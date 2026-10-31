//! Per-(client IP, subdomain) throttle on invalid integration secrets (#811).
//!
//! After `max_failures` invalid-secret attempts inside the window, every
//! further attempt from that IP on that subdomain is closed -- including one
//! that carries a correct secret, so the block reveals nothing about which
//! guesses were right -- and none of them may trigger a device wake.

mod test_helpers;

use rouse_relay::passthrough::{
    resolve_device_stream_for_client, PassthroughContext, PassthroughError, SessionRegistry,
};
use rouse_relay::rate_limit::{FcmWakeThrottle, WrongSecretThrottle};
use rouse_relay::state::RelayState;
use std::net::IpAddr;
use std::sync::Arc;
use std::time::{Duration, Instant, SystemTime};
use test_helpers::{MockFcm, MockFirestore, MockUnifiedPush};

const SUB: &str = "cool-penguin";
const GOOD: &str = "brave-falcon";

fn ip(s: &str) -> IpAddr {
    s.parse().unwrap()
}

fn device_record() -> rouse_relay::firestore::DeviceRecord {
    rouse_relay::firestore::DeviceRecord {
        fcm_token: "fcm-token".to_string(),
        firebase_uid: "uid".to_string(),
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
        valid_secrets: vec![GOOD.to_string()],
        integration_secrets: std::collections::HashMap::new(),
        retired_secrets: std::collections::HashMap::new(),
    }
}

/// Context for an offline device: a correct secret reaches the wake path,
/// sends a push, and then times out quickly.
fn offline_ctx(fcm: Arc<MockFcm>, subdomains: &[&str]) -> PassthroughContext {
    let mut firestore = MockFirestore::new();
    for s in subdomains {
        firestore = firestore.with_device(s, device_record());
    }
    PassthroughContext {
        relay_state: Arc::new(RelayState::new()),
        session_registry: Arc::new(SessionRegistry::new()),
        firestore: Arc::new(firestore),
        fcm,
        unifiedpush: Arc::new(MockUnifiedPush::new()),
        relay_hostname: "relay.rousecontext.com".to_string(),
        fcm_wakeup_timeout: Duration::from_millis(50),
        // Zero cooldown: every attempt that reaches the wake path sends a push,
        // so the push count tells us exactly which attempts got through.
        fcm_wake_throttle: Arc::new(FcmWakeThrottle::new(Duration::ZERO)),
    }
}

fn sni(secret: &str, sub: &str) -> String {
    format!("{secret}.{sub}.rousecontext.com")
}

async fn attempt(
    ctx: &PassthroughContext,
    throttle: &WrongSecretThrottle,
    client: IpAddr,
    sub: &str,
    secret: &str,
) -> Result<(), PassthroughError> {
    resolve_device_stream_for_client(ctx, throttle, client, sub, &sni(secret, sub), secret)
        .await
        .map(|_| ())
}

async fn five_wrong(
    ctx: &PassthroughContext,
    throttle: &WrongSecretThrottle,
    client: IpAddr,
    sub: &str,
) {
    for i in 0..5 {
        let r = attempt(ctx, throttle, client, sub, &format!("wrong-guess{i}")).await;
        assert!(
            matches!(r, Err(PassthroughError::InvalidSecret)),
            "attempt {i} should be a plain invalid-secret reject, got {r:?}"
        );
    }
}

fn pushes(fcm: &MockFcm) -> usize {
    fcm.sent.lock().unwrap().len()
}

#[tokio::test]
async fn correct_secret_after_five_wrong_is_closed_without_wake() {
    let fcm = Arc::new(MockFcm::new());
    let ctx = offline_ctx(fcm.clone(), &[SUB]);
    let throttle = WrongSecretThrottle::new(5, Duration::from_secs(3600), 1000);
    let client = ip("203.0.113.7");

    five_wrong(&ctx, &throttle, client, SUB).await;
    assert_eq!(pushes(&fcm), 0, "wrong secrets never wake");

    let r = attempt(&ctx, &throttle, client, SUB, GOOD).await;
    assert!(
        matches!(r, Err(PassthroughError::SecretThrottled)),
        "6th attempt with the correct secret must be closed, got {r:?}"
    );
    assert_eq!(pushes(&fcm), 0, "a blocked attempt must never wake the device");
}

#[tokio::test]
async fn fewer_than_threshold_failures_do_not_block() {
    let fcm = Arc::new(MockFcm::new());
    let ctx = offline_ctx(fcm.clone(), &[SUB]);
    let throttle = WrongSecretThrottle::new(5, Duration::from_secs(3600), 1000);
    let client = ip("203.0.113.7");

    for i in 0..4 {
        let _ = attempt(&ctx, &throttle, client, SUB, &format!("wrong{i}")).await;
    }
    let r = attempt(&ctx, &throttle, client, SUB, GOOD).await;
    assert!(
        matches!(r, Err(PassthroughError::WakeupTimeout)),
        "4 failures is under the threshold; correct secret reaches the wake path, got {r:?}"
    );
    assert_eq!(pushes(&fcm), 1);
}

#[tokio::test]
async fn window_expiry_restores_access() {
    let fcm = Arc::new(MockFcm::new());
    let ctx = offline_ctx(fcm.clone(), &[SUB]);
    let throttle = WrongSecretThrottle::new(5, Duration::from_millis(300), 1000);
    let client = ip("203.0.113.7");

    five_wrong(&ctx, &throttle, client, SUB).await;
    assert!(matches!(
        attempt(&ctx, &throttle, client, SUB, GOOD).await,
        Err(PassthroughError::SecretThrottled)
    ));

    tokio::time::sleep(Duration::from_millis(400)).await;

    let r = attempt(&ctx, &throttle, client, SUB, GOOD).await;
    assert!(
        matches!(r, Err(PassthroughError::WakeupTimeout)),
        "after the window the correct secret reaches the wake path again, got {r:?}"
    );
    assert_eq!(pushes(&fcm), 1);
}

#[tokio::test]
async fn different_ip_is_unaffected() {
    let fcm = Arc::new(MockFcm::new());
    let ctx = offline_ctx(fcm.clone(), &[SUB]);
    let throttle = WrongSecretThrottle::new(5, Duration::from_secs(3600), 1000);

    five_wrong(&ctx, &throttle, ip("203.0.113.7"), SUB).await;

    let r = attempt(&ctx, &throttle, ip("198.51.100.9"), SUB, GOOD).await;
    assert!(
        matches!(r, Err(PassthroughError::WakeupTimeout)),
        "another IP on the same subdomain is not blocked, got {r:?}"
    );
    assert_eq!(pushes(&fcm), 1);
}

#[tokio::test]
async fn different_subdomain_is_unaffected() {
    let fcm = Arc::new(MockFcm::new());
    let ctx = offline_ctx(fcm.clone(), &[SUB, "other-device"]);
    let throttle = WrongSecretThrottle::new(5, Duration::from_secs(3600), 1000);
    let client = ip("203.0.113.7");

    five_wrong(&ctx, &throttle, client, SUB).await;

    let r = attempt(&ctx, &throttle, client, "other-device", GOOD).await;
    assert!(
        matches!(r, Err(PassthroughError::WakeupTimeout)),
        "the same IP on another subdomain is not blocked, got {r:?}"
    );
    assert_eq!(pushes(&fcm), 1);
}

#[tokio::test]
async fn correct_secrets_do_not_count_as_failures() {
    let fcm = Arc::new(MockFcm::new());
    let ctx = offline_ctx(fcm.clone(), &[SUB]);
    let throttle = WrongSecretThrottle::new(5, Duration::from_secs(3600), 1000);
    let client = ip("203.0.113.7");

    for _ in 0..8 {
        let r = attempt(&ctx, &throttle, client, SUB, GOOD).await;
        assert!(matches!(r, Err(PassthroughError::WakeupTimeout)));
    }
    assert_eq!(pushes(&fcm), 8);
}

// --- Throttle-level tests (injected clock) ---

#[test]
fn throttle_blocks_after_threshold_until_window_ends() {
    let t = WrongSecretThrottle::new(5, Duration::from_secs(3600), 1000);
    let a = ip("203.0.113.7");
    let t0 = Instant::now();
    for i in 0..5 {
        assert!(!t.is_blocked_at(a, SUB, t0), "not blocked before failure {i}");
        t.record_failure_at(a, SUB, t0 + Duration::from_secs(i));
    }
    assert!(t.is_blocked_at(a, SUB, t0 + Duration::from_secs(10)));
    assert!(t.is_blocked_at(a, SUB, t0 + Duration::from_secs(3599)));
    assert!(!t.is_blocked_at(a, SUB, t0 + Duration::from_secs(3600)));
}

#[test]
fn state_stays_bounded_under_many_distinct_keys() {
    let cap = 64;
    let t = WrongSecretThrottle::new(5, Duration::from_secs(3600), cap);
    let t0 = Instant::now();
    for i in 0..10_000u32 {
        let addr = IpAddr::from(std::net::Ipv4Addr::from(0x0a00_0000 + i));
        t.record_failure_at(addr, &format!("sub{}", i % 7), t0);
    }
    assert!(t.len() <= cap, "len {} exceeds cap {cap}", t.len());
}

#[test]
fn expired_entries_are_swept_and_make_room() {
    let cap = 8;
    let t = WrongSecretThrottle::new(5, Duration::from_secs(60), cap);
    let t0 = Instant::now();
    for i in 0..cap as u32 {
        t.record_failure_at(IpAddr::from(std::net::Ipv4Addr::from(i)), SUB, t0);
    }
    assert_eq!(t.len(), cap);

    t.sweep_expired_at(t0 + Duration::from_secs(59));
    assert_eq!(t.len(), cap, "nothing evicted inside the window");
    t.sweep_expired_at(t0 + Duration::from_secs(61));
    assert_eq!(t.len(), 0, "all entries evicted after the window");

    // A full table of expired entries must not stop new failures being tracked.
    for i in 0..cap as u32 {
        t.record_failure_at(IpAddr::from(std::net::Ipv4Addr::from(i)), SUB, t0);
    }
    let fresh = ip("203.0.113.7");
    let later = t0 + Duration::from_secs(120);
    for _ in 0..5 {
        t.record_failure_at(fresh, SUB, later);
    }
    assert!(t.len() <= cap);
    assert!(
        t.is_blocked_at(fresh, SUB, later),
        "expired entries are reclaimed so a new offender is still tracked"
    );
}
