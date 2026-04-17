//! Integration test: drive `RealAcmeClient` against a mock ACME server that
//! requires External Account Binding (GTS-style) and verify the relay
//! registers successfully.
//!
//! The mock server implements just enough of RFC 8555 to walk the full
//! `issue_certificate` flow: `directory`, `newNonce`, `newAccount` (with
//! EAB validation), `newOrder`, authorization fetch + response, finalize,
//! and cert download. The DNS-01 step uses a stub provider that accepts
//! any publish/propagation request.
//!
//! The test does NOT validate the ACME ECDSA signature (we already have
//! unit tests for `sign_jws`), but it DOES fully validate the EAB HS256
//! signature the relay produces, because that's the point of this test.

use async_trait::async_trait;
use axum::extract::{Path, State};
use axum::http::{HeaderMap, StatusCode};
use axum::response::IntoResponse;
use axum::routing::{get, head, post};
use axum::Router;
use base64::Engine as _;
use hmac::{Hmac, Mac};
use rouse_relay::acme::{AcmeClient, ExternalAccountBinding, RealAcmeClient};
use rouse_relay::dns_challenge::{DnsChallengeProvider, DnsError, TxtHandle};
use sha2::Sha256;
use std::sync::{Arc, Mutex};
use tokio::net::TcpListener;

type HmacSha256 = Hmac<Sha256>;

/// State shared across the mock ACME server's handlers.
#[derive(Clone)]
struct MockAcmeState {
    base_url: String,
    eab_kid: String,
    eab_hmac: Vec<u8>,
    cert_pem: String,
    /// Records which endpoints the relay hit so the test can assert on
    /// the full request sequence.
    calls: Arc<Mutex<Vec<String>>>,
    /// Captured `externalAccountBinding` field from `newAccount` for
    /// post-hoc structural assertions.
    captured_eab: Arc<Mutex<Option<serde_json::Value>>>,
}

impl MockAcmeState {
    fn record(&self, label: &str) {
        self.calls.lock().unwrap().push(label.to_string());
    }
}

async fn directory(State(s): State<MockAcmeState>) -> impl IntoResponse {
    s.record("GET /directory");
    axum::Json(serde_json::json!({
        "newNonce": format!("{}/newNonce", s.base_url),
        "newAccount": format!("{}/newAccount", s.base_url),
        "newOrder": format!("{}/newOrder", s.base_url),
    }))
}

async fn new_nonce(State(s): State<MockAcmeState>) -> impl IntoResponse {
    s.record("HEAD /newNonce");
    let mut headers = HeaderMap::new();
    headers.insert("replay-nonce", "nonce-0".parse().unwrap());
    (StatusCode::OK, headers)
}

fn decode_b64(b64: &str) -> Vec<u8> {
    base64::engine::general_purpose::URL_SAFE_NO_PAD
        .decode(b64)
        .expect("valid base64url")
}

/// Validate the EAB JWS inside a `newAccount` payload. Panics (i.e. fails
/// the test) if the EAB is malformed or the HMAC doesn't match.
fn validate_eab(eab: &serde_json::Value, expected_kid: &str, hmac_key: &[u8], expected_url: &str) {
    // Protected: alg=HS256, kid=<kid>, url=<url>.
    let protected_b64 = eab["protected"].as_str().expect("eab protected is string");
    let payload_b64 = eab["payload"].as_str().expect("eab payload is string");
    let sig_b64 = eab["signature"].as_str().expect("eab signature is string");

    let protected: serde_json::Value =
        serde_json::from_slice(&decode_b64(protected_b64)).expect("eab protected is JSON");
    assert_eq!(protected["alg"], "HS256", "EAB alg must be HS256");
    assert_eq!(protected["kid"], expected_kid, "EAB kid mismatch");
    assert_eq!(protected["url"], expected_url, "EAB url mismatch");

    // Payload is the outer account's JWK — must have kty=EC, crv=P-256,
    // plus `x` and `y`.
    let payload: serde_json::Value =
        serde_json::from_slice(&decode_b64(payload_b64)).expect("eab payload is JSON JWK");
    assert_eq!(payload["kty"], "EC");
    assert_eq!(payload["crv"], "P-256");
    assert!(payload.get("x").is_some());
    assert!(payload.get("y").is_some());

    // Verify HMAC-SHA256 matches.
    let signing_input = format!("{protected_b64}.{payload_b64}");
    let mut mac = HmacSha256::new_from_slice(hmac_key).unwrap();
    mac.update(signing_input.as_bytes());
    let expected_sig_bytes = mac.finalize().into_bytes();
    let actual_sig_bytes = decode_b64(sig_b64);
    assert_eq!(
        actual_sig_bytes,
        expected_sig_bytes.as_slice(),
        "EAB HMAC signature did not verify with the expected key"
    );
}

async fn new_account(
    State(s): State<MockAcmeState>,
    body: String,
) -> Result<(StatusCode, HeaderMap, ()), (StatusCode, String)> {
    s.record("POST /newAccount");

    // Outer JWS wraps the payload.
    let outer: serde_json::Value = serde_json::from_str(&body)
        .map_err(|e| (StatusCode::BAD_REQUEST, format!("bad outer JSON: {e}")))?;
    let outer_payload_b64 = outer["payload"]
        .as_str()
        .ok_or((StatusCode::BAD_REQUEST, "outer payload missing".to_string()))?;
    let outer_payload: serde_json::Value =
        serde_json::from_slice(&decode_b64(outer_payload_b64))
            .map_err(|e| (StatusCode::BAD_REQUEST, format!("bad inner JSON: {e}")))?;

    // GTS emits externalAccountRequired when EAB is missing.
    let eab = outer_payload.get("externalAccountBinding").ok_or((
        StatusCode::BAD_REQUEST,
        "externalAccountBinding required".to_string(),
    ))?;

    validate_eab(
        eab,
        &s.eab_kid,
        &s.eab_hmac,
        &format!("{}/newAccount", s.base_url),
    );
    *s.captured_eab.lock().unwrap() = Some(eab.clone());

    let mut headers = HeaderMap::new();
    headers.insert(
        "location",
        format!("{}/account/1", s.base_url).parse().unwrap(),
    );
    headers.insert("replay-nonce", "nonce-1".parse().unwrap());
    Ok((StatusCode::CREATED, headers, ()))
}

async fn new_order(State(s): State<MockAcmeState>) -> impl IntoResponse {
    s.record("POST /newOrder");
    let mut headers = HeaderMap::new();
    headers.insert(
        "location",
        format!("{}/order/1", s.base_url).parse().unwrap(),
    );
    headers.insert("replay-nonce", "nonce-2".parse().unwrap());
    let body = serde_json::json!({
        "status": "pending",
        "authorizations": [format!("{}/authz/1", s.base_url)],
        "finalize": format!("{}/order/1/finalize", s.base_url),
    });
    (StatusCode::CREATED, headers, axum::Json(body))
}

async fn authz(State(s): State<MockAcmeState>) -> impl IntoResponse {
    s.record("POST /authz/1");
    let mut headers = HeaderMap::new();
    headers.insert("replay-nonce", "nonce-3".parse().unwrap());
    let body = serde_json::json!({
        "status": "pending",
        "challenges": [
            {
                "type": "dns-01",
                "url": format!("{}/challenge/1", s.base_url),
                "token": "challenge-token-abc",
                "status": "pending",
            }
        ]
    });
    (StatusCode::OK, headers, axum::Json(body))
}

async fn challenge_respond(State(s): State<MockAcmeState>) -> impl IntoResponse {
    s.record("POST /challenge/1");
    let mut headers = HeaderMap::new();
    headers.insert("replay-nonce", "nonce-4".parse().unwrap());
    (StatusCode::OK, headers, axum::Json(serde_json::json!({})))
}

// After the challenge is posted, the client polls `/authz/1` again; we
// route subsequent authz hits through a counter to flip status to "valid".
// For this test we just always return valid to keep it simple.
async fn authz_valid(State(s): State<MockAcmeState>) -> impl IntoResponse {
    s.record("POST /authz/1/poll");
    let mut headers = HeaderMap::new();
    headers.insert("replay-nonce", "nonce-5".parse().unwrap());
    let body = serde_json::json!({
        "status": "valid",
        "challenges": [
            {
                "type": "dns-01",
                "url": format!("{}/challenge/1", s.base_url),
                "token": "challenge-token-abc",
                "status": "valid",
            }
        ]
    });
    (StatusCode::OK, headers, axum::Json(body))
}

async fn finalize(State(s): State<MockAcmeState>) -> impl IntoResponse {
    s.record("POST /order/1/finalize");
    let mut headers = HeaderMap::new();
    headers.insert("replay-nonce", "nonce-6".parse().unwrap());
    let body = serde_json::json!({
        "status": "processing",
        "authorizations": [format!("{}/authz/1", s.base_url)],
        "finalize": format!("{}/order/1/finalize", s.base_url),
    });
    (StatusCode::OK, headers, axum::Json(body))
}

async fn order_poll(State(s): State<MockAcmeState>) -> impl IntoResponse {
    s.record("POST /order/1");
    let mut headers = HeaderMap::new();
    headers.insert("replay-nonce", "nonce-7".parse().unwrap());
    let body = serde_json::json!({
        "status": "valid",
        "authorizations": [format!("{}/authz/1", s.base_url)],
        "finalize": format!("{}/order/1/finalize", s.base_url),
        "certificate": format!("{}/cert/1", s.base_url),
    });
    (StatusCode::OK, headers, axum::Json(body))
}

async fn cert_download(State(s): State<MockAcmeState>) -> impl IntoResponse {
    s.record("POST /cert/1");
    let mut headers = HeaderMap::new();
    headers.insert("replay-nonce", "nonce-8".parse().unwrap());
    headers.insert(
        "content-type",
        "application/pem-certificate-chain".parse().unwrap(),
    );
    (StatusCode::OK, headers, s.cert_pem.clone())
}

/// Tiny in-router state machine: authz hits alternate between "pending"
/// (first) and "valid" (subsequent).
async fn authz_dispatch(
    State(s): State<MockAcmeState>,
    Path(_id): Path<String>,
) -> axum::response::Response {
    let count = {
        let calls = s.calls.lock().unwrap();
        calls.iter().filter(|c| c.contains("/authz/")).count()
    };
    // Don't rely on state machines across handlers — inline the branching.
    if count == 0 {
        authz(State(s)).await.into_response()
    } else {
        authz_valid(State(s)).await.into_response()
    }
}

/// Stub DNS provider — accepts all publish/propagation requests.
#[derive(Default)]
struct StubDns;

#[async_trait]
impl DnsChallengeProvider for StubDns {
    async fn publish_txt(&self, _name: &str, _value: &str) -> Result<TxtHandle, DnsError> {
        Ok(TxtHandle("stub".to_string()))
    }
    async fn wait_for_propagation(&self, _name: &str, _value: &str) -> Result<(), DnsError> {
        Ok(())
    }
    async fn delete_txt(&self, _handle: &TxtHandle) {}
    async fn cleanup_stale(&self, _name: &str) -> Result<(), DnsError> {
        Ok(())
    }
}

fn self_signed_cert_pem() -> String {
    let key = rcgen::KeyPair::generate().unwrap();
    let mut params = rcgen::CertificateParams::new(vec!["example.com".to_string()]).unwrap();
    let mut dn = rcgen::DistinguishedName::new();
    dn.push(rcgen::DnType::CommonName, "example.com");
    params.distinguished_name = dn;
    let cert = params.self_signed(&key).unwrap();
    cert.pem()
}

#[tokio::test]
async fn acme_client_registers_with_eab_against_mock_server() {
    let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let addr = listener.local_addr().unwrap();
    let base_url = format!("http://{addr}");

    let state = MockAcmeState {
        base_url: base_url.clone(),
        eab_kid: "test-eab-kid".to_string(),
        eab_hmac: b"mock-eab-hmac-key-at-least-thirty-two-bytes!!".to_vec(),
        cert_pem: self_signed_cert_pem(),
        calls: Arc::new(Mutex::new(Vec::new())),
        captured_eab: Arc::new(Mutex::new(None)),
    };

    let app: Router = Router::new()
        .route("/directory", get(directory))
        .route("/newNonce", head(new_nonce))
        .route("/newAccount", post(new_account))
        .route("/newOrder", post(new_order))
        .route("/authz/{id}", post(authz_dispatch))
        .route("/challenge/1", post(challenge_respond))
        .route("/order/1/finalize", post(finalize))
        .route("/order/1", post(order_poll))
        .route("/cert/1", post(cert_download))
        .with_state(state.clone());

    tokio::spawn(async move {
        axum::serve(listener, app).await.unwrap();
    });

    let dns: Arc<dyn DnsChallengeProvider> = Arc::new(StubDns);
    let client = RealAcmeClient::new(
        format!("{base_url}/directory"),
        dns,
        "example.com".to_string(),
    )
    .with_external_account_binding(ExternalAccountBinding {
        kid: state.eab_kid.clone(),
        hmac_key: state.eab_hmac.clone(),
    });

    let bundle = client
        .issue_certificate("sub", None)
        .await
        .expect("cert issuance should succeed against EAB-requiring mock");
    assert!(!bundle.cert_pem.is_empty());
    assert!(bundle.cert_pem.contains("BEGIN CERTIFICATE"));

    // EAB must have been captured by the mock's newAccount handler.
    let captured = state.captured_eab.lock().unwrap().clone();
    assert!(
        captured.is_some(),
        "mock server did not see externalAccountBinding on newAccount"
    );

    // Sanity: full RFC 8555 flow happened.
    let calls = state.calls.lock().unwrap().clone();
    assert!(calls.contains(&"POST /newAccount".to_string()));
    assert!(calls.contains(&"POST /newOrder".to_string()));
    assert!(calls.contains(&"POST /order/1/finalize".to_string()));
    assert!(calls.contains(&"POST /cert/1".to_string()));
}

#[tokio::test]
async fn acme_client_no_eab_registers_against_le_style_mock() {
    // Same mock, but the newAccount handler does NOT require EAB — it just
    // accepts any payload. Asserts that the LE-style path is unchanged.
    let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let addr = listener.local_addr().unwrap();
    let base_url = format!("http://{addr}");

    #[derive(Clone)]
    struct LeState {
        base_url: String,
        cert_pem: String,
        calls: Arc<Mutex<Vec<String>>>,
        saw_eab: Arc<Mutex<bool>>,
    }

    let le_state = LeState {
        base_url: base_url.clone(),
        cert_pem: self_signed_cert_pem(),
        calls: Arc::new(Mutex::new(Vec::new())),
        saw_eab: Arc::new(Mutex::new(false)),
    };

    async fn le_directory(State(s): State<LeState>) -> impl IntoResponse {
        s.calls.lock().unwrap().push("directory".into());
        axum::Json(serde_json::json!({
            "newNonce": format!("{}/newNonce", s.base_url),
            "newAccount": format!("{}/newAccount", s.base_url),
            "newOrder": format!("{}/newOrder", s.base_url),
        }))
    }
    async fn le_nonce() -> impl IntoResponse {
        let mut headers = HeaderMap::new();
        headers.insert("replay-nonce", "n".parse().unwrap());
        (StatusCode::OK, headers)
    }
    async fn le_new_account(State(s): State<LeState>, body: String) -> (StatusCode, HeaderMap, ()) {
        s.calls.lock().unwrap().push("newAccount".into());
        let outer: serde_json::Value = serde_json::from_str(&body).unwrap();
        let payload_b64 = outer["payload"].as_str().unwrap();
        let payload: serde_json::Value = serde_json::from_slice(&decode_b64(payload_b64)).unwrap();
        if payload.get("externalAccountBinding").is_some() {
            *s.saw_eab.lock().unwrap() = true;
        }
        let mut headers = HeaderMap::new();
        headers.insert(
            "location",
            format!("{}/account/1", s.base_url).parse().unwrap(),
        );
        headers.insert("replay-nonce", "n".parse().unwrap());
        (StatusCode::CREATED, headers, ())
    }
    async fn le_new_order(State(s): State<LeState>) -> impl IntoResponse {
        s.calls.lock().unwrap().push("newOrder".into());
        let mut headers = HeaderMap::new();
        headers.insert(
            "location",
            format!("{}/order/1", s.base_url).parse().unwrap(),
        );
        headers.insert("replay-nonce", "n".parse().unwrap());
        let body = serde_json::json!({
            "status": "pending",
            "authorizations": [format!("{}/authz/1", s.base_url)],
            "finalize": format!("{}/order/1/finalize", s.base_url),
        });
        (StatusCode::CREATED, headers, axum::Json(body))
    }
    async fn le_authz(State(s): State<LeState>, Path(_id): Path<String>) -> impl IntoResponse {
        let first = {
            let mut calls = s.calls.lock().unwrap();
            let was_first = !calls.iter().any(|c: &String| c == "authz");
            calls.push("authz".into());
            was_first
        };
        let mut headers = HeaderMap::new();
        headers.insert("replay-nonce", "n".parse().unwrap());
        let status = if first { "pending" } else { "valid" };
        let chal_status = if first { "pending" } else { "valid" };
        let body = serde_json::json!({
            "status": status,
            "challenges": [{
                "type": "dns-01",
                "url": format!("{}/challenge/1", s.base_url),
                "token": "t",
                "status": chal_status,
            }]
        });
        (StatusCode::OK, headers, axum::Json(body))
    }
    async fn le_chall(State(s): State<LeState>) -> impl IntoResponse {
        s.calls.lock().unwrap().push("challenge".into());
        let mut headers = HeaderMap::new();
        headers.insert("replay-nonce", "n".parse().unwrap());
        (StatusCode::OK, headers, axum::Json(serde_json::json!({})))
    }
    async fn le_finalize(State(s): State<LeState>) -> impl IntoResponse {
        s.calls.lock().unwrap().push("finalize".into());
        let mut headers = HeaderMap::new();
        headers.insert("replay-nonce", "n".parse().unwrap());
        let body = serde_json::json!({
            "status": "processing",
            "authorizations": [format!("{}/authz/1", s.base_url)],
            "finalize": format!("{}/order/1/finalize", s.base_url),
        });
        (StatusCode::OK, headers, axum::Json(body))
    }
    async fn le_order(State(s): State<LeState>) -> impl IntoResponse {
        s.calls.lock().unwrap().push("order".into());
        let mut headers = HeaderMap::new();
        headers.insert("replay-nonce", "n".parse().unwrap());
        let body = serde_json::json!({
            "status": "valid",
            "authorizations": [format!("{}/authz/1", s.base_url)],
            "finalize": format!("{}/order/1/finalize", s.base_url),
            "certificate": format!("{}/cert/1", s.base_url),
        });
        (StatusCode::OK, headers, axum::Json(body))
    }
    async fn le_cert(State(s): State<LeState>) -> impl IntoResponse {
        s.calls.lock().unwrap().push("cert".into());
        let mut headers = HeaderMap::new();
        headers.insert("replay-nonce", "n".parse().unwrap());
        (StatusCode::OK, headers, s.cert_pem.clone())
    }

    let app: Router = Router::new()
        .route("/directory", get(le_directory))
        .route("/newNonce", head(le_nonce))
        .route("/newAccount", post(le_new_account))
        .route("/newOrder", post(le_new_order))
        .route("/authz/{id}", post(le_authz))
        .route("/challenge/1", post(le_chall))
        .route("/order/1/finalize", post(le_finalize))
        .route("/order/1", post(le_order))
        .route("/cert/1", post(le_cert))
        .with_state(le_state.clone());

    tokio::spawn(async move {
        axum::serve(listener, app).await.unwrap();
    });

    let dns: Arc<dyn DnsChallengeProvider> = Arc::new(StubDns);
    // No EAB configured: this mimics the LE path.
    let client = RealAcmeClient::new(
        format!("{base_url}/directory"),
        dns,
        "example.com".to_string(),
    );

    let bundle = client
        .issue_certificate("sub", None)
        .await
        .expect("LE-style issuance (no EAB) should succeed");
    assert!(bundle.cert_pem.contains("BEGIN CERTIFICATE"));
    assert!(
        !*le_state.saw_eab.lock().unwrap(),
        "newAccount payload must not include externalAccountBinding when EAB is not configured"
    );
}
