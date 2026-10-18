//! POST /register only ever assigns a subdomain that no other device holds,
//! never replaces another identity's device record, and is rate-limited per
//! client IP.

mod test_helpers;

use async_trait::async_trait;
use base64::engine::general_purpose::STANDARD as BASE64;
use base64::Engine;
use rouse_relay::api::{build_router, AppState, ClientIp};
use rouse_relay::firestore::{
    DeviceRecord, FirestoreClient, FirestoreError, PendingCert, PushKind, SubdomainReservation,
};
use rouse_relay::subdomain::SubdomainGenerator;
use std::collections::HashMap;
use std::net::IpAddr;
use std::sync::Arc;
use std::time::{Duration, SystemTime};
use test_helpers::*;

// ── helpers ────────────────────────────────────────────────────────────

/// A CSR plus the p256 handle for the same key.
fn generate_test_csr(common_name: &str) -> (Vec<u8>, p256::ecdsa::SigningKey) {
    use p256::pkcs8::DecodePrivateKey;
    use rcgen::{CertificateParams, DistinguishedName, DnType, KeyPair};

    let key_pair = KeyPair::generate_for(&rcgen::PKCS_ECDSA_P256_SHA256).unwrap();
    let signing_key = p256::ecdsa::SigningKey::from_pkcs8_pem(&key_pair.serialize_pem()).unwrap();
    let mut params = CertificateParams::new(vec![common_name.to_string()]).unwrap();
    params.distinguished_name = DistinguishedName::new();
    params
        .distinguished_name
        .push(DnType::CommonName, common_name);
    let csr = params.serialize_request(&key_pair).unwrap();
    (csr.der().to_vec(), signing_key)
}

fn new_key() -> p256::ecdsa::SigningKey {
    p256::ecdsa::SigningKey::random(&mut rand::thread_rng())
}

fn spki_der(key: &p256::ecdsa::SigningKey) -> Vec<u8> {
    use p256::pkcs8::EncodePublicKey;
    key.verifying_key()
        .to_public_key_der()
        .unwrap()
        .as_bytes()
        .to_vec()
}

fn thumbprint(key: &p256::ecdsa::SigningKey) -> String {
    rouse_relay::keypair_auth::thumbprint(&spki_der(key))
}

fn unix_now() -> i64 {
    SystemTime::now()
        .duration_since(SystemTime::UNIX_EPOCH)
        .unwrap()
        .as_secs() as i64
}

/// JSON body for a keypair-path `/register` with a fresh signed proof.
fn keypair_register_body(key: &p256::ecdsa::SigningKey, nonce: &str) -> String {
    use p256::ecdsa::{signature::Signer, Signature};
    let ts = unix_now();
    let msg = rouse_relay::keypair_auth::canonical_message(
        rouse_relay::keypair_auth::PURPOSE_REGISTER,
        ts,
        nonce,
    );
    let sig: Signature = key.sign(&msg);
    serde_json::json!({
        "fcm_token": "device-fcm",
        "public_key": BASE64.encode(spki_der(key)),
        "auth_timestamp": ts,
        "auth_nonce": nonce,
        "auth_signature": BASE64.encode(sig.to_der().as_bytes()),
    })
    .to_string()
}

fn post(uri: &str, body: String) -> axum::http::Request<axum::body::Body> {
    axum::http::Request::builder()
        .method("POST")
        .uri(uri)
        .header("content-type", "application/json")
        .body(axum::body::Body::from(body))
        .unwrap()
}

async fn send(
    router: axum::Router,
    req: axum::http::Request<axum::body::Body>,
) -> (u16, serde_json::Value) {
    let resp = tower::ServiceExt::oneshot(router, req).await.unwrap();
    let status = resp.status().as_u16();
    let body = axum::body::to_bytes(resp.into_body(), 1024 * 1024)
        .await
        .unwrap();
    let json = serde_json::from_slice(&body).unwrap_or(serde_json::Value::Null);
    (status, json)
}

/// A device that completed both registration rounds: key bound, cert issued.
fn established_device(key: &p256::ecdsa::SigningKey) -> DeviceRecord {
    DeviceRecord {
        fcm_token: "established-fcm".to_string(),
        firebase_uid: String::new(),
        key_thumbprint: Some(thumbprint(key)),
        public_key: BASE64.encode(spki_der(key)),
        key_binding: rouse_relay::firestore::KeyBinding::Bound,
        cert_expires: SystemTime::now() + Duration::from_secs(60 * 86400),
        registered_at: SystemTime::now() - Duration::from_secs(86400),
        last_seen: None,
        last_rotation: None,
        renewal_nudge_sent: None,
        secret_prefix: None,
        push_kind: PushKind::Fcm,
        push_endpoint: String::new(),
        valid_secrets: vec!["brave-health".to_string()],
        integration_secrets: HashMap::from([("health".to_string(), "brave-health".to_string())]),
        retired_secrets: HashMap::new(),
    }
}

/// Stable comparison form of a record (DeviceRecord has no PartialEq).
fn snapshot(record: &DeviceRecord) -> serde_json::Value {
    serde_json::to_value(record).unwrap()
}

fn state_with(firestore: Arc<dyn FirestoreClient>, generator: SubdomainGenerator) -> Arc<AppState> {
    build_test_state_with_ca_and_generator(
        firestore,
        Arc::new(MockFcm::new()),
        Arc::new(MockAcme::new("cert")),
        Arc::new(MockFirebaseAuth::new().with_token("tok-new", "uid-new")),
        generator,
    )
}

// ── a new registration never lands on a held subdomain ─────────────────

#[tokio::test]
async fn keypair_registration_gets_a_different_subdomain_than_an_existing_device() {
    let established_key = new_key();
    let firestore = Arc::new(
        MockFirestore::new().with_device("taken-name", established_device(&established_key)),
    );
    let before = snapshot(&firestore.devices.lock().unwrap()["taken-name"]);
    let state = state_with(
        firestore.clone(),
        SubdomainGenerator::scripted(["taken-name", "fresh-name"]),
    );

    let (status, json) = send(
        build_router(state),
        post("/register", keypair_register_body(&new_key(), "n-1")),
    )
    .await;

    assert_eq!(status, 200, "registration should succeed: {json}");
    assert_eq!(json["subdomain"], "fresh-name");
    let devices = firestore.devices.lock().unwrap();
    assert_eq!(
        snapshot(&devices["taken-name"]),
        before,
        "existing device record must be unchanged"
    );
    assert_eq!(devices.len(), 2);
}

#[tokio::test]
async fn firebase_registration_gets_a_different_subdomain_than_an_existing_device() {
    let established_key = new_key();
    let firestore = Arc::new(
        MockFirestore::new().with_device("taken-name", established_device(&established_key)),
    );
    let before = snapshot(&firestore.devices.lock().unwrap()["taken-name"]);
    let state = state_with(
        firestore.clone(),
        SubdomainGenerator::scripted(["taken-name", "fresh-name"]),
    );

    let (status, json) = send(
        build_router(state),
        post(
            "/register",
            serde_json::json!({"firebase_token": "tok-new", "fcm_token": "f"}).to_string(),
        ),
    )
    .await;

    assert_eq!(status, 200, "registration should succeed: {json}");
    assert_eq!(json["subdomain"], "fresh-name");
    let devices = firestore.devices.lock().unwrap();
    assert_eq!(snapshot(&devices["taken-name"]), before);
    assert_eq!(devices["fresh-name"].firebase_uid, "uid-new");
}

#[tokio::test]
async fn registration_skips_a_subdomain_reserved_by_another_account() {
    let firestore = Arc::new(MockFirestore::new().with_reservation(
        "reserved-name",
        SubdomainReservation {
            fqdn: "reserved-name.rousecontext.com".to_string(),
            firebase_uid: "someone-else".to_string(),
            expires_at: SystemTime::now() + Duration::from_secs(600),
            base_domain: "rousecontext.com".to_string(),
            created_at: SystemTime::now(),
        },
    ));
    let state = state_with(
        firestore.clone(),
        SubdomainGenerator::scripted(["reserved-name", "fresh-name"]),
    );

    let (status, json) = send(
        build_router(state),
        post("/register", keypair_register_body(&new_key(), "n-1")),
    )
    .await;

    assert_eq!(status, 200, "registration should succeed: {json}");
    assert_eq!(json["subdomain"], "fresh-name");
    assert!(firestore
        .reservations
        .lock()
        .unwrap()
        .contains_key("reserved-name"));
    assert!(!firestore
        .devices
        .lock()
        .unwrap()
        .contains_key("reserved-name"));
}

#[tokio::test]
async fn registration_fails_closed_when_every_candidate_is_held() {
    let established_key = new_key();
    let firestore = Arc::new(
        MockFirestore::new().with_device("taken-name", established_device(&established_key)),
    );
    let before = snapshot(&firestore.devices.lock().unwrap()["taken-name"]);
    let state = state_with(
        firestore.clone(),
        SubdomainGenerator::scripted(vec!["taken-name"; 200]),
    );

    let (status, json) = send(
        build_router(state),
        post("/register", keypair_register_body(&new_key(), "n-1")),
    )
    .await;

    assert_ne!(status, 200, "must not succeed without a free name: {json}");
    let devices = firestore.devices.lock().unwrap();
    assert_eq!(devices.len(), 1, "no new record may be written");
    assert_eq!(snapshot(&devices["taken-name"]), before);
}

/// Round 2 for a brand-new identity only ever yields certs for that
/// identity's own freshly assigned subdomain, and the established device's
/// bound key stays bound.
#[tokio::test]
async fn round_two_for_a_new_identity_never_covers_an_existing_device_subdomain() {
    let established_key = new_key();
    let firestore = Arc::new(
        MockFirestore::new().with_device("taken-name", established_device(&established_key)),
    );
    let established_pk = firestore.devices.lock().unwrap()["taken-name"]
        .public_key
        .clone();
    let state = state_with(
        firestore.clone(),
        SubdomainGenerator::scripted(["taken-name", "fresh-name"]),
    );

    // Round 1 with a new key.
    let (csr_der, new_device_key) = generate_test_csr("x.rousecontext.com");
    let (status, _) = send(
        build_router(state.clone()),
        post("/register", keypair_register_body(&new_device_key, "n-1")),
    )
    .await;
    assert_eq!(status, 200);

    // Round 2: CSR for the new key, no signature (fresh registration).
    let (status, json) = send(
        build_router(state),
        post(
            "/register/certs",
            serde_json::json!({ "csr": BASE64.encode(&csr_der) }).to_string(),
        ),
    )
    .await;

    assert_eq!(status, 200, "round 2 should succeed: {json}");
    assert_eq!(json["subdomain"], "fresh-name");
    let devices = firestore.devices.lock().unwrap();
    assert_eq!(devices["taken-name"].public_key, established_pk);
    assert_eq!(
        devices["taken-name"].key_thumbprint.as_deref(),
        Some(thumbprint(&established_key).as_str())
    );
}

// ── the write itself is atomic, independent of the pre-check ───────────

/// Delegates to [`MockFirestore`] but reports every subdomain as absent on
/// point reads, modelling the window in which two registrations both pass the
/// availability check before either has written.
struct StaleReadFirestore(Arc<MockFirestore>);

#[async_trait]
impl FirestoreClient for StaleReadFirestore {
    async fn get_device(&self, subdomain: &str) -> Result<DeviceRecord, FirestoreError> {
        Err(FirestoreError::NotFound(subdomain.to_string()))
    }
    async fn find_device_by_uid(
        &self,
        uid: &str,
    ) -> Result<Option<(String, DeviceRecord)>, FirestoreError> {
        self.0.find_device_by_uid(uid).await
    }
    async fn find_device_by_thumbprint(
        &self,
        tp: &str,
    ) -> Result<Option<(String, DeviceRecord)>, FirestoreError> {
        self.0.find_device_by_thumbprint(tp).await
    }
    async fn create_device(&self, s: &str, r: &DeviceRecord) -> Result<(), FirestoreError> {
        // Yield first so concurrent registrations interleave here.
        tokio::task::yield_now().await;
        self.0.create_device(s, r).await
    }
    async fn put_device(&self, s: &str, r: &DeviceRecord) -> Result<(), FirestoreError> {
        tokio::task::yield_now().await;
        self.0.put_device(s, r).await
    }
    async fn set_device_last_seen(
        &self,
        s: &str,
        t: std::time::SystemTime,
    ) -> Result<(), FirestoreError> {
        self.0.set_device_last_seen(s, t).await
    }
    async fn delete_device(&self, s: &str) -> Result<(), FirestoreError> {
        self.0.delete_device(s).await
    }
    async fn put_pending_cert(&self, s: &str, p: &PendingCert) -> Result<(), FirestoreError> {
        self.0.put_pending_cert(s, p).await
    }
    async fn get_pending_cert(&self, s: &str) -> Result<PendingCert, FirestoreError> {
        self.0.get_pending_cert(s).await
    }
    async fn delete_pending_cert(&self, s: &str) -> Result<(), FirestoreError> {
        self.0.delete_pending_cert(s).await
    }
    async fn list_devices(&self) -> Result<Vec<(String, DeviceRecord)>, FirestoreError> {
        self.0.list_devices().await
    }
    async fn list_pending_certs(&self) -> Result<Vec<(String, PendingCert)>, FirestoreError> {
        self.0.list_pending_certs().await
    }
    async fn put_reservation(
        &self,
        s: &str,
        r: &SubdomainReservation,
    ) -> Result<(), FirestoreError> {
        self.0.put_reservation(s, r).await
    }
    async fn get_reservation(&self, s: &str) -> Result<SubdomainReservation, FirestoreError> {
        Err(FirestoreError::NotFound(s.to_string()))
    }
    async fn find_reservation_by_uid(
        &self,
        uid: &str,
    ) -> Result<Option<(String, SubdomainReservation)>, FirestoreError> {
        self.0.find_reservation_by_uid(uid).await
    }
    async fn delete_reservation(&self, s: &str) -> Result<(), FirestoreError> {
        self.0.delete_reservation(s).await
    }
    async fn list_reservations(
        &self,
    ) -> Result<Vec<(String, SubdomainReservation)>, FirestoreError> {
        self.0.list_reservations().await
    }
}

#[tokio::test]
async fn concurrent_registrations_drawing_the_same_name_get_distinct_subdomains() {
    let inner = Arc::new(MockFirestore::new());
    let state = state_with(
        Arc::new(StaleReadFirestore(inner.clone())),
        SubdomainGenerator::scripted(["dup", "dup", "alt-one", "alt-two"]),
    );
    let key_a = new_key();
    let key_b = new_key();

    let ((status_a, json_a), (status_b, json_b)) = tokio::join!(
        send(
            build_router(state.clone()),
            post("/register", keypair_register_body(&key_a, "a"))
        ),
        send(
            build_router(state.clone()),
            post("/register", keypair_register_body(&key_b, "b"))
        ),
    );

    assert_eq!(status_a, 200, "{json_a}");
    assert_eq!(status_b, 200, "{json_b}");
    let sub_a = json_a["subdomain"].as_str().unwrap().to_string();
    let sub_b = json_b["subdomain"].as_str().unwrap().to_string();
    assert_ne!(sub_a, sub_b, "exactly one registration may hold `dup`");
    assert!(
        sub_a == "dup" || sub_b == "dup",
        "one of them should hold `dup`"
    );

    // Each response's subdomain maps to that caller's own identity.
    let devices = inner.devices.lock().unwrap();
    assert_eq!(devices.len(), 2);
    assert_eq!(
        devices[&sub_a].key_thumbprint.as_deref(),
        Some(thumbprint(&key_a).as_str())
    );
    assert_eq!(
        devices[&sub_b].key_thumbprint.as_deref(),
        Some(thumbprint(&key_b).as_str())
    );
}

#[tokio::test]
async fn registration_never_replaces_an_existing_record_even_if_the_check_misses_it() {
    let established_key = new_key();
    let inner = Arc::new(
        MockFirestore::new().with_device("taken-name", established_device(&established_key)),
    );
    let before = snapshot(&inner.devices.lock().unwrap()["taken-name"]);
    let state = state_with(
        Arc::new(StaleReadFirestore(inner.clone())),
        SubdomainGenerator::scripted(["taken-name", "fresh-name"]),
    );

    let (status, json) = send(
        build_router(state),
        post("/register", keypair_register_body(&new_key(), "n-1")),
    )
    .await;

    assert_eq!(status, 200, "{json}");
    assert_eq!(json["subdomain"], "fresh-name");
    assert_eq!(
        snapshot(&inner.devices.lock().unwrap()["taken-name"]),
        before
    );
}

// ── same identity keeps its subdomain ──────────────────────────────────

#[tokio::test]
async fn keypair_re_registration_reuses_its_own_subdomain() {
    let firestore = Arc::new(MockFirestore::new());
    let state = state_with(
        firestore.clone(),
        SubdomainGenerator::scripted(["first-name", "second-name"]),
    );
    let key = new_key();

    let (s1, j1) = send(
        build_router(state.clone()),
        post("/register", keypair_register_body(&key, "n-1")),
    )
    .await;
    let (s2, j2) = send(
        build_router(state),
        post("/register", keypair_register_body(&key, "n-2")),
    )
    .await;

    assert_eq!(s1, 200, "{j1}");
    assert_eq!(s2, 200, "{j2}");
    assert_eq!(j1["subdomain"], "first-name");
    assert_eq!(j2["subdomain"], "first-name");
    assert_eq!(firestore.devices.lock().unwrap().len(), 1);
}

// ── per-IP rate limit ──────────────────────────────────────────────────

#[tokio::test]
async fn register_is_rate_limited_per_client_ip() {
    let state = state_with(Arc::new(MockFirestore::new()), SubdomainGenerator::new());
    let ip_a: IpAddr = "203.0.113.7".parse().unwrap();
    let ip_b: IpAddr = "198.51.100.9".parse().unwrap();
    let router_for = |ip: IpAddr| build_router(state.clone()).layer(axum::Extension(ClientIp(ip)));
    let key = new_key();

    let burst = rouse_relay::rate_limit::RateLimitConfig::register_per_ip().max_tokens;
    for i in 0..burst {
        let (status, json) = send(
            router_for(ip_a),
            post("/register", keypair_register_body(&key, &format!("a-{i}"))),
        )
        .await;
        assert_eq!(status, 200, "request {i} within the burst: {json}");
    }

    let (status, json) = send(
        router_for(ip_a),
        post("/register", keypair_register_body(&key, "a-over")),
    )
    .await;
    assert_eq!(
        status, 429,
        "request beyond the burst must be rejected: {json}"
    );
    assert_eq!(json["error"], "rate_limited");

    let (status, json) = send(
        router_for(ip_b),
        post("/register", keypair_register_body(&new_key(), "b-0")),
    )
    .await;
    assert_eq!(status, 200, "another IP has its own budget: {json}");
}
