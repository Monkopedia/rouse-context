//! Tests for the public/authed router split introduced in #208.
//!
//! The authed router is wrapped in a `require_device_identity` middleware
//! that rejects requests without a `DeviceIdentity` extension (i.e. without a
//! valid mTLS client certificate) with 401 BEFORE the handler body runs.
//!
//! These tests pin that structural invariant:
//! - Unauthenticated `/rotate-secret` and `/ws` return 401.
//! - The middleware short-circuits before the handler so the handler never
//!   observes the unauthenticated request (we verify this with a hand-rolled
//!   router that threads a counter through the real middleware).
//! - Public routes (`/status`) are reachable without a client cert.
//! - Authenticated `/rotate-secret` still succeeds (regression for #202).

mod test_helpers;

use rouse_relay::api::build_router;
use rouse_relay::api::require_device_identity;
use rouse_relay::api::ws::DeviceIdentity;
use rouse_relay::firestore::DeviceRecord;
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::Arc;
use std::time::{Duration, SystemTime};
use test_helpers::*;

fn make_device() -> DeviceRecord {
    DeviceRecord {
        fcm_token: "fcm-tok".to_string(),
        firebase_uid: "uid-1".to_string(),
        public_key: "key".to_string(),
        cert_expires: SystemTime::now() + Duration::from_secs(86400),
        registered_at: SystemTime::now(),
        last_rotation: None,
        renewal_nudge_sent: None,
        secret_prefix: Some("old-secret".to_string()),
        valid_secrets: Vec::new(),
        integration_secrets: std::collections::HashMap::new(),
    }
}

fn build_state() -> std::sync::Arc<rouse_relay::api::AppState> {
    build_test_state_with_ca(
        Arc::new(MockFirestore::new().with_device("cool-penguin", make_device())),
        Arc::new(MockFcm::new()),
        Arc::new(MockAcme::new("test-cert")),
        Arc::new(MockFirebaseAuth::new()),
    )
}

/// `/status` does not require a client cert. Regression for the public-group
/// half of the split.
#[tokio::test]
async fn status_is_reachable_without_mtls() {
    let app = build_router(build_state());

    let req = axum::http::Request::builder()
        .uri("/status")
        .body(axum::body::Body::empty())
        .unwrap();
    let resp = tower::ServiceExt::oneshot(app, req).await.unwrap();
    assert_eq!(resp.status(), 200, "/status must be publicly reachable");
}

/// Unauthenticated `/rotate-secret` returns 401 via the middleware. This is
/// the #202 regression — with the per-handler check removed, the middleware
/// must enforce the same guarantee.
#[tokio::test]
async fn rotate_secret_without_mtls_returns_401_via_middleware() {
    let app = build_router(build_state());

    let req = axum::http::Request::builder()
        .method("POST")
        .uri("/rotate-secret")
        .header("content-type", "application/json")
        .body(axum::body::Body::from(
            serde_json::json!({
                "subdomain": "cool-penguin",
                "integrations": ["outreach"]
            })
            .to_string(),
        ))
        .unwrap();
    let resp = tower::ServiceExt::oneshot(app, req).await.unwrap();
    assert_eq!(
        resp.status(),
        401,
        "unauthenticated /rotate-secret must be rejected"
    );
}

/// Unauthenticated `/ws` returns 401 via the middleware.
#[tokio::test]
async fn ws_without_mtls_returns_401_via_middleware() {
    let app = build_router(build_state());

    let req = axum::http::Request::builder()
        .uri("/ws")
        .body(axum::body::Body::empty())
        .unwrap();
    let resp = tower::ServiceExt::oneshot(app, req).await.unwrap();
    assert_eq!(
        resp.status(),
        401,
        "unauthenticated /ws must be rejected before the upgrade handler"
    );
}

/// Authenticated `/rotate-secret` still works. Regression check: the
/// middleware must pass through when `DeviceIdentity` is present so the
/// handler can do its work.
#[tokio::test]
async fn rotate_secret_with_mtls_still_works() {
    let app = build_router(build_state()).layer(axum::Extension(DeviceIdentity {
        subdomain: "cool-penguin".to_string(),
    }));

    let req = axum::http::Request::builder()
        .method("POST")
        .uri("/rotate-secret")
        .header("content-type", "application/json")
        .body(axum::body::Body::from(
            serde_json::json!({
                "integrations": ["outreach"]
            })
            .to_string(),
        ))
        .unwrap();
    let resp = tower::ServiceExt::oneshot(app, req).await.unwrap();
    assert_eq!(
        resp.status(),
        200,
        "mTLS-authenticated /rotate-secret must succeed"
    );
}

/// The middleware short-circuits unauthenticated requests BEFORE the handler
/// runs. We verify this structurally: build a tiny router whose "handler"
/// increments a counter, sit the real `require_device_identity` middleware
/// in front of it, and assert the counter never moves when no identity is
/// attached.
///
/// This pins the invariant that matters — it is not enough for the handler
/// to return 401 after running; the handler must not execute at all, because
/// future handlers added to the authed group may have side effects even on
/// unauthenticated requests (exactly the #202 bug).
#[tokio::test]
async fn middleware_intercepts_before_handler() {
    let hit = Arc::new(AtomicUsize::new(0));
    let hit_for_handler = hit.clone();

    // A handler that records it was invoked. If the middleware works this
    // never runs on an unauthenticated request.
    let counting_handler = move || {
        let hit_for_handler = hit_for_handler.clone();
        async move {
            hit_for_handler.fetch_add(1, Ordering::SeqCst);
            axum::http::StatusCode::OK
        }
    };

    let app = axum::Router::new()
        .route("/guarded", axum::routing::get(counting_handler))
        .layer(axum::middleware::from_fn(require_device_identity));

    // 1. No identity -> 401 and handler not called.
    let req = axum::http::Request::builder()
        .uri("/guarded")
        .body(axum::body::Body::empty())
        .unwrap();
    let resp = tower::ServiceExt::oneshot(app.clone(), req).await.unwrap();
    assert_eq!(resp.status(), 401);
    assert_eq!(
        hit.load(Ordering::SeqCst),
        0,
        "middleware must reject before handler executes"
    );

    // 2. With identity -> handler runs.
    let authed = app.layer(axum::Extension(DeviceIdentity {
        subdomain: "s".to_string(),
    }));
    let req = axum::http::Request::builder()
        .uri("/guarded")
        .body(axum::body::Body::empty())
        .unwrap();
    let resp = tower::ServiceExt::oneshot(authed, req).await.unwrap();
    assert_eq!(resp.status(), 200);
    assert_eq!(
        hit.load(Ordering::SeqCst),
        1,
        "middleware must pass through when identity is present"
    );
}
