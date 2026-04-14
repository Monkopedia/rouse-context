//! Tests for POST /request-subdomain (server-assigned FQDN with reservation).
//!
//! See GitHub issue #92.

mod test_helpers;

use rouse_relay::api::build_router;
use std::sync::Arc;
use std::time::SystemTime;
use test_helpers::*;

fn make_app(firestore: MockFirestore, firebase_auth: MockFirebaseAuth) -> axum::Router {
    let state = build_test_state(
        Arc::new(firestore),
        Arc::new(MockFcm::new()),
        Arc::new(MockAcme::new("test-cert")),
        Arc::new(firebase_auth),
    );
    build_router(state)
}

async fn post_request(
    app: axum::Router,
    body: serde_json::Value,
) -> axum::http::Response<axum::body::Body> {
    let req = axum::http::Request::builder()
        .method("POST")
        .uri("/request-subdomain")
        .header("content-type", "application/json")
        .body(axum::body::Body::from(body.to_string()))
        .unwrap();
    tower::ServiceExt::oneshot(app, req).await.unwrap()
}

#[tokio::test]
async fn request_subdomain_returns_fqdn_and_reservation() {
    let firestore = MockFirestore::new();
    let auth = MockFirebaseAuth::new().with_token("valid-token", "uid-abc");
    let app = make_app(firestore, auth);

    let resp = post_request(app, serde_json::json!({ "firebase_token": "valid-token" })).await;
    assert_eq!(resp.status(), 200);

    let body = axum::body::to_bytes(resp.into_body(), 1024 * 1024)
        .await
        .unwrap();
    let json: serde_json::Value = serde_json::from_slice(&body).unwrap();

    let subdomain = json["subdomain"].as_str().expect("subdomain field missing");
    let base_domain = json["base_domain"].as_str().expect("base_domain missing");
    let fqdn = json["fqdn"].as_str().expect("fqdn missing");
    let ttl = json["reservation_ttl_seconds"]
        .as_u64()
        .expect("ttl missing");

    assert!(!subdomain.is_empty());
    assert_eq!(base_domain, "rousecontext.com");
    assert_eq!(fqdn, format!("{subdomain}.{base_domain}"));
    assert!((60..=3600).contains(&ttl));
}

#[tokio::test]
async fn request_subdomain_invalid_token_returns_401() {
    let firestore = MockFirestore::new();
    let auth = MockFirebaseAuth::new(); // no valid tokens
    let app = make_app(firestore, auth);

    let resp = post_request(app, serde_json::json!({ "firebase_token": "bad-token" })).await;
    assert_eq!(resp.status(), 401);
}

#[tokio::test]
async fn request_subdomain_missing_token_returns_400() {
    let firestore = MockFirestore::new();
    let auth = MockFirebaseAuth::new().with_token("valid-token", "uid-abc");
    let app = make_app(firestore, auth);

    let resp = post_request(app, serde_json::json!({ "firebase_token": "" })).await;
    assert_eq!(resp.status(), 400);
}

#[tokio::test]
async fn request_subdomain_persists_reservation_in_firestore() {
    let firestore = Arc::new(MockFirestore::new());
    let auth = MockFirebaseAuth::new().with_token("valid-token", "uid-abc");

    let state = build_test_state(
        firestore.clone(),
        Arc::new(MockFcm::new()),
        Arc::new(MockAcme::new("test-cert")),
        Arc::new(auth),
    );
    let app = build_router(state);

    let resp = post_request(app, serde_json::json!({ "firebase_token": "valid-token" })).await;
    assert_eq!(resp.status(), 200);

    let body = axum::body::to_bytes(resp.into_body(), 1024 * 1024)
        .await
        .unwrap();
    let json: serde_json::Value = serde_json::from_slice(&body).unwrap();
    let subdomain = json["subdomain"].as_str().unwrap().to_string();

    // Reservation should be stored and not expired
    let reservations = firestore.reservations.lock().unwrap();
    let entry = reservations
        .get(&subdomain)
        .expect("reservation not stored for subdomain");
    assert_eq!(entry.firebase_uid, "uid-abc");
    assert!(entry.expires_at > SystemTime::now());
}

#[tokio::test]
async fn request_subdomain_rate_limits_after_burst() {
    let firestore = MockFirestore::new();
    let auth = MockFirebaseAuth::new().with_token("valid-token", "uid-abc");
    let app = make_app(firestore, auth);

    // Default rate limit should allow a small burst and then reject.
    let mut success = 0;
    let mut rate_limited = 0;
    for _ in 0..10 {
        let req = axum::http::Request::builder()
            .method("POST")
            .uri("/request-subdomain")
            .header("content-type", "application/json")
            .body(axum::body::Body::from(
                serde_json::json!({ "firebase_token": "valid-token" }).to_string(),
            ))
            .unwrap();
        let resp = tower::ServiceExt::oneshot(app.clone(), req).await.unwrap();
        match resp.status().as_u16() {
            200 => success += 1,
            429 => rate_limited += 1,
            s => panic!("unexpected status {s}"),
        }
    }
    assert!(success >= 1, "at least one request should succeed");
    assert!(
        rate_limited >= 1,
        "rate limiter should kick in within 10 rapid requests, got {success} successes and {rate_limited} 429s"
    );
}

#[tokio::test]
async fn request_subdomain_retry_returns_same_name_while_reservation_valid() {
    let firestore = Arc::new(MockFirestore::new());
    let auth = MockFirebaseAuth::new().with_token("valid-token", "uid-abc");

    let state = build_test_state(
        firestore.clone(),
        Arc::new(MockFcm::new()),
        Arc::new(MockAcme::new("test-cert")),
        Arc::new(auth),
    );
    let app = build_router(state);

    let resp = post_request(
        app.clone(),
        serde_json::json!({ "firebase_token": "valid-token" }),
    )
    .await;
    assert_eq!(resp.status(), 200);
    let body = axum::body::to_bytes(resp.into_body(), 1024 * 1024)
        .await
        .unwrap();
    let json1: serde_json::Value = serde_json::from_slice(&body).unwrap();
    let sub1 = json1["subdomain"].as_str().unwrap().to_string();

    // A second request from the same UID should return the same existing reservation.
    let resp = post_request(app, serde_json::json!({ "firebase_token": "valid-token" })).await;
    assert_eq!(resp.status(), 200);
    let body = axum::body::to_bytes(resp.into_body(), 1024 * 1024)
        .await
        .unwrap();
    let json2: serde_json::Value = serde_json::from_slice(&body).unwrap();
    let sub2 = json2["subdomain"].as_str().unwrap().to_string();

    assert_eq!(
        sub1, sub2,
        "Retry within TTL should yield the same subdomain (idempotent)"
    );
}

#[tokio::test]
async fn request_subdomain_uses_single_word_pool_when_free() {
    // With an empty pool, subdomains from /request-subdomain should not
    // contain hyphens (single-word tier).
    let firestore = MockFirestore::new();
    let auth = MockFirebaseAuth::new().with_token("valid-token", "uid-abc");
    let app = make_app(firestore, auth);

    let resp = post_request(app, serde_json::json!({ "firebase_token": "valid-token" })).await;
    assert_eq!(resp.status(), 200);

    let body = axum::body::to_bytes(resp.into_body(), 1024 * 1024)
        .await
        .unwrap();
    let json: serde_json::Value = serde_json::from_slice(&body).unwrap();
    let subdomain = json["subdomain"].as_str().unwrap();

    assert!(
        !subdomain.contains('-'),
        "Empty pool: expected single-word subdomain, got: {subdomain}"
    );
}
