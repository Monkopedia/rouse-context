//! Tests for POST /register (round 1) and POST /register/certs (round 2).

mod test_helpers;

use base64::engine::general_purpose::STANDARD as BASE64;
use base64::Engine;
use rouse_relay::api::build_router;
use rouse_relay::firestore::{DeviceRecord, SubdomainReservation};
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
    // No integrations were requested, so the secrets map is either absent
    // (serde skips empty maps) or present and empty.
    if let Some(secrets) = json.get("secrets") {
        assert!(
            secrets.as_object().map(|m| m.is_empty()).unwrap_or(false),
            "secrets should be empty when no integrations are requested"
        );
    }
    // Round 1 response should NOT contain cert or private_key
    assert!(json.get("cert").is_none());
    assert!(json.get("private_key").is_none());
}

#[tokio::test]
async fn register_with_integrations_returns_generated_secrets() {
    let firestore = Arc::new(MockFirestore::new());
    let acme = MockAcme::new("test-cert-chain");
    let auth = MockFirebaseAuth::new().with_token("valid-token", "uid-42");

    let state = build_test_state(
        firestore.clone(),
        Arc::new(MockFcm::new()),
        Arc::new(acme),
        Arc::new(auth),
    );
    let app = rouse_relay::api::build_router(state);

    let resp = axum::http::Request::builder()
        .method("POST")
        .uri("/register")
        .header("content-type", "application/json")
        .body(axum::body::Body::from(
            serde_json::json!({
                "firebase_token": "valid-token",
                "fcm_token": "device-fcm-token",
                "integrations": ["outreach", "health", "notifications"]
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

    let subdomain = json["subdomain"].as_str().unwrap().to_string();
    let secrets = json["secrets"].as_object().expect("secrets map missing");
    assert_eq!(secrets.len(), 3);

    for id in ["outreach", "health", "notifications"] {
        let value = secrets[id].as_str().unwrap();
        let suffix = format!("-{id}");
        assert!(
            value.ends_with(&suffix),
            "secret {value:?} should end with {suffix:?}"
        );
        let adjective = value.strip_suffix(&suffix).unwrap();
        assert!(
            !adjective.is_empty() && adjective.chars().all(|c| c.is_ascii_lowercase()),
            "adjective {adjective:?} is not lowercase ascii letters"
        );
    }

    // Firestore should have stored the generated values in both shapes:
    //   valid_secrets — flat list, used by the SNI fast path
    //   integration_secrets — mapping, used for merge-missing rotation
    let devices = firestore.devices.lock().unwrap();
    let stored = devices
        .get(&subdomain)
        .expect("device missing in firestore");
    assert_eq!(stored.valid_secrets.len(), 3);
    assert_eq!(stored.integration_secrets.len(), 3);
    for (id, expected) in secrets {
        let expected = expected.as_str().unwrap().to_string();
        assert!(
            stored.valid_secrets.contains(&expected),
            "Firestore valid_secrets {:?} missing {expected}",
            stored.valid_secrets
        );
        assert_eq!(
            stored.integration_secrets.get(id).map(|s| s.as_str()),
            Some(expected.as_str()),
            "Firestore integration_secrets {:?} should map {id} -> {expected}",
            stored.integration_secrets
        );
    }
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
        integration_secrets: std::collections::HashMap::new(),
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
        integration_secrets: std::collections::HashMap::new(),
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
        integration_secrets: std::collections::HashMap::new(),
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
        integration_secrets: std::collections::HashMap::new(),
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
async fn force_new_deletes_old_dns_records() {
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
        last_rotation: Some(SystemTime::now() - Duration::from_secs(31 * 86400)),
        renewal_nudge_sent: None,
        secret_prefix: None,
        valid_secrets: Vec::new(),
        integration_secrets: std::collections::HashMap::new(),
    };
    let firestore = Arc::new(MockFirestore::new().with_device("old-sub", existing_record));
    let dns = Arc::new(MockDns::new());
    let acme = MockAcme::new("rotated-cert");
    let auth = MockFirebaseAuth::new().with_token(firebase_token, "uid-123");

    let state = build_test_state_with_dns(
        firestore.clone(),
        Arc::new(MockFcm::new()),
        Arc::new(acme),
        Arc::new(auth),
        dns.clone(),
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

    // DNS records for old subdomain should have been deleted
    let deleted = dns.deleted_subdomains.lock().unwrap();
    assert_eq!(deleted.len(), 1);
    assert_eq!(deleted[0], "old-sub");
}

#[tokio::test]
async fn force_new_succeeds_even_if_dns_cleanup_fails() {
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
        last_rotation: Some(SystemTime::now() - Duration::from_secs(31 * 86400)),
        renewal_nudge_sent: None,
        secret_prefix: None,
        valid_secrets: Vec::new(),
        integration_secrets: std::collections::HashMap::new(),
    };
    let firestore = Arc::new(MockFirestore::new().with_device("old-sub", existing_record));
    // DNS client that always fails
    let dns = Arc::new(MockDns::failing());
    let acme = MockAcme::new("rotated-cert");
    let auth = MockFirebaseAuth::new().with_token(firebase_token, "uid-123");

    let state = build_test_state_with_dns(
        firestore.clone(),
        Arc::new(MockFcm::new()),
        Arc::new(acme),
        Arc::new(auth),
        dns,
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
    // Registration should still succeed even though DNS cleanup failed
    assert_eq!(resp.status(), 200);

    // Old Firestore record should still be deleted
    assert!(firestore.devices.lock().unwrap().get("old-sub").is_none());
}

#[tokio::test]
async fn re_register_same_subdomain_does_not_delete_dns() {
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
        integration_secrets: std::collections::HashMap::new(),
    };
    let firestore = Arc::new(MockFirestore::new().with_device("brave-falcon", existing_record));
    let dns = Arc::new(MockDns::new());
    let acme = MockAcme::new("cert");
    let auth = MockFirebaseAuth::new().with_token(firebase_token, "uid-123");

    let state = build_test_state_with_dns(
        firestore,
        Arc::new(MockFcm::new()),
        Arc::new(acme),
        Arc::new(auth),
        dns.clone(),
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
    assert_eq!(json["subdomain"].as_str().unwrap(), "brave-falcon");

    // DNS records should NOT be deleted for re-registration (same subdomain)
    let deleted = dns.deleted_subdomains.lock().unwrap();
    assert!(
        deleted.is_empty(),
        "Re-registration should not delete DNS records, but deleted: {:?}",
        *deleted
    );
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

// --- /register/certs proof-of-possession for existing device (issue #201) ---

/// Generate a fresh P-256 keypair + self-signed CSR DER for a given CN. Returns
/// the CSR DER and a `p256::ecdsa::SigningKey` cloned from the same PKCS#8
/// material used by rcgen, so tests can produce an ECDSA signature over the
/// CSR DER that verifies against the CSR's own SPKI.
fn generate_test_csr(common_name: &str) -> (Vec<u8>, p256::ecdsa::SigningKey) {
    use p256::ecdsa::SigningKey;
    use p256::pkcs8::DecodePrivateKey;
    use rcgen::{CertificateParams, DistinguishedName, DnType, KeyPair};

    // rcgen generates its own P-256 keypair; re-import its PKCS#8 export
    // into the p256 crate so we can sign arbitrary bytes against the same
    // key later (rcgen's KeyPair doesn't expose a raw sign method).
    let key_pair = KeyPair::generate_for(&rcgen::PKCS_ECDSA_P256_SHA256).unwrap();
    let pkcs8_pem = key_pair.serialize_pem();
    let signing_key = SigningKey::from_pkcs8_pem(&pkcs8_pem).unwrap();

    let mut params = CertificateParams::new(vec![common_name.to_string()]).unwrap();
    params.distinguished_name = DistinguishedName::new();
    params
        .distinguished_name
        .push(DnType::CommonName, common_name);
    let csr = params.serialize_request(&key_pair).unwrap();
    (csr.der().to_vec(), signing_key)
}

/// Store the registered public key (DER, base64) for a device.
fn pub_key_b64_from_signing_key(key: &p256::ecdsa::SigningKey) -> String {
    use p256::pkcs8::EncodePublicKey;
    let pub_der = key.verifying_key().to_public_key_der().unwrap();
    BASE64.encode(pub_der.as_bytes())
}

fn existing_record_with_pub_key(uid: &str, pub_key_b64: String) -> DeviceRecord {
    DeviceRecord {
        fcm_token: "old-fcm".to_string(),
        firebase_uid: uid.to_string(),
        public_key: pub_key_b64,
        cert_expires: SystemTime::now() + Duration::from_secs(86400),
        registered_at: SystemTime::now(),
        last_rotation: None,
        renewal_nudge_sent: None,
        secret_prefix: None,
        valid_secrets: Vec::new(),
        integration_secrets: std::collections::HashMap::new(),
    }
}

#[tokio::test]
async fn register_certs_fresh_registration_without_signature_succeeds() {
    // Regression: fresh device (record.public_key empty) registered in round 1
    // must still be able to call /register/certs without a signature.
    let existing = DeviceRecord {
        fcm_token: "fcm".to_string(),
        firebase_uid: "uid-fresh".to_string(),
        public_key: String::new(), // round 1 leaves this empty
        cert_expires: SystemTime::UNIX_EPOCH,
        registered_at: SystemTime::now(),
        last_rotation: None,
        renewal_nudge_sent: None,
        secret_prefix: None,
        valid_secrets: Vec::new(),
        integration_secrets: std::collections::HashMap::new(),
    };
    let firestore = Arc::new(MockFirestore::new().with_device("fresh-device", existing));
    let acme = MockAcme::new("test-cert");
    let auth = MockFirebaseAuth::new().with_token("valid-token", "uid-fresh");

    let state = build_test_state_with_ca(
        firestore,
        Arc::new(MockFcm::new()),
        Arc::new(acme),
        Arc::new(auth),
    );
    let app = build_router(state);

    let (csr_der, _signing_key) = generate_test_csr("fresh-device.rousecontext.com");

    let resp = axum::http::Request::builder()
        .method("POST")
        .uri("/register/certs")
        .header("content-type", "application/json")
        .body(axum::body::Body::from(
            serde_json::json!({
                "firebase_token": "valid-token",
                "csr": BASE64.encode(&csr_der)
            })
            .to_string(),
        ))
        .unwrap();
    let resp = tower::ServiceExt::oneshot(app, resp).await.unwrap();
    assert_eq!(resp.status(), 200);
}

#[tokio::test]
async fn register_certs_existing_device_with_valid_signature_succeeds() {
    use p256::ecdsa::{signature::Signer, Signature};

    // Device record with a prior public key on file. The caller signs the new
    // CSR DER with that registered key and gets a new cert.
    let (csr_der, _csr_key) = generate_test_csr("brave-falcon.rousecontext.com");

    let registered_key = p256::ecdsa::SigningKey::random(&mut rand::thread_rng());
    let pub_key_b64 = pub_key_b64_from_signing_key(&registered_key);
    let existing = existing_record_with_pub_key("uid-existing", pub_key_b64);
    let firestore = Arc::new(MockFirestore::new().with_device("brave-falcon", existing));

    let sig: Signature = registered_key.sign(&csr_der);
    let sig_b64 = BASE64.encode(sig.to_der().as_bytes());

    let acme = MockAcme::new("test-cert");
    let auth = MockFirebaseAuth::new().with_token("valid-token", "uid-existing");
    let state = build_test_state_with_ca(
        firestore,
        Arc::new(MockFcm::new()),
        Arc::new(acme),
        Arc::new(auth),
    );
    let app = build_router(state);

    let resp = axum::http::Request::builder()
        .method("POST")
        .uri("/register/certs")
        .header("content-type", "application/json")
        .body(axum::body::Body::from(
            serde_json::json!({
                "firebase_token": "valid-token",
                "csr": BASE64.encode(&csr_der),
                "signature": sig_b64,
            })
            .to_string(),
        ))
        .unwrap();
    let resp = tower::ServiceExt::oneshot(app, resp).await.unwrap();
    assert_eq!(resp.status(), 200);
}

#[tokio::test]
async fn register_certs_existing_device_without_signature_returns_403() {
    // Attack scenario: attacker has a valid Firebase token for a UID whose
    // device is already registered. Without a signature, the relay must
    // reject rather than mint a cert for the attacker's CSR key.
    let (csr_der, _csr_key) = generate_test_csr("brave-falcon.rousecontext.com");

    let registered_key = p256::ecdsa::SigningKey::random(&mut rand::thread_rng());
    let pub_key_b64 = pub_key_b64_from_signing_key(&registered_key);
    let existing = existing_record_with_pub_key("uid-existing", pub_key_b64);
    let firestore = Arc::new(MockFirestore::new().with_device("brave-falcon", existing));

    let acme = MockAcme::new("test-cert");
    let auth = MockFirebaseAuth::new().with_token("valid-token", "uid-existing");
    let state = build_test_state_with_ca(
        firestore,
        Arc::new(MockFcm::new()),
        Arc::new(acme),
        Arc::new(auth),
    );
    let app = build_router(state);

    let resp = axum::http::Request::builder()
        .method("POST")
        .uri("/register/certs")
        .header("content-type", "application/json")
        .body(axum::body::Body::from(
            serde_json::json!({
                "firebase_token": "valid-token",
                "csr": BASE64.encode(&csr_der)
            })
            .to_string(),
        ))
        .unwrap();
    let resp = tower::ServiceExt::oneshot(app, resp).await.unwrap();
    assert_eq!(resp.status(), 403);
}

#[tokio::test]
async fn register_certs_existing_device_with_wrong_signature_returns_403() {
    use p256::ecdsa::{signature::Signer, Signature};

    // The attacker signs with their own key, not the registered key. The
    // relay must reject.
    let (csr_der, _csr_key) = generate_test_csr("brave-falcon.rousecontext.com");

    let registered_key = p256::ecdsa::SigningKey::random(&mut rand::thread_rng());
    let pub_key_b64 = pub_key_b64_from_signing_key(&registered_key);
    let existing = existing_record_with_pub_key("uid-existing", pub_key_b64);
    let firestore = Arc::new(MockFirestore::new().with_device("brave-falcon", existing));

    // Attacker-held, unrelated key.
    let attacker_key = p256::ecdsa::SigningKey::random(&mut rand::thread_rng());
    let sig: Signature = attacker_key.sign(&csr_der);
    let sig_b64 = BASE64.encode(sig.to_der().as_bytes());

    let acme = MockAcme::new("test-cert");
    let auth = MockFirebaseAuth::new().with_token("valid-token", "uid-existing");
    let state = build_test_state_with_ca(
        firestore,
        Arc::new(MockFcm::new()),
        Arc::new(acme),
        Arc::new(auth),
    );
    let app = build_router(state);

    let resp = axum::http::Request::builder()
        .method("POST")
        .uri("/register/certs")
        .header("content-type", "application/json")
        .body(axum::body::Body::from(
            serde_json::json!({
                "firebase_token": "valid-token",
                "csr": BASE64.encode(&csr_der),
                "signature": sig_b64,
            })
            .to_string(),
        ))
        .unwrap();
    let resp = tower::ServiceExt::oneshot(app, resp).await.unwrap();
    assert_eq!(resp.status(), 403);
}

#[tokio::test]
async fn register_certs_rejects_csr_with_tampered_self_signature() {
    // Defense in depth: even on a fresh-registration path (no stored key yet),
    // the CSR's own self-signature must be valid. Tampering with the signature
    // byte triggers a 400, not a 500 or 200.
    let existing = DeviceRecord {
        fcm_token: "fcm".to_string(),
        firebase_uid: "uid-tamper".to_string(),
        public_key: String::new(),
        cert_expires: SystemTime::UNIX_EPOCH,
        registered_at: SystemTime::now(),
        last_rotation: None,
        renewal_nudge_sent: None,
        secret_prefix: None,
        valid_secrets: Vec::new(),
        integration_secrets: std::collections::HashMap::new(),
    };
    let firestore = Arc::new(MockFirestore::new().with_device("tamper-device", existing));

    let (mut csr_der, _csr_key) = generate_test_csr("tamper-device.rousecontext.com");
    // Flip a byte in the trailing signature portion.
    let last = csr_der.len() - 1;
    csr_der[last] ^= 0x01;

    let acme = MockAcme::new("test-cert");
    let auth = MockFirebaseAuth::new().with_token("valid-token", "uid-tamper");
    let state = build_test_state_with_ca(
        firestore,
        Arc::new(MockFcm::new()),
        Arc::new(acme),
        Arc::new(auth),
    );
    let app = build_router(state);

    let resp = axum::http::Request::builder()
        .method("POST")
        .uri("/register/certs")
        .header("content-type", "application/json")
        .body(axum::body::Body::from(
            serde_json::json!({
                "firebase_token": "valid-token",
                "csr": BASE64.encode(&csr_der)
            })
            .to_string(),
        ))
        .unwrap();
    let resp = tower::ServiceExt::oneshot(app, resp).await.unwrap();
    assert_eq!(resp.status(), 400);
}

// --- /register consumes active reservation created by /request-subdomain ---

#[tokio::test]
async fn register_consumes_active_reservation_for_uid() {
    // Seed a fresh reservation for uid-res-1. The reservation was created by
    // a prior /request-subdomain call and is still within the TTL window.
    // /register must use the reserved subdomain instead of generating a new
    // one, and must delete the reservation (single-use).
    let now = SystemTime::now();
    let reservation = SubdomainReservation {
        fqdn: "zephyr.rousecontext.com".to_string(),
        firebase_uid: "uid-res-1".to_string(),
        expires_at: now + Duration::from_secs(600),
        base_domain: "rousecontext.com".to_string(),
        created_at: now,
    };
    let firestore = Arc::new(MockFirestore::new().with_reservation("zephyr", reservation));
    let acme = MockAcme::new("test-cert");
    let auth = MockFirebaseAuth::new().with_token("valid-token", "uid-res-1");

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

    // Response must use the reserved subdomain, not a freshly generated one.
    assert_eq!(json["subdomain"].as_str().unwrap(), "zephyr");

    // Device record stored under the reserved subdomain.
    assert!(firestore.devices.lock().unwrap().contains_key("zephyr"));

    // Reservation consumed (deleted) — it is single-use.
    assert!(
        firestore
            .reservations
            .lock()
            .unwrap()
            .get("zephyr")
            .is_none(),
        "active reservation should be deleted after /register consumes it"
    );
}

#[tokio::test]
async fn register_generates_new_subdomain_when_no_reservation_exists() {
    // No reservation exists for uid-no-res. /register falls through to the
    // normal generator path and produces an adjective-noun subdomain.
    let firestore = Arc::new(MockFirestore::new());
    let acme = MockAcme::new("test-cert");
    let auth = MockFirebaseAuth::new().with_token("valid-token", "uid-no-res");

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

    // Falls through to generator path: adjective-noun format.
    let subdomain = json["subdomain"].as_str().unwrap();
    assert!(
        subdomain.contains('-'),
        "fallback subdomain should be adjective-noun, got {subdomain}"
    );
}

#[tokio::test]
async fn register_with_expired_reservation_generates_new_subdomain() {
    // Reservation exists but expired ten minutes ago. /register must not
    // adopt it — it should fall through to the generator path.
    let now = SystemTime::now();
    let reservation = SubdomainReservation {
        fqdn: "aurora.rousecontext.com".to_string(),
        firebase_uid: "uid-expired".to_string(),
        expires_at: now - Duration::from_secs(600),
        base_domain: "rousecontext.com".to_string(),
        created_at: now - Duration::from_secs(1200),
    };
    let firestore = Arc::new(MockFirestore::new().with_reservation("aurora", reservation));
    let acme = MockAcme::new("test-cert");
    let auth = MockFirebaseAuth::new().with_token("valid-token", "uid-expired");

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

    // Must NOT use the expired reservation.
    let subdomain = json["subdomain"].as_str().unwrap();
    assert_ne!(
        subdomain, "aurora",
        "expired reservation must not be adopted"
    );
    assert!(
        subdomain.contains('-'),
        "fallback subdomain should be adjective-noun, got {subdomain}"
    );

    // Device stored under the generated subdomain.
    assert!(firestore.devices.lock().unwrap().contains_key(subdomain));
}
