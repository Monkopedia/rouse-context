//! Tests for POST /renew endpoint.

mod test_helpers;

use base64::engine::general_purpose::STANDARD as BASE64;
use base64::Engine;
use p256::ecdsa::SigningKey;
use p256::ecdsa::{signature::Signer, Signature};
use p256::pkcs8::EncodePublicKey;
use rouse_relay::api::build_router;
use rouse_relay::firestore::DeviceRecord;
use std::sync::Arc;
use std::time::{Duration, SystemTime};
use test_helpers::*;

fn make_app(
    firestore: Arc<MockFirestore>,
    acme: MockAcme,
    firebase_auth: MockFirebaseAuth,
) -> axum::Router {
    let state = build_test_state(
        firestore,
        Arc::new(MockFcm::new()),
        Arc::new(acme),
        Arc::new(firebase_auth),
    );
    build_router(state)
}

/// Create a test keypair and device record.
fn setup_device() -> (SigningKey, DeviceRecord) {
    let signing_key = SigningKey::random(&mut rand::thread_rng());
    let verifying_key = signing_key.verifying_key();
    let pub_key_der = verifying_key.to_public_key_der().unwrap();
    let pub_key_b64 = BASE64.encode(pub_key_der.as_bytes());

    let record = DeviceRecord {
        fcm_token: "device-fcm".to_string(),
        firebase_uid: "uid-456".to_string(),
        public_key: pub_key_b64,
        cert_expires: SystemTime::now() + Duration::from_secs(86400),
        registered_at: SystemTime::now(),
        last_rotation: None,
        renewal_nudge_sent: None,
    };
    (signing_key, record)
}

#[tokio::test]
async fn renew_firebase_path_valid_signature_returns_cert() {
    let (signing_key, record) = setup_device();
    let firestore = Arc::new(MockFirestore::new().with_device("brave-falcon", record));
    let acme = MockAcme::new("renewed-cert");
    let auth = MockFirebaseAuth::new().with_token("valid-token", "uid-456");

    let app = make_app(firestore, acme, auth);

    let csr_bytes = b"renew-csr-bytes";
    let csr_b64 = BASE64.encode(csr_bytes);
    let signature: Signature = signing_key.sign(csr_bytes);
    let sig_b64 = BASE64.encode(signature.to_der().as_bytes());

    let resp = axum::http::Request::builder()
        .method("POST")
        .uri("/renew")
        .header("content-type", "application/json")
        .body(axum::body::Body::from(
            serde_json::json!({
                "subdomain": "brave-falcon",
                "firebase_token": "valid-token",
                "csr": csr_b64,
                "signature": sig_b64
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
    assert_eq!(json["cert"].as_str().unwrap(), "renewed-cert");
}

#[tokio::test]
async fn renew_invalid_signature_returns_403() {
    let (_signing_key, record) = setup_device();
    let firestore = Arc::new(MockFirestore::new().with_device("brave-falcon", record));
    let acme = MockAcme::new("cert");
    let auth = MockFirebaseAuth::new().with_token("valid-token", "uid-456");

    let app = make_app(firestore, acme, auth);

    let csr_bytes = b"renew-csr-bytes";
    let csr_b64 = BASE64.encode(csr_bytes);
    let bad_sig = BASE64.encode(b"not-a-real-signature");

    let resp = axum::http::Request::builder()
        .method("POST")
        .uri("/renew")
        .header("content-type", "application/json")
        .body(axum::body::Body::from(
            serde_json::json!({
                "subdomain": "brave-falcon",
                "firebase_token": "valid-token",
                "csr": csr_b64,
                "signature": bad_sig
            })
            .to_string(),
        ))
        .unwrap();

    let resp = tower::ServiceExt::oneshot(app, resp).await.unwrap();
    assert_eq!(resp.status(), 403);
}

#[tokio::test]
async fn renew_wrong_uid_returns_403() {
    let (signing_key, record) = setup_device();
    let firestore = Arc::new(MockFirestore::new().with_device("brave-falcon", record));
    let acme = MockAcme::new("cert");
    // Token maps to a different UID than the device record
    let auth = MockFirebaseAuth::new().with_token("valid-token", "wrong-uid");

    let app = make_app(firestore, acme, auth);

    let csr_bytes = b"renew-csr-bytes";
    let csr_b64 = BASE64.encode(csr_bytes);
    let signature: Signature = signing_key.sign(csr_bytes);
    let sig_b64 = BASE64.encode(signature.to_der().as_bytes());

    let resp = axum::http::Request::builder()
        .method("POST")
        .uri("/renew")
        .header("content-type", "application/json")
        .body(axum::body::Body::from(
            serde_json::json!({
                "subdomain": "brave-falcon",
                "firebase_token": "valid-token",
                "csr": csr_b64,
                "signature": sig_b64
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
async fn renew_device_not_found_returns_404() {
    let firestore = Arc::new(MockFirestore::new()); // empty
    let acme = MockAcme::new("cert");
    let auth = MockFirebaseAuth::new().with_token("valid-token", "uid-456");

    let app = make_app(firestore, acme, auth);

    let csr_bytes = b"renew-csr-bytes";
    let csr_b64 = BASE64.encode(csr_bytes);

    let resp = axum::http::Request::builder()
        .method("POST")
        .uri("/renew")
        .header("content-type", "application/json")
        .body(axum::body::Body::from(
            serde_json::json!({
                "subdomain": "nonexistent",
                "firebase_token": "valid-token",
                "csr": csr_b64,
                "signature": BASE64.encode(b"dummy")
            })
            .to_string(),
        ))
        .unwrap();

    let resp = tower::ServiceExt::oneshot(app, resp).await.unwrap();
    assert_eq!(resp.status(), 404);
}

#[tokio::test]
async fn renew_missing_csr_returns_400() {
    let firestore = Arc::new(MockFirestore::new());
    let acme = MockAcme::new("cert");
    let auth = MockFirebaseAuth::new().with_token("valid-token", "uid-456");

    let app = make_app(firestore, acme, auth);

    let resp = axum::http::Request::builder()
        .method("POST")
        .uri("/renew")
        .header("content-type", "application/json")
        .body(axum::body::Body::from(
            serde_json::json!({
                "subdomain": "brave-falcon",
                "firebase_token": "valid-token",
                "csr": "",
                "signature": "dummy"
            })
            .to_string(),
        ))
        .unwrap();

    let resp = tower::ServiceExt::oneshot(app, resp).await.unwrap();
    assert_eq!(resp.status(), 400);
}

#[tokio::test]
async fn renew_no_auth_returns_401() {
    let firestore = Arc::new(MockFirestore::new());
    let acme = MockAcme::new("cert");
    let auth = MockFirebaseAuth::new();

    let app = make_app(firestore, acme, auth);

    let resp = axum::http::Request::builder()
        .method("POST")
        .uri("/renew")
        .header("content-type", "application/json")
        .body(axum::body::Body::from(
            serde_json::json!({
                "csr": BASE64.encode(b"some-csr")
            })
            .to_string(),
        ))
        .unwrap();

    let resp = tower::ServiceExt::oneshot(app, resp).await.unwrap();
    assert_eq!(resp.status(), 401);
}

#[tokio::test]
async fn renew_clears_renewal_nudge_sent() {
    let (signing_key, mut record) = setup_device();
    record.renewal_nudge_sent = Some(SystemTime::now());
    let firestore = Arc::new(MockFirestore::new().with_device("brave-falcon", record));
    let acme = MockAcme::new("renewed-cert");
    let auth = MockFirebaseAuth::new().with_token("valid-token", "uid-456");

    let app = make_app(firestore.clone(), acme, auth);

    let csr_bytes = b"renew-csr-bytes";
    let csr_b64 = BASE64.encode(csr_bytes);
    let signature: Signature = signing_key.sign(csr_bytes);
    let sig_b64 = BASE64.encode(signature.to_der().as_bytes());

    let resp = axum::http::Request::builder()
        .method("POST")
        .uri("/renew")
        .header("content-type", "application/json")
        .body(axum::body::Body::from(
            serde_json::json!({
                "subdomain": "brave-falcon",
                "firebase_token": "valid-token",
                "csr": csr_b64,
                "signature": sig_b64
            })
            .to_string(),
        ))
        .unwrap();

    let resp = tower::ServiceExt::oneshot(app, resp).await.unwrap();
    assert_eq!(resp.status(), 200);

    // Check that renewal_nudge_sent was cleared
    let devices = firestore.devices.lock().unwrap();
    let updated = devices.get("brave-falcon").unwrap();
    assert!(updated.renewal_nudge_sent.is_none());
}
