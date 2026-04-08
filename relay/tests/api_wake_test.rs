//! Tests for POST /wake/:subdomain endpoint.

mod test_helpers;

use rouse_relay::api::build_router;
use rouse_relay::firestore::DeviceRecord;
use rouse_relay::rate_limit::{RateLimitConfig, RateLimiter};
use rouse_relay::state::RelayState;
use std::sync::Arc;
use std::time::{Duration, SystemTime};
use test_helpers::*;

fn make_device_record() -> DeviceRecord {
    DeviceRecord {
        fcm_token: "device-fcm-token".to_string(),
        firebase_uid: "uid-789".to_string(),
        public_key: "test-pubkey".to_string(),
        cert_expires: SystemTime::now() + Duration::from_secs(86400),
        registered_at: SystemTime::now(),
        last_rotation: None,
        renewal_nudge_sent: None,
        secret_prefix: None,
    }
}

fn make_app_with_state(
    firestore: Arc<MockFirestore>,
    fcm: Arc<MockFcm>,
    relay_state: Arc<RelayState>,
    rate_limiter: RateLimiter,
) -> axum::Router {
    let state = Arc::new(rouse_relay::api::AppState {
        relay_state,
        session_registry: Arc::new(rouse_relay::passthrough::SessionRegistry::new()),
        firestore,
        fcm,
        acme: Arc::new(MockAcme::new("cert")),
        firebase_auth: Arc::new(MockFirebaseAuth::new()),
        subdomain_generator: rouse_relay::subdomain::SubdomainGenerator::new(),
        rate_limiter,
        config: rouse_relay::config::RelayConfig::default(),
        device_ca: None,
    });
    build_router(state)
}

#[ignore = "wake endpoint disabled"]
#[tokio::test]
async fn wake_online_device_returns_200_immediately() {
    let firestore =
        Arc::new(MockFirestore::new().with_device("brave-falcon", make_device_record()));
    let fcm = Arc::new(MockFcm::new());
    let relay_state = Arc::new(RelayState::new());
    // Simulate device being online
    relay_state.register_mux_connection("brave-falcon");

    let rate_limiter = RateLimiter::new(RateLimitConfig::default());
    let app = make_app_with_state(firestore, fcm.clone(), relay_state, rate_limiter);

    let resp = axum::http::Request::builder()
        .method("POST")
        .uri("/wake/brave-falcon")
        .body(axum::body::Body::empty())
        .unwrap();

    let resp = tower::ServiceExt::oneshot(app, resp).await.unwrap();
    assert_eq!(resp.status(), 200);

    let body = axum::body::to_bytes(resp.into_body(), 1024 * 1024)
        .await
        .unwrap();
    let json: serde_json::Value = serde_json::from_slice(&body).unwrap();
    assert_eq!(json["status"].as_str().unwrap(), "ready");

    // No FCM should have been sent
    assert!(fcm.sent.lock().unwrap().is_empty());
}

#[ignore = "wake endpoint disabled"]
#[tokio::test]
async fn wake_offline_device_sends_fcm_and_waits() {
    let firestore =
        Arc::new(MockFirestore::new().with_device("brave-falcon", make_device_record()));
    let fcm = Arc::new(MockFcm::new());
    let relay_state = Arc::new(RelayState::new());
    // Device is NOT online

    let rate_limiter = RateLimiter::new(RateLimitConfig::default());
    let app = make_app_with_state(firestore, fcm.clone(), relay_state.clone(), rate_limiter);

    // Spawn the request in a task, then simulate the device connecting
    let relay_state_clone = relay_state.clone();
    let handle = tokio::spawn(async move {
        let resp = axum::http::Request::builder()
            .method("POST")
            .uri("/wake/brave-falcon")
            .body(axum::body::Body::empty())
            .unwrap();
        tower::ServiceExt::oneshot(app, resp).await.unwrap()
    });

    // Give the handler a moment to start waiting
    tokio::time::sleep(Duration::from_millis(50)).await;

    // Simulate device connecting
    relay_state_clone.register_mux_connection("brave-falcon");

    let resp = handle.await.unwrap();
    assert_eq!(resp.status(), 200);

    // FCM should have been sent
    let sent = fcm.sent.lock().unwrap();
    assert_eq!(sent.len(), 1);
    assert_eq!(sent[0].0, "device-fcm-token");
    assert_eq!(sent[0].1.message_type, "wake");
    assert!(sent[0].2); // high priority
}

#[ignore = "wake endpoint disabled"]
#[tokio::test]
async fn wake_device_not_found_returns_404() {
    let firestore = Arc::new(MockFirestore::new()); // empty
    let fcm = Arc::new(MockFcm::new());
    let relay_state = Arc::new(RelayState::new());
    let rate_limiter = RateLimiter::new(RateLimitConfig::default());

    let app = make_app_with_state(firestore, fcm, relay_state, rate_limiter);

    let resp = axum::http::Request::builder()
        .method("POST")
        .uri("/wake/nonexistent")
        .body(axum::body::Body::empty())
        .unwrap();

    let resp = tower::ServiceExt::oneshot(app, resp).await.unwrap();
    assert_eq!(resp.status(), 404);
}

#[ignore = "wake endpoint disabled"]
#[tokio::test]
async fn wake_rate_limit_returns_429() {
    let firestore =
        Arc::new(MockFirestore::new().with_device("brave-falcon", make_device_record()));
    let fcm = Arc::new(MockFcm::new());
    let relay_state = Arc::new(RelayState::new());
    relay_state.register_mux_connection("brave-falcon");

    // Rate limiter with only 2 tokens
    let rate_limiter = RateLimiter::new(RateLimitConfig {
        max_tokens: 2,
        refill_interval: Duration::from_secs(10),
    });

    let state = Arc::new(rouse_relay::api::AppState {
        relay_state,
        session_registry: Arc::new(rouse_relay::passthrough::SessionRegistry::new()),
        firestore,
        fcm,
        acme: Arc::new(MockAcme::new("cert")),
        firebase_auth: Arc::new(MockFirebaseAuth::new()),
        subdomain_generator: rouse_relay::subdomain::SubdomainGenerator::new(),
        rate_limiter,
        config: rouse_relay::config::RelayConfig::default(),
        device_ca: None,
    });

    // First two requests should succeed
    for _ in 0..2 {
        let app = build_router(state.clone());
        let resp = axum::http::Request::builder()
            .method("POST")
            .uri("/wake/brave-falcon")
            .body(axum::body::Body::empty())
            .unwrap();
        let resp = tower::ServiceExt::oneshot(app, resp).await.unwrap();
        assert_eq!(resp.status(), 200);
    }

    // Third request should be rate limited
    let app = build_router(state.clone());
    let resp = axum::http::Request::builder()
        .method("POST")
        .uri("/wake/brave-falcon")
        .body(axum::body::Body::empty())
        .unwrap();

    let resp = tower::ServiceExt::oneshot(app, resp).await.unwrap();
    assert_eq!(resp.status(), 429);

    let body = axum::body::to_bytes(resp.into_body(), 1024 * 1024)
        .await
        .unwrap();
    let json: serde_json::Value = serde_json::from_slice(&body).unwrap();
    assert_eq!(json["error"].as_str().unwrap(), "rate_limited");
    assert!(json["retry_after_secs"].is_number());
}

#[ignore = "wake endpoint disabled"]
#[tokio::test]
async fn wake_fcm_failure_returns_500() {
    let firestore =
        Arc::new(MockFirestore::new().with_device("brave-falcon", make_device_record()));
    let fcm = Arc::new(MockFcm::failing());
    let relay_state = Arc::new(RelayState::new());
    let rate_limiter = RateLimiter::new(RateLimitConfig::default());

    let app = make_app_with_state(firestore, fcm, relay_state, rate_limiter);

    let resp = axum::http::Request::builder()
        .method("POST")
        .uri("/wake/brave-falcon")
        .body(axum::body::Body::empty())
        .unwrap();

    let resp = tower::ServiceExt::oneshot(app, resp).await.unwrap();
    assert_eq!(resp.status(), 500);
}
