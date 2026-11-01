//! Former secrets do not count toward the wrong-secret throttle (#811),
//! exercised through the real `/rotate-secret` handler.
//!
//! A stale client config (an old connector whose URL was replaced on
//! disable/re-enable) retrying from the same egress IP must not lock out the
//! working URL. Its attempts are still rejected like any invalid secret, with
//! no wake.

mod test_helpers;

use rouse_relay::api::build_router;
use rouse_relay::api::ws::DeviceIdentity;
use rouse_relay::api::AppState;
use rouse_relay::firestore::{DeviceRecord, STALE_SECRETS_MAX};
use rouse_relay::passthrough::{
    resolve_device_stream_for_client, PassthroughContext, PassthroughError, SessionRegistry,
};
use rouse_relay::rate_limit::{FcmWakeThrottle, WrongSecretThrottle};
use rouse_relay::state::RelayState;
use std::collections::HashMap;
use std::net::IpAddr;
use std::sync::Arc;
use std::time::{Duration, SystemTime};
use test_helpers::*;

const SUB: &str = "cool-penguin";
const A: &str = "brave-alpha";
const B: &str = "clever-beta";

fn device() -> DeviceRecord {
    DeviceRecord {
        fcm_token: "fcm-tok".to_string(),
        firebase_uid: "uid-1".to_string(),
        key_thumbprint: None,
        public_key: "key".to_string(),
        key_binding: rouse_relay::firestore::KeyBinding::Bound,
        cert_expires: SystemTime::now() + Duration::from_secs(86400),
        registered_at: SystemTime::now(),
        last_seen: None,
        last_rotation: None,
        renewal_nudge_sent: None,
        secret_prefix: None,
        push_kind: Default::default(),
        push_endpoint: String::new(),
        valid_secrets: vec![A.to_string(), B.to_string()],
        integration_secrets: HashMap::from([
            ("alpha".to_string(), A.to_string()),
            ("beta".to_string(), B.to_string()),
        ]),
        retired_secrets: HashMap::new(),
        stale_secrets: Vec::new(),
    }
}

struct Harness {
    state: Arc<AppState>,
    firestore: Arc<MockFirestore>,
    fcm: Arc<MockFcm>,
    throttle: WrongSecretThrottle,
    client: IpAddr,
}

impl Harness {
    fn new() -> Self {
        let firestore = Arc::new(MockFirestore::new().with_device(SUB, device()));
        let fcm = Arc::new(MockFcm::new());
        let state = build_test_state_with_ca(
            firestore.clone(),
            fcm.clone(),
            Arc::new(MockAcme::new("test-cert")),
            Arc::new(MockFirebaseAuth::new()),
        );
        Self {
            state,
            firestore,
            fcm,
            throttle: WrongSecretThrottle::new(5, Duration::from_secs(3600), 1000),
            client: "203.0.113.7".parse().unwrap(),
        }
    }

    /// Call the real `/rotate-secret` handler; returns the new secrets map.
    async fn rotate(&self, integrations: &[&str]) -> HashMap<String, String> {
        let app = build_router(self.state.clone()).layer(axum::Extension(DeviceIdentity {
            subdomain: SUB.to_string(),
        }));
        let req = axum::http::Request::builder()
            .method("POST")
            .uri("/rotate-secret")
            .header("content-type", "application/json")
            .body(axum::body::Body::from(
                serde_json::json!({ "integrations": integrations }).to_string(),
            ))
            .unwrap();
        let resp = tower::ServiceExt::oneshot(app, req).await.unwrap();
        assert_eq!(resp.status(), 200);
        let body = axum::body::to_bytes(resp.into_body(), 1024 * 1024)
            .await
            .unwrap();
        let json: serde_json::Value = serde_json::from_slice(&body).unwrap();
        json["secrets"]
            .as_object()
            .unwrap()
            .iter()
            .map(|(k, v)| (k.clone(), v.as_str().unwrap().to_string()))
            .collect()
    }

    /// Passthrough context sharing the handler's relay state (warm cache).
    fn ctx(&self) -> PassthroughContext {
        self.ctx_with(self.state.relay_state.clone())
    }

    /// Passthrough context on fresh relay state (restart: cold cache).
    fn cold_ctx(&self) -> PassthroughContext {
        self.ctx_with(Arc::new(RelayState::new()))
    }

    fn ctx_with(&self, relay_state: Arc<RelayState>) -> PassthroughContext {
        PassthroughContext {
            relay_state,
            session_registry: Arc::new(SessionRegistry::new()),
            firestore: self.firestore.clone(),
            fcm: self.fcm.clone(),
            unifiedpush: Arc::new(MockUnifiedPush::new()),
            relay_hostname: "relay.rousecontext.com".to_string(),
            fcm_wakeup_timeout: Duration::from_millis(50),
            fcm_wake_throttle: Arc::new(FcmWakeThrottle::new(Duration::ZERO)),
        }
    }

    async fn attempt(
        &self,
        ctx: &PassthroughContext,
        secret: &str,
    ) -> Result<(), PassthroughError> {
        let sni = format!("{secret}.{SUB}.rousecontext.com");
        resolve_device_stream_for_client(ctx, &self.throttle, self.client, SUB, &sni, secret)
            .await
            .map(|_| ())
    }

    fn pushes(&self) -> usize {
        self.fcm.sent.lock().unwrap().len()
    }

    fn stored(&self) -> DeviceRecord {
        self.firestore
            .devices
            .lock()
            .unwrap()
            .get(SUB)
            .unwrap()
            .clone()
    }
}

/// Disable beta, then re-enable it (new secret). Returns the new beta secret.
async fn disable_then_reenable_beta(h: &Harness) -> String {
    let after_disable = h.rotate(&["alpha"]).await;
    assert!(!after_disable.contains_key("beta"));
    let after_enable = h.rotate(&["alpha", "beta"]).await;
    let new_b = after_enable["beta"].clone();
    assert_ne!(new_b, B, "re-enable mints a new secret (#519)");
    new_b
}

async fn old_url_does_not_lock_out_new_url(h: &Harness, ctx: &PassthroughContext, new_b: &str) {
    for i in 0..20 {
        let r = h.attempt(ctx, B).await;
        assert!(
            matches!(r, Err(PassthroughError::InvalidSecret)),
            "old URL attempt {i} is rejected like any invalid secret, got {r:?}"
        );
    }
    assert_eq!(h.pushes(), 0, "the old URL never wakes the device");
    assert!(!h.throttle.is_blocked(h.client, SUB));

    let r = h.attempt(ctx, new_b).await;
    assert!(
        matches!(r, Err(PassthroughError::WakeupTimeout)),
        "new URL is still admitted, got {r:?}"
    );
    let r = h.attempt(ctx, A).await;
    assert!(matches!(r, Err(PassthroughError::WakeupTimeout)));
}

#[tokio::test]
async fn reenable_then_old_url_attempts_do_not_block_new_url() {
    let h = Harness::new();
    let new_b = disable_then_reenable_beta(&h).await;
    old_url_does_not_lock_out_new_url(&h, &h.ctx(), &new_b).await;
}

#[tokio::test]
async fn reenable_then_old_url_attempts_do_not_block_new_url_after_restart() {
    let h = Harness::new();
    let new_b = disable_then_reenable_beta(&h).await;
    // Restart: the secrets cache is empty, so the stale list must come from
    // the persisted record.
    assert!(h.stored().stale_secrets.contains(&B.to_string()));
    old_url_does_not_lock_out_new_url(&h, &h.cold_ctx(), &new_b).await;
}

#[tokio::test]
async fn disable_keeps_retired_semantics_and_marks_secret_stale() {
    let h = Harness::new();
    h.rotate(&["alpha"]).await;
    let rec = h.stored();
    // #519 role unchanged: the dropped secret is parked in retired_secrets.
    assert_eq!(rec.retired_secrets.get("beta").map(String::as_str), Some(B));
    assert_eq!(rec.stale_secrets, vec![B.to_string()]);

    h.rotate(&["alpha", "beta"]).await;
    let rec = h.stored();
    assert!(
        rec.retired_secrets.is_empty(),
        "re-enable clears retired (#519)"
    );
    assert_eq!(rec.stale_secrets, vec![B.to_string()], "but B stays stale");
}

#[tokio::test]
async fn stale_and_valid_overlap_is_valid() {
    let h = Harness::new();
    // A label that is both stale and currently valid is treated as valid.
    h.state
        .relay_state
        .set_secrets_cache(SUB, vec![A.to_string()], vec![A.to_string()]);
    let r = h.attempt(&h.ctx(), A).await;
    assert!(
        matches!(r, Err(PassthroughError::WakeupTimeout)),
        "valid wins over stale, got {r:?}"
    );

    // Same on the cold path, from the stored record.
    let mut rec = device();
    rec.stale_secrets = vec![A.to_string()];
    h.firestore
        .devices
        .lock()
        .unwrap()
        .insert(SUB.to_string(), rec);
    let r = h.attempt(&h.cold_ctx(), A).await;
    assert!(matches!(r, Err(PassthroughError::WakeupTimeout)));
    assert_eq!(h.pushes(), 2);
}

#[tokio::test]
async fn stale_list_is_bounded_keeping_the_most_recent() {
    let h = Harness::new();
    let mut former = vec![B.to_string()];
    for _ in 0..(STALE_SECRETS_MAX + 6) {
        h.rotate(&["alpha"]).await;
        let s = h.rotate(&["alpha", "beta"]).await;
        former.push(s["beta"].clone());
    }
    // The last minted beta secret is live; all earlier ones are former.
    let live = former.pop().unwrap();
    let rec = h.stored();
    assert_eq!(rec.stale_secrets.len(), STALE_SECRETS_MAX);
    assert!(!rec.stale_secrets.contains(&live));
    let mut expected: Vec<String> = Vec::new();
    for s in &former {
        expected.retain(|x| x != s);
        expected.push(s.clone());
    }
    let tail = expected[expected.len() - STALE_SECRETS_MAX..].to_vec();
    assert_eq!(rec.stale_secrets, tail, "oldest dropped, most recent kept");
}

#[tokio::test]
async fn random_guesses_still_count_after_rotation() {
    let h = Harness::new();
    disable_then_reenable_beta(&h).await;
    let ctx = h.ctx();
    for i in 0..4 {
        let _ = h.attempt(&ctx, B).await;
        let r = h.attempt(&ctx, &format!("guess{i}")).await;
        assert!(matches!(r, Err(PassthroughError::InvalidSecret)));
    }
    assert!(!h.throttle.is_blocked(h.client, SUB));
    let _ = h.attempt(&ctx, "guess4").await;
    assert!(h.throttle.is_blocked(h.client, SUB));
    assert!(matches!(
        h.attempt(&ctx, A).await,
        Err(PassthroughError::SecretThrottled)
    ));
}
