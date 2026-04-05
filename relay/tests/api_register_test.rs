//! Tests for POST /register endpoint.

mod test_helpers;

use base64::engine::general_purpose::STANDARD as BASE64;
use base64::Engine;
use rouse_relay::api::build_router;
use rouse_relay::firestore::DeviceRecord;
use std::sync::Arc;
use std::time::{Duration, SystemTime};
use test_helpers::*;

fn make_app(
    firestore: MockFirestore,
    acme: MockAcme,
    firebase_auth: MockFirebaseAuth,
) -> axum::Router {
    let state = build_test_state(
        Arc::new(firestore),
        Arc::new(MockFcm::new()),
        Arc::new(acme),
        Arc::new(firebase_auth),
    );
    build_router(state)
}

fn fake_csr() -> String {
    BASE64.encode(b"fake-csr-bytes")
}

#[tokio::test]
async fn register_new_device_returns_subdomain_and_cert() {
    let firestore = MockFirestore::new();
    let acme = MockAcme::new("test-cert-chain");
    let auth = MockFirebaseAuth::new().with_token("valid-token", "uid-123");

    let app = make_app(firestore, acme, auth);

    let resp = axum::http::Request::builder()
        .method("POST")
        .uri("/register")
        .header("content-type", "application/json")
        .body(axum::body::Body::from(
            serde_json::json!({
                "firebase_token": "valid-token",
                "csr": fake_csr(),
                "fcm_token": "device-fcm-token"
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

    assert!(json["subdomain"].is_string());
    assert!(!json["subdomain"].as_str().unwrap().is_empty());
    assert_eq!(json["cert"].as_str().unwrap(), "test-cert-chain");
    assert_eq!(
        json["relay_host"].as_str().unwrap(),
        "relay.rousecontext.com"
    );
}

#[tokio::test]
async fn register_invalid_firebase_token_returns_401() {
    let firestore = MockFirestore::new();
    let acme = MockAcme::new("test-cert");
    let auth = MockFirebaseAuth::new(); // no valid tokens

    let app = make_app(firestore, acme, auth);

    let resp = axum::http::Request::builder()
        .method("POST")
        .uri("/register")
        .header("content-type", "application/json")
        .body(axum::body::Body::from(
            serde_json::json!({
                "firebase_token": "bad-token",
                "csr": fake_csr(),
                "fcm_token": "device-fcm-token"
            })
            .to_string(),
        ))
        .unwrap();

    let resp = tower::ServiceExt::oneshot(app, resp).await.unwrap();
    assert_eq!(resp.status(), 401);
}

#[tokio::test]
async fn register_missing_csr_returns_400() {
    let firestore = MockFirestore::new();
    let acme = MockAcme::new("test-cert");
    let auth = MockFirebaseAuth::new().with_token("valid-token", "uid-123");

    let app = make_app(firestore, acme, auth);

    let resp = axum::http::Request::builder()
        .method("POST")
        .uri("/register")
        .header("content-type", "application/json")
        .body(axum::body::Body::from(
            serde_json::json!({
                "firebase_token": "valid-token",
                "csr": "",
                "fcm_token": "device-fcm-token"
            })
            .to_string(),
        ))
        .unwrap();

    let resp = tower::ServiceExt::oneshot(app, resp).await.unwrap();
    assert_eq!(resp.status(), 400);
}

#[tokio::test]
async fn re_register_without_signature_returns_403() {
    // Pre-populate a device for uid-123
    let existing_record = DeviceRecord {
        fcm_token: "old-token".to_string(),
        firebase_uid: "uid-123".to_string(),
        public_key: BASE64.encode(b"old-pubkey"),
        cert_expires: SystemTime::now() + Duration::from_secs(86400),
        registered_at: SystemTime::now(),
        last_rotation: None,
        renewal_nudge_sent: None,
    };
    let firestore = MockFirestore::new().with_device("old-sub", existing_record);
    let acme = MockAcme::new("test-cert");
    let auth = MockFirebaseAuth::new().with_token("valid-token", "uid-123");

    let app = make_app(firestore, acme, auth);

    let resp = axum::http::Request::builder()
        .method("POST")
        .uri("/register")
        .header("content-type", "application/json")
        .body(axum::body::Body::from(
            serde_json::json!({
                "firebase_token": "valid-token",
                "csr": fake_csr(),
                "fcm_token": "new-fcm-token"
            })
            .to_string(),
        ))
        .unwrap();

    let resp = tower::ServiceExt::oneshot(app, resp).await.unwrap();
    assert_eq!(resp.status(), 403);

    let body = axum::body::to_bytes(resp.into_body(), 1024 * 1024)
        .await
        .unwrap();
    let json: serde_json::Value = serde_json::from_slice(&body).unwrap();
    assert_eq!(json["error"].as_str().unwrap(), "forbidden");
}

#[tokio::test]
async fn force_new_within_cooldown_returns_429() {
    // Device with last_rotation just now (within 30-day cooldown)
    use p256::ecdsa::SigningKey;
    use p256::pkcs8::EncodePublicKey;

    let signing_key = SigningKey::random(&mut rand::thread_rng());
    let verifying_key = signing_key.verifying_key();
    let pub_key_der = verifying_key.to_public_key_der().unwrap();
    let pub_key_b64 = BASE64.encode(pub_key_der.as_bytes());

    let csr_bytes = b"fake-csr-bytes";
    let csr_b64 = BASE64.encode(csr_bytes);

    // Sign the CSR bytes
    use p256::ecdsa::{signature::Signer, Signature};
    let signature: Signature = signing_key.sign(csr_bytes);
    let sig_b64 = BASE64.encode(signature.to_der().as_bytes());

    let existing_record = DeviceRecord {
        fcm_token: "old-token".to_string(),
        firebase_uid: "uid-123".to_string(),
        public_key: pub_key_b64,
        cert_expires: SystemTime::now() + Duration::from_secs(86400),
        registered_at: SystemTime::now(),
        last_rotation: Some(SystemTime::now()), // just rotated
        renewal_nudge_sent: None,
    };
    let firestore = MockFirestore::new().with_device("old-sub", existing_record);
    let acme = MockAcme::new("test-cert");
    let auth = MockFirebaseAuth::new().with_token("valid-token", "uid-123");

    let app = make_app(firestore, acme, auth);

    let resp = axum::http::Request::builder()
        .method("POST")
        .uri("/register")
        .header("content-type", "application/json")
        .body(axum::body::Body::from(
            serde_json::json!({
                "firebase_token": "valid-token",
                "csr": csr_b64,
                "fcm_token": "new-fcm-token",
                "signature": sig_b64,
                "force_new": true
            })
            .to_string(),
        ))
        .unwrap();

    let resp = tower::ServiceExt::oneshot(app, resp).await.unwrap();
    assert_eq!(resp.status(), 429);

    let body = axum::body::to_bytes(resp.into_body(), 1024 * 1024)
        .await
        .unwrap();
    let json: serde_json::Value = serde_json::from_slice(&body).unwrap();
    assert_eq!(json["error"].as_str().unwrap(), "cooldown");
    assert!(json["retry_after_secs"].is_number());
}

#[tokio::test]
async fn re_register_with_valid_signature_reuses_subdomain() {
    use p256::ecdsa::SigningKey;
    use p256::pkcs8::EncodePublicKey;

    let signing_key = SigningKey::random(&mut rand::thread_rng());
    let verifying_key = signing_key.verifying_key();
    let pub_key_der = verifying_key.to_public_key_der().unwrap();
    let pub_key_b64 = BASE64.encode(pub_key_der.as_bytes());

    let csr_bytes = b"fake-csr-bytes";
    let csr_b64 = BASE64.encode(csr_bytes);

    use p256::ecdsa::{signature::Signer, Signature};
    let signature: Signature = signing_key.sign(csr_bytes);
    let sig_b64 = BASE64.encode(signature.to_der().as_bytes());

    let existing_record = DeviceRecord {
        fcm_token: "old-token".to_string(),
        firebase_uid: "uid-123".to_string(),
        public_key: pub_key_b64,
        cert_expires: SystemTime::now() + Duration::from_secs(86400),
        registered_at: SystemTime::now(),
        last_rotation: None,
        renewal_nudge_sent: None,
    };
    let firestore = MockFirestore::new().with_device("brave-falcon", existing_record);
    let acme = MockAcme::new("new-cert-chain");
    let auth = MockFirebaseAuth::new().with_token("valid-token", "uid-123");

    let app = make_app(firestore, acme, auth);

    let resp = axum::http::Request::builder()
        .method("POST")
        .uri("/register")
        .header("content-type", "application/json")
        .body(axum::body::Body::from(
            serde_json::json!({
                "firebase_token": "valid-token",
                "csr": csr_b64,
                "fcm_token": "new-fcm-token",
                "signature": sig_b64,
                "force_new": false
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
    // Re-registration should reuse the existing subdomain
    assert_eq!(json["subdomain"].as_str().unwrap(), "brave-falcon");
    assert_eq!(json["cert"].as_str().unwrap(), "new-cert-chain");
}

#[tokio::test]
async fn force_new_assigns_new_subdomain_when_cooldown_expired() {
    use p256::ecdsa::SigningKey;
    use p256::pkcs8::EncodePublicKey;

    let signing_key = SigningKey::random(&mut rand::thread_rng());
    let verifying_key = signing_key.verifying_key();
    let pub_key_der = verifying_key.to_public_key_der().unwrap();
    let pub_key_b64 = BASE64.encode(pub_key_der.as_bytes());

    let csr_bytes = b"fake-csr-bytes";
    let csr_b64 = BASE64.encode(csr_bytes);

    use p256::ecdsa::{signature::Signer, Signature};
    let signature: Signature = signing_key.sign(csr_bytes);
    let sig_b64 = BASE64.encode(signature.to_der().as_bytes());

    let existing_record = DeviceRecord {
        fcm_token: "old-token".to_string(),
        firebase_uid: "uid-123".to_string(),
        public_key: pub_key_b64,
        cert_expires: SystemTime::now() + Duration::from_secs(86400),
        registered_at: SystemTime::now() - Duration::from_secs(60 * 86400),
        last_rotation: Some(SystemTime::now() - Duration::from_secs(31 * 86400)), // 31 days ago
        renewal_nudge_sent: None,
    };
    let firestore = Arc::new(MockFirestore::new().with_device("old-sub", existing_record));
    let acme = MockAcme::new("rotated-cert");
    let auth = MockFirebaseAuth::new().with_token("valid-token", "uid-123");

    let state = build_test_state(
        firestore.clone(),
        Arc::new(MockFcm::new()),
        Arc::new(acme),
        Arc::new(auth),
    );
    let app = build_router(state);

    let resp = axum::http::Request::builder()
        .method("POST")
        .uri("/register")
        .header("content-type", "application/json")
        .body(axum::body::Body::from(
            serde_json::json!({
                "firebase_token": "valid-token",
                "csr": csr_b64,
                "fcm_token": "new-fcm-token",
                "signature": sig_b64,
                "force_new": true
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
    // Should be a new subdomain, not "old-sub"
    let new_sub = json["subdomain"].as_str().unwrap();
    assert_ne!(new_sub, "old-sub");
    assert!(
        new_sub.contains('-'),
        "subdomain should be adjective-noun format"
    );

    // Old subdomain should be deleted
    assert!(firestore.devices.lock().unwrap().get("old-sub").is_none());
    // New subdomain should exist
    assert!(firestore.devices.lock().unwrap().contains_key(new_sub));
}

#[tokio::test]
async fn acme_rate_limit_returns_429() {
    let firestore = MockFirestore::new();
    let acme = MockAcme::rate_limited();
    let auth = MockFirebaseAuth::new().with_token("valid-token", "uid-123");

    let app = make_app(firestore, acme, auth);

    let resp = axum::http::Request::builder()
        .method("POST")
        .uri("/register")
        .header("content-type", "application/json")
        .body(axum::body::Body::from(
            serde_json::json!({
                "firebase_token": "valid-token",
                "csr": fake_csr(),
                "fcm_token": "device-fcm-token"
            })
            .to_string(),
        ))
        .unwrap();

    let resp = tower::ServiceExt::oneshot(app, resp).await.unwrap();
    assert_eq!(resp.status(), 429);

    let body = axum::body::to_bytes(resp.into_body(), 1024 * 1024)
        .await
        .unwrap();
    let json: serde_json::Value = serde_json::from_slice(&body).unwrap();
    assert_eq!(json["error"].as_str().unwrap(), "acme_rate_limited");
}
