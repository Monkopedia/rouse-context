//! Tests for POST /register (round 1) and POST /register/certs (round 2).

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

#[tokio::test]
async fn register_new_device_returns_subdomain() {
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
    assert_eq!(
        json["relay_host"].as_str().unwrap(),
        "relay.rousecontext.com"
    );
    // Round 1 response should NOT contain secrets (client generates them)
    assert!(
        json.get("secret_prefix").is_none(),
        "Registration response should not include secret_prefix"
    );
    assert!(
        json.get("integration_secrets").is_none(),
        "Registration response should not include integration_secrets"
    );
    // Round 1 response should NOT contain cert or private_key
    assert!(json.get("cert").is_none());
    assert!(json.get("private_key").is_none());
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
                "fcm_token": "device-fcm-token"
            })
            .to_string(),
        ))
        .unwrap();

    let resp = tower::ServiceExt::oneshot(app, resp).await.unwrap();
    assert_eq!(resp.status(), 401);
}

#[tokio::test]
async fn register_missing_fcm_token_returns_400() {
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
                "fcm_token": ""
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
        secret_prefix: None,
        valid_secrets: Vec::new(),
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
    use p256::ecdsa::SigningKey;
    use p256::pkcs8::EncodePublicKey;

    let signing_key = SigningKey::random(&mut rand::thread_rng());
    let verifying_key = signing_key.verifying_key();
    let pub_key_der = verifying_key.to_public_key_der().unwrap();
    let pub_key_b64 = BASE64.encode(pub_key_der.as_bytes());

    let firebase_token = "valid-token";

    // Sign the firebase_token bytes (re-registration signs the token, not a CSR)
    use p256::ecdsa::{signature::Signer, Signature};
    let signature: Signature = signing_key.sign(firebase_token.as_bytes());
    let sig_b64 = BASE64.encode(signature.to_der().as_bytes());

    let existing_record = DeviceRecord {
        fcm_token: "old-token".to_string(),
        firebase_uid: "uid-123".to_string(),
        public_key: pub_key_b64,
        cert_expires: SystemTime::now() + Duration::from_secs(86400),
        registered_at: SystemTime::now(),
        last_rotation: Some(SystemTime::now()), // just rotated
        renewal_nudge_sent: None,
        secret_prefix: None,
        valid_secrets: Vec::new(),
    };
    let firestore = MockFirestore::new().with_device("old-sub", existing_record);
    let acme = MockAcme::new("test-cert");
    let auth = MockFirebaseAuth::new().with_token(firebase_token, "uid-123");

    let app = make_app(firestore, acme, auth);

    let resp = axum::http::Request::builder()
        .method("POST")
        .uri("/register")
        .header("content-type", "application/json")
        .body(axum::body::Body::from(
            serde_json::json!({
                "firebase_token": firebase_token,
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

    let firebase_token = "valid-token";

    use p256::ecdsa::{signature::Signer, Signature};
    let signature: Signature = signing_key.sign(firebase_token.as_bytes());
    let sig_b64 = BASE64.encode(signature.to_der().as_bytes());

    let existing_record = DeviceRecord {
        fcm_token: "old-token".to_string(),
        firebase_uid: "uid-123".to_string(),
        public_key: pub_key_b64,
        cert_expires: SystemTime::now() + Duration::from_secs(86400),
        registered_at: SystemTime::now(),
        last_rotation: None,
        renewal_nudge_sent: None,
        secret_prefix: None,
        valid_secrets: Vec::new(),
    };
    let firestore = MockFirestore::new().with_device("brave-falcon", existing_record);
    let acme = MockAcme::new("new-cert-chain");
    let auth = MockFirebaseAuth::new().with_token(firebase_token, "uid-123");

    let app = make_app(firestore, acme, auth);

    let resp = axum::http::Request::builder()
        .method("POST")
        .uri("/register")
        .header("content-type", "application/json")
        .body(axum::body::Body::from(
            serde_json::json!({
                "firebase_token": firebase_token,
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
}

#[tokio::test]
async fn force_new_assigns_new_subdomain_when_cooldown_expired() {
    use p256::ecdsa::SigningKey;
    use p256::pkcs8::EncodePublicKey;

    let signing_key = SigningKey::random(&mut rand::thread_rng());
    let verifying_key = signing_key.verifying_key();
    let pub_key_der = verifying_key.to_public_key_der().unwrap();
    let pub_key_b64 = BASE64.encode(pub_key_der.as_bytes());

    let firebase_token = "valid-token";

    use p256::ecdsa::{signature::Signer, Signature};
    let signature: Signature = signing_key.sign(firebase_token.as_bytes());
    let sig_b64 = BASE64.encode(signature.to_der().as_bytes());

    let existing_record = DeviceRecord {
        fcm_token: "old-token".to_string(),
        firebase_uid: "uid-123".to_string(),
        public_key: pub_key_b64,
        cert_expires: SystemTime::now() + Duration::from_secs(86400),
        registered_at: SystemTime::now() - Duration::from_secs(60 * 86400),
        last_rotation: Some(SystemTime::now() - Duration::from_secs(31 * 86400)), // 31 days ago
        renewal_nudge_sent: None,
        secret_prefix: None,
        valid_secrets: Vec::new(),
    };
    let firestore = Arc::new(MockFirestore::new().with_device("old-sub", existing_record));
    let acme = MockAcme::new("rotated-cert");
    let auth = MockFirebaseAuth::new().with_token(firebase_token, "uid-123");

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
                "firebase_token": firebase_token,
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
async fn register_certs_without_prior_registration_returns_404() {
    let firestore = MockFirestore::new();
    let acme = MockAcme::new("test-cert");
    let auth = MockFirebaseAuth::new().with_token("valid-token", "uid-123");

    let app = make_app(firestore, acme, auth);

    let resp = axum::http::Request::builder()
        .method("POST")
        .uri("/register/certs")
        .header("content-type", "application/json")
        .body(axum::body::Body::from(
            serde_json::json!({
                "firebase_token": "valid-token",
                "csr": BASE64.encode(b"fake-csr")
            })
            .to_string(),
        ))
        .unwrap();

    let resp = tower::ServiceExt::oneshot(app, resp).await.unwrap();
    assert_eq!(resp.status(), 404);
}
