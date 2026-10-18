//! A device's bound public key persists across `/register` (same-subdomain
//! reuse and `force_new` rotation), and once bound every `/register/certs`
//! call must be signed by it. Also covers the `/ws` placeholder record being
//! create-only.

mod test_helpers;

use base64::engine::general_purpose::STANDARD as BASE64;
use base64::Engine;
use p256::ecdsa::{signature::Signer, Signature, SigningKey};
use rouse_relay::api::build_router;
use rouse_relay::firestore::{DeviceRecord, FirestoreClient, KeyBinding};
use std::sync::Arc;
use std::time::{Duration, SystemTime};
use test_helpers::*;

const TOKEN: &str = "valid-token";
const UID: &str = "uid-bound";

fn generate_test_csr(common_name: &str) -> Vec<u8> {
    use rcgen::{CertificateParams, DistinguishedName, DnType, KeyPair};
    let key_pair = KeyPair::generate_for(&rcgen::PKCS_ECDSA_P256_SHA256).unwrap();
    let mut params = CertificateParams::new(vec![common_name.to_string()]).unwrap();
    params.distinguished_name = DistinguishedName::new();
    params
        .distinguished_name
        .push(DnType::CommonName, common_name);
    params.serialize_request(&key_pair).unwrap().der().to_vec()
}

fn spki_b64(key: &SigningKey) -> String {
    use p256::pkcs8::EncodePublicKey;
    BASE64.encode(key.verifying_key().to_public_key_der().unwrap().as_bytes())
}

fn sign_b64(key: &SigningKey, data: &[u8]) -> String {
    let sig: Signature = key.sign(data);
    BASE64.encode(sig.to_der().as_bytes())
}

fn record(
    public_key: String,
    cert_expires: SystemTime,
    last_rotation: Option<SystemTime>,
) -> DeviceRecord {
    DeviceRecord {
        fcm_token: "old-fcm".to_string(),
        firebase_uid: UID.to_string(),
        key_thumbprint: None,
        public_key,
        key_binding: KeyBinding::Bound,
        cert_expires,
        registered_at: SystemTime::now(),
        last_seen: None,
        last_rotation,
        renewal_nudge_sent: None,
        secret_prefix: None,
        push_kind: Default::default(),
        push_endpoint: String::new(),
        valid_secrets: Vec::new(),
        integration_secrets: std::collections::HashMap::new(),
        retired_secrets: std::collections::HashMap::new(),
    }
}

fn app(firestore: Arc<MockFirestore>) -> axum::Router {
    let state = build_test_state_with_ca(
        firestore,
        Arc::new(MockFcm::new()),
        Arc::new(MockAcme::new("test-cert")),
        Arc::new(MockFirebaseAuth::new().with_token(TOKEN, UID)),
    );
    build_router(state)
}

async fn post(app: axum::Router, uri: &str, body: serde_json::Value) -> (u16, serde_json::Value) {
    let req = axum::http::Request::builder()
        .method("POST")
        .uri(uri)
        .header("content-type", "application/json")
        .body(axum::body::Body::from(body.to_string()))
        .unwrap();
    let resp = tower::ServiceExt::oneshot(app, req).await.unwrap();
    let status = resp.status().as_u16();
    let bytes = axum::body::to_bytes(resp.into_body(), 1024 * 1024)
        .await
        .unwrap();
    let json = serde_json::from_slice(&bytes).unwrap_or(serde_json::Value::Null);
    (status, json)
}

/// Re-register with a signature by the bound key (the only way the reuse path
/// is reachable on the Firebase flavor).
async fn re_register(app: axum::Router, key: &SigningKey, force_new: bool) -> String {
    let (status, json) = post(
        app,
        "/register",
        serde_json::json!({
            "firebase_token": TOKEN,
            "fcm_token": "new-fcm",
            "signature": sign_b64(key, TOKEN.as_bytes()),
            "force_new": force_new,
        }),
    )
    .await;
    assert_eq!(status, 200, "re-registration failed: {json}");
    json["subdomain"].as_str().unwrap().to_string()
}

async fn certs_without_signature(app: axum::Router) -> u16 {
    let csr = generate_test_csr("x.rousecontext.com");
    post(
        app,
        "/register/certs",
        serde_json::json!({ "firebase_token": TOKEN, "csr": BASE64.encode(&csr) }),
    )
    .await
    .0
}

#[tokio::test]
async fn bound_key_and_expiry_survive_re_registration() {
    let key = SigningKey::random(&mut rand::thread_rng());
    let expires = SystemTime::now() + Duration::from_secs(30 * 86400);
    let firestore = Arc::new(
        MockFirestore::new().with_device("bound-sub", record(spki_b64(&key), expires, None)),
    );

    let sub = re_register(app(firestore.clone()), &key, false).await;
    assert_eq!(sub, "bound-sub");

    let stored = firestore.get_device("bound-sub").await.unwrap();
    assert_eq!(stored.public_key, spki_b64(&key), "bound key must be kept");
    assert_eq!(stored.cert_expires, expires, "cert expiry must be kept");
    assert_eq!(stored.fcm_token, "new-fcm", "push target is still updated");
}

#[tokio::test]
async fn certs_after_re_registration_require_bound_key_signature() {
    let key = SigningKey::random(&mut rand::thread_rng());
    let firestore = Arc::new(MockFirestore::new().with_device(
        "bound-sub",
        record(
            spki_b64(&key),
            SystemTime::now() + Duration::from_secs(86400),
            None,
        ),
    ));

    re_register(app(firestore.clone()), &key, false).await;

    assert_eq!(certs_without_signature(app(firestore.clone())).await, 403);
    let stored = firestore.get_device("bound-sub").await.unwrap();
    assert_eq!(stored.public_key, spki_b64(&key));
}

#[tokio::test]
async fn force_new_carries_bound_key_to_new_subdomain() {
    let key = SigningKey::random(&mut rand::thread_rng());
    let expires = SystemTime::now() + Duration::from_secs(30 * 86400);
    let long_ago = SystemTime::now() - Duration::from_secs(365 * 86400);
    let firestore = Arc::new(
        MockFirestore::new()
            .with_device("old-sub", record(spki_b64(&key), expires, Some(long_ago))),
    );

    let new_sub = re_register(app(firestore.clone()), &key, true).await;
    assert_ne!(new_sub, "old-sub");

    let stored = firestore.get_device(&new_sub).await.unwrap();
    assert_eq!(
        stored.public_key,
        spki_b64(&key),
        "bound key must carry over"
    );
    assert_eq!(stored.cert_expires, expires);

    assert_eq!(certs_without_signature(app(firestore.clone())).await, 403);
}

#[tokio::test]
async fn certs_after_re_registration_with_bound_key_signature_succeed() {
    let key = SigningKey::random(&mut rand::thread_rng());
    let firestore = Arc::new(MockFirestore::new().with_device(
        "bound-sub",
        record(
            spki_b64(&key),
            SystemTime::now() + Duration::from_secs(86400),
            None,
        ),
    ));

    re_register(app(firestore.clone()), &key, false).await;

    let csr = generate_test_csr("bound-sub.rousecontext.com");
    let (status, _) = post(
        app(firestore.clone()),
        "/register/certs",
        serde_json::json!({
            "firebase_token": TOKEN,
            "csr": BASE64.encode(&csr),
            "signature": sign_b64(&key, &csr),
        }),
    )
    .await;
    assert_eq!(status, 200);
}

#[tokio::test]
async fn fresh_device_first_certs_without_signature_succeeds() {
    let firestore = Arc::new(MockFirestore::new());
    let (status, json) = post(
        app(firestore.clone()),
        "/register",
        serde_json::json!({ "firebase_token": TOKEN, "fcm_token": "fcm" }),
    )
    .await;
    assert_eq!(status, 200, "{json}");

    assert_eq!(certs_without_signature(app(firestore.clone())).await, 200);

    // ...and from then on the key is bound.
    assert_eq!(certs_without_signature(app(firestore.clone())).await, 403);
}

#[tokio::test]
async fn ws_placeholder_does_not_overwrite_existing_record() {
    let key = SigningKey::random(&mut rand::thread_rng());
    let existing = record(
        spki_b64(&key),
        SystemTime::now() + Duration::from_secs(86400),
        None,
    );
    let firestore = MockFirestore::new().with_device("raced-sub", existing);

    rouse_relay::api::ws::create_placeholder_record(&firestore, "raced-sub").await;

    let stored = firestore.get_device("raced-sub").await.unwrap();
    assert_eq!(stored.firebase_uid, UID);
    assert_eq!(stored.fcm_token, "old-fcm");
    assert_eq!(stored.public_key, spki_b64(&key));
}

#[tokio::test]
async fn bound_record_without_stored_key_still_requires_signature() {
    let mut rec = record(
        String::new(),
        SystemTime::now() + Duration::from_secs(86400),
        None,
    );
    rec.key_binding = KeyBinding::Bound;
    let firestore = Arc::new(MockFirestore::new().with_device("bound-sub", rec));

    assert_eq!(certs_without_signature(app(firestore)).await, 403);
}

#[tokio::test]
async fn record_with_stored_key_requires_signature_even_if_marked_awaiting() {
    let key = SigningKey::random(&mut rand::thread_rng());
    let mut rec = record(
        spki_b64(&key),
        SystemTime::now() + Duration::from_secs(86400),
        None,
    );
    rec.key_binding = KeyBinding::AwaitingFirstKey;
    let firestore = Arc::new(MockFirestore::new().with_device("bound-sub", rec));

    assert_eq!(certs_without_signature(app(firestore)).await, 403);
}

#[tokio::test]
async fn ws_placeholder_is_created_bound() {
    let firestore = MockFirestore::new();

    rouse_relay::api::ws::create_placeholder_record(&firestore, "new-sub").await;

    let stored = firestore.get_device("new-sub").await.unwrap();
    assert_eq!(stored.key_binding, KeyBinding::Bound);
    assert!(!stored.awaits_first_key());
}
