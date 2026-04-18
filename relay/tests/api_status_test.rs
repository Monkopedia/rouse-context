//! Tests for GET /status endpoint.

mod test_helpers;

use rouse_relay::api::build_router;
use rouse_relay::state::RelayState;
use std::sync::atomic::Ordering;
use std::sync::Arc;
use test_helpers::*;

fn make_app(relay_state: Arc<RelayState>) -> axum::Router {
    let state = Arc::new(rouse_relay::api::AppState {
        relay_state,
        session_registry: Arc::new(rouse_relay::passthrough::SessionRegistry::new()),
        firestore: Arc::new(MockFirestore::new()),
        fcm: Arc::new(MockFcm::new()),
        acme: Arc::new(MockAcme::new("cert")),
        dns: Arc::new(MockDns::new()),
        firebase_auth: Arc::new(MockFirebaseAuth::new()),
        subdomain_generator: rouse_relay::subdomain::SubdomainGenerator::new(),
        rate_limiter: rouse_relay::rate_limit::RateLimiter::new(
            rouse_relay::rate_limit::RateLimitConfig::default(),
        ),
        request_subdomain_rate_limiter: rouse_relay::rate_limit::RateLimiter::new(
            rouse_relay::rate_limit::RateLimitConfig::default(),
        ),
        config: rouse_relay::config::RelayConfig::default(),
        device_ca: None,
        #[cfg(feature = "test-mode")]
        test_metrics: None,
    });
    build_router(state)
}

#[tokio::test]
async fn status_returns_correct_counts() {
    let relay_state = Arc::new(RelayState::new());
    relay_state.register_mux_connection("device-a");
    relay_state.register_mux_connection("device-b");
    relay_state
        .total_sessions_served
        .store(42, Ordering::Relaxed);
    relay_state.pending_fcm_wakeups.store(1, Ordering::Relaxed);

    let app = make_app(relay_state);

    let resp = axum::http::Request::builder()
        .method("GET")
        .uri("/status")
        .body(axum::body::Body::empty())
        .unwrap();

    let resp = tower::ServiceExt::oneshot(app, resp).await.unwrap();
    assert_eq!(resp.status(), 200);

    let body = axum::body::to_bytes(resp.into_body(), 1024 * 1024)
        .await
        .unwrap();
    let json: serde_json::Value = serde_json::from_slice(&body).unwrap();

    assert!(json["uptime_secs"].as_u64().unwrap() < 5); // just started
    assert_eq!(json["active_mux_connections"].as_u64().unwrap(), 2);
    assert_eq!(json["active_streams"].as_u64().unwrap(), 0);
    assert_eq!(json["total_sessions_served"].as_u64().unwrap(), 42);
    assert_eq!(json["pending_fcm_wakeups"].as_u64().unwrap(), 1);
}

#[tokio::test]
async fn status_empty_state() {
    let relay_state = Arc::new(RelayState::new());
    let app = make_app(relay_state);

    let resp = axum::http::Request::builder()
        .method("GET")
        .uri("/status")
        .body(axum::body::Body::empty())
        .unwrap();

    let resp = tower::ServiceExt::oneshot(app, resp).await.unwrap();
    assert_eq!(resp.status(), 200);

    let body = axum::body::to_bytes(resp.into_body(), 1024 * 1024)
        .await
        .unwrap();
    let json: serde_json::Value = serde_json::from_slice(&body).unwrap();

    assert_eq!(json["active_mux_connections"].as_u64().unwrap(), 0);
    assert_eq!(json["active_streams"].as_u64().unwrap(), 0);
    assert_eq!(json["total_sessions_served"].as_u64().unwrap(), 0);
    assert_eq!(json["pending_fcm_wakeups"].as_u64().unwrap(), 0);
}

#[tokio::test]
async fn status_after_mux_disconnect() {
    let relay_state = Arc::new(RelayState::new());
    relay_state.register_mux_connection("device-a");
    relay_state.register_mux_connection("device-b");
    relay_state.remove_mux_connection("device-a");

    let app = make_app(relay_state);

    let resp = axum::http::Request::builder()
        .method("GET")
        .uri("/status")
        .body(axum::body::Body::empty())
        .unwrap();

    let resp = tower::ServiceExt::oneshot(app, resp).await.unwrap();
    assert_eq!(resp.status(), 200);

    let body = axum::body::to_bytes(resp.into_body(), 1024 * 1024)
        .await
        .unwrap();
    let json: serde_json::Value = serde_json::from_slice(&body).unwrap();
    assert_eq!(json["active_mux_connections"].as_u64().unwrap(), 1);
}
