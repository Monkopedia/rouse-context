//! Tests that `/rotate-secret` (and `/register`) populate the in-memory
//! `valid_secrets_cache` so the passthrough path accepts newly-pushed
//! secrets immediately, even if Firestore is lagging due to eventual
//! consistency.
//!
//! This closes issue #101: users reported adding a new integration and
//! then seeing `unexpected eof` from the relay because the SNI router
//! read a stale `valid_secrets` list from Firestore. After this fix,
//! the write endpoints seed a cache that takes precedence over the
//! Firestore value until the cache is cleared.

mod test_helpers;

use rouse_relay::api::build_router;
use rouse_relay::api::ws::DeviceIdentity;
use rouse_relay::firestore::DeviceRecord;
use rouse_relay::mux::frame::Frame;
use rouse_relay::mux::lifecycle::MuxSession;
use rouse_relay::passthrough::{
    resolve_device_stream, OpenStreamRequest, PassthroughContext, SessionRegistry,
};
use rouse_relay::rate_limit::FcmWakeThrottle;
use rouse_relay::state::RelayState;
use std::sync::Arc;
use std::time::{Duration, SystemTime};
use test_helpers::*;
use tokio::sync::mpsc;

fn make_device(secret: &str) -> DeviceRecord {
    DeviceRecord {
        fcm_token: "fcm-tok".to_string(),
        firebase_uid: "uid-1".to_string(),
        public_key: "key".to_string(),
        cert_expires: SystemTime::now() + Duration::from_secs(86400),
        registered_at: SystemTime::now(),
        last_rotation: None,
        renewal_nudge_sent: None,
        secret_prefix: Some(secret.to_string()),
        valid_secrets: vec!["old-health".to_string()],
        integration_secrets: std::collections::HashMap::new(),
    }
}

fn setup_device(
    relay_state: &Arc<RelayState>,
    registry: &Arc<SessionRegistry>,
    subdomain: &str,
) -> mpsc::Receiver<Frame> {
    let mut session = MuxSession::new(subdomain.to_string(), 8);
    let frame_rx = session.take_frame_rx().unwrap();
    let (open_tx, mut open_rx) = mpsc::channel::<OpenStreamRequest>(16);
    let (kill_tx, _kill_rx) = mpsc::channel::<()>(1);
    relay_state.register_mux_connection(subdomain);
    registry.insert(subdomain, open_tx, kill_tx);
    tokio::spawn(async move {
        while let Some(req) = open_rx.recv().await {
            let result = session
                .open_stream()
                .map(|stream| (session.handle(), stream));
            let _ = req.reply.send(result);
        }
    });
    frame_rx
}

fn make_ctx(
    relay_state: Arc<RelayState>,
    registry: Arc<SessionRegistry>,
    firestore: Arc<MockFirestore>,
    fcm: Arc<MockFcm>,
) -> PassthroughContext {
    PassthroughContext {
        relay_state,
        session_registry: registry,
        firestore,
        fcm,
        relay_hostname: "relay.rousecontext.com".to_string(),
        fcm_wakeup_timeout: Duration::from_secs(1),
        fcm_wake_throttle: Arc::new(FcmWakeThrottle::new(Duration::from_secs(30))),
    }
}

/// After `/rotate-secret`, a passthrough connection using a freshly-pushed
/// secret must succeed even when Firestore still reports the old list.
/// The in-memory cache is the source of truth for secret validation after
/// a write.
#[tokio::test]
async fn rotate_secret_updates_cache_and_passthrough_accepts_new_secret() {
    // Firestore starts with the OLD secret list.
    let firestore =
        Arc::new(MockFirestore::new().with_device("cool-penguin", make_device("old-health")));

    // Build the AppState manually so we can reuse the same RelayState between
    // the API call and the passthrough call.
    let tmp = tempfile::tempdir().expect("tempdir");
    let ca = rouse_relay::device_ca::DeviceCa::load_or_create(
        &tmp.path().join("ca_key.pem"),
        &tmp.path().join("ca_cert.pem"),
    )
    .expect("ca");
    std::mem::forget(tmp);

    let relay_state = Arc::new(RelayState::new());
    let session_registry = Arc::new(rouse_relay::passthrough::SessionRegistry::new());
    let app_state = Arc::new(rouse_relay::api::AppState {
        relay_state: relay_state.clone(),
        session_registry: session_registry.clone(),
        firestore: firestore.clone(),
        fcm: Arc::new(MockFcm::new()),
        acme: Arc::new(MockAcme::new("test-cert")),
        dns: Arc::new(MockDns::new()),
        firebase_auth: Arc::new(MockFirebaseAuth::new()),
        subdomain_generator: rouse_relay::subdomain::SubdomainGenerator::new(),
        rate_limiter: rouse_relay::rate_limit::RateLimiter::new(
            rouse_relay::rate_limit::RateLimitConfig::default(),
        ),
        request_subdomain_rate_limiter: rouse_relay::rate_limit::RateLimiter::new(
            rouse_relay::rate_limit::RateLimitConfig {
                max_tokens: 3,
                refill_interval: Duration::from_secs(20),
            },
        ),
        config: rouse_relay::config::RelayConfig::default(),
        device_ca: Some(ca),
        #[cfg(feature = "test-mode")]
        test_metrics: None,
    });

    // Hit /rotate-secret asking the relay to generate a fresh secret for
    // "usage". The relay picks an adjective and returns the mapping.
    // DeviceIdentity is injected via a layer to simulate an mTLS-validated
    // client cert for "cool-penguin" (per issue #202 the handler now
    // requires this; there is no body subdomain fallback).
    let app = build_router(app_state.clone()).layer(axum::Extension(DeviceIdentity {
        subdomain: "cool-penguin".to_string(),
    }));
    let resp = axum::http::Request::builder()
        .method("POST")
        .uri("/rotate-secret")
        .header("content-type", "application/json")
        .body(axum::body::Body::from(
            serde_json::json!({
                "integrations": ["usage"]
            })
            .to_string(),
        ))
        .unwrap();
    let resp = tower::ServiceExt::oneshot(app, resp).await.unwrap();
    assert_eq!(resp.status(), 200);

    let body = axum::body::to_bytes(resp.into_body(), 1024 * 1024)
        .await
        .unwrap();
    let json: serde_json::Value = serde_json::from_slice(&body).unwrap();
    let new_secret = json["secrets"]["usage"]
        .as_str()
        .expect("rotate-secret must return a usage secret")
        .to_string();

    // Simulate eventual-consistency lag: force Firestore to still return the
    // OLD list so the cache is the only source that knows about the new secret.
    firestore.devices.lock().unwrap().insert(
        "cool-penguin".to_string(),
        make_device("old-health"), // valid_secrets = ["old-health"]
    );

    // Now simulate a passthrough connection using the new secret.
    let _frame_rx = setup_device(&relay_state, &session_registry, "cool-penguin");
    let ctx = make_ctx(
        relay_state,
        session_registry,
        firestore,
        Arc::new(MockFcm::new()),
    );
    let sni = format!("{new_secret}.cool-penguin.rousecontext.com");
    let result = resolve_device_stream(&ctx, "cool-penguin", &sni, &new_secret).await;

    assert!(
        result.is_ok(),
        "passthrough should accept new secret from cache, got: {:?}",
        result.err()
    );
}

/// Passthrough should reject a secret that is not in the cache (and the
/// cache is authoritative once seeded).
///
/// Regression note (#216): `/rotate-secret` generates
/// `{adjective}-{integrationId}` secrets using `rand::thread_rng()`, drawing
/// the adjective from the fixed list in `words::adjectives` which contains
/// `"old"`. Rotating integration `"health"` therefore had a ~1/217 chance of
/// producing the literal secret `"old-health"`, making this test flaky under
/// any amount of parallel or repeated execution. To keep the assertion
/// structurally collision-proof, we rotate a DIFFERENT integration
/// (`"usage"`) than the one whose legacy secret we replay (`"old-health"`):
/// the rotation produces `{adj}-usage`, which can never equal `"old-health"`
/// for any adjective in the list.
#[tokio::test]
async fn rotate_secret_cache_rejects_secrets_not_in_list() {
    let firestore =
        Arc::new(MockFirestore::new().with_device("cool-penguin", make_device("old-health")));

    let tmp = tempfile::tempdir().expect("tempdir");
    let ca = rouse_relay::device_ca::DeviceCa::load_or_create(
        &tmp.path().join("ca_key.pem"),
        &tmp.path().join("ca_cert.pem"),
    )
    .expect("ca");
    std::mem::forget(tmp);

    let relay_state = Arc::new(RelayState::new());
    let session_registry = Arc::new(rouse_relay::passthrough::SessionRegistry::new());
    let app_state = Arc::new(rouse_relay::api::AppState {
        relay_state: relay_state.clone(),
        session_registry: session_registry.clone(),
        firestore: firestore.clone(),
        fcm: Arc::new(MockFcm::new()),
        acme: Arc::new(MockAcme::new("test-cert")),
        dns: Arc::new(MockDns::new()),
        firebase_auth: Arc::new(MockFirebaseAuth::new()),
        subdomain_generator: rouse_relay::subdomain::SubdomainGenerator::new(),
        rate_limiter: rouse_relay::rate_limit::RateLimiter::new(
            rouse_relay::rate_limit::RateLimitConfig::default(),
        ),
        request_subdomain_rate_limiter: rouse_relay::rate_limit::RateLimiter::new(
            rouse_relay::rate_limit::RateLimitConfig {
                max_tokens: 3,
                refill_interval: Duration::from_secs(20),
            },
        ),
        config: rouse_relay::config::RelayConfig::default(),
        device_ca: Some(ca),
        #[cfg(feature = "test-mode")]
        test_metrics: None,
    });

    // Rotate the "usage" integration (not "health"). After this call the
    // cache holds `[{adj}-usage]` for exactly one adjective pick, so the
    // legacy `"old-health"` secret cannot be present in the cache under any
    // RNG outcome.
    // DeviceIdentity is layered in to simulate mTLS (see #202).
    let app = build_router(app_state.clone()).layer(axum::Extension(DeviceIdentity {
        subdomain: "cool-penguin".to_string(),
    }));
    let resp = axum::http::Request::builder()
        .method("POST")
        .uri("/rotate-secret")
        .header("content-type", "application/json")
        .body(axum::body::Body::from(
            serde_json::json!({
                "integrations": ["usage"]
            })
            .to_string(),
        ))
        .unwrap();
    let resp = tower::ServiceExt::oneshot(app, resp).await.unwrap();
    assert_eq!(resp.status(), 200);

    let _frame_rx = setup_device(&relay_state, &session_registry, "cool-penguin");
    let ctx = make_ctx(
        relay_state,
        session_registry,
        firestore,
        Arc::new(MockFcm::new()),
    );

    // An unknown secret (the legacy "old-health", which is never in the
    // rotated `{adj}-usage` set) must be rejected even if the legacy
    // secret_prefix in Firestore matches.
    let result = resolve_device_stream(
        &ctx,
        "cool-penguin",
        "old-health.cool-penguin.rousecontext.com",
        "old-health",
    )
    .await;
    assert!(matches!(
        result,
        Err(rouse_relay::passthrough::PassthroughError::InvalidSecret)
    ));
}

/// Passthrough falls through to Firestore when the cache has no entry
/// (cold-start / restart scenarios).
#[tokio::test]
async fn passthrough_cache_miss_falls_back_to_firestore() {
    let firestore = Arc::new(MockFirestore::new().with_device(
        "cool-penguin",
        DeviceRecord {
            fcm_token: "fcm-tok".to_string(),
            firebase_uid: "uid-1".to_string(),
            public_key: "key".to_string(),
            cert_expires: SystemTime::now() + Duration::from_secs(86400),
            registered_at: SystemTime::now(),
            last_rotation: None,
            renewal_nudge_sent: None,
            secret_prefix: None,
            valid_secrets: vec!["brave-health".to_string()],
            integration_secrets: std::collections::HashMap::new(),
        },
    ));

    let relay_state = Arc::new(RelayState::new());
    let session_registry = Arc::new(SessionRegistry::new());
    let _frame_rx = setup_device(&relay_state, &session_registry, "cool-penguin");

    // Cache is empty: resolve should read Firestore and accept the secret.
    let ctx = make_ctx(
        relay_state.clone(),
        session_registry,
        firestore,
        Arc::new(MockFcm::new()),
    );
    let result = resolve_device_stream(
        &ctx,
        "cool-penguin",
        "brave-health.cool-penguin.rousecontext.com",
        "brave-health",
    )
    .await;
    assert!(
        result.is_ok(),
        "cache-miss should fall back to Firestore, got: {:?}",
        result.err()
    );

    // Cache should have been populated from Firestore for future lookups.
    let cached = relay_state.get_valid_secrets_cache("cool-penguin");
    assert_eq!(cached, Some(vec!["brave-health".to_string()]));
}
