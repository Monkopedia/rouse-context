//! Wire-shape pins for every error `resolve_register_identity` can return.
//!
//! `POST /register` is the device-registration endpoint, so the exact status
//! code and JSON body of each rejection is a client-visible contract. This file
//! exists because issue #683's fix changed that function's error type from a
//! built `axum::Response` to the `(StatusCode, Json<ApiError>)` pair the
//! `ApiError` constructors already produce (`clippy::result_large_err`), and a
//! refactor of error plumbing is exactly the kind of change that can move a
//! status code without anyone noticing.
//!
//! Three of the seven paths below -- non-Base64 `public_key`, and each of the
//! three missing keypair-proof fields -- had no coverage at all before this
//! file. The literals here are the contract; they were verified to be produced
//! byte-identically by the code before the refactor as well as after it.

mod test_helpers;

use base64::engine::general_purpose::STANDARD as BASE64;
use base64::Engine;
use rouse_relay::api::build_router;
use std::sync::Arc;
use std::time::SystemTime;
use test_helpers::*;

fn spki_b64(key: &p256::ecdsa::SigningKey) -> String {
    use p256::pkcs8::EncodePublicKey;
    BASE64.encode(key.verifying_key().to_public_key_der().unwrap().as_bytes())
}

fn sign_register_proof(key: &p256::ecdsa::SigningKey, ts: i64, nonce: &str) -> String {
    use p256::ecdsa::{signature::Signer, Signature};
    let msg = rouse_relay::keypair_auth::canonical_message(
        rouse_relay::keypair_auth::PURPOSE_REGISTER,
        ts,
        nonce,
    );
    let sig: Signature = key.sign(&msg);
    BASE64.encode(sig.to_der().as_bytes())
}

fn unix_now() -> i64 {
    SystemTime::now()
        .duration_since(SystemTime::UNIX_EPOCH)
        .unwrap()
        .as_secs() as i64
}

/// POST `body` to /register and return `(status, content-type, body bytes)`.
async fn post_register(
    auth: MockFirebaseAuth,
    body: serde_json::Value,
) -> (u16, String, serde_json::Value) {
    let state = build_test_state(
        Arc::new(MockFirestore::new()),
        Arc::new(MockFcm::new()),
        Arc::new(MockAcme::new("cert")),
        Arc::new(auth),
    );
    let app = build_router(state);
    let req = axum::http::Request::builder()
        .method("POST")
        .uri("/register")
        .header("content-type", "application/json")
        .body(axum::body::Body::from(body.to_string()))
        .unwrap();
    let resp = tower::ServiceExt::oneshot(app, req).await.unwrap();
    let status = resp.status().as_u16();
    let content_type = resp
        .headers()
        .get("content-type")
        .map(|v| v.to_str().unwrap().to_string())
        .unwrap_or_default();
    let bytes = axum::body::to_bytes(resp.into_body(), 1024 * 1024)
        .await
        .unwrap();
    let json: serde_json::Value = serde_json::from_slice(&bytes).unwrap_or_else(|e| {
        panic!(
            "body was not JSON ({e}): {:?}",
            String::from_utf8_lossy(&bytes)
        )
    });
    (status, content_type, json)
}

/// Assert the full wire shape: status, content-type, and the complete body.
///
/// `assert_eq!` on the whole `serde_json::Value` is deliberate -- comparing the
/// entire object catches an added or dropped field (e.g. a stray
/// `retry_after_secs`), which a per-field assertion would miss.
fn assert_shape(
    observed: (u16, String, serde_json::Value),
    status: u16,
    error: &str,
    message: &str,
) {
    let (got_status, got_ct, got_body) = observed;
    assert_eq!(got_status, status, "status code; body was {got_body}");
    assert_eq!(got_ct, "application/json", "content-type");
    assert_eq!(
        got_body,
        serde_json::json!({ "error": error, "message": message }),
        "response body"
    );
}

#[tokio::test]
async fn invalid_firebase_token_is_401_unauthorized() {
    let observed = post_register(
        MockFirebaseAuth::new(),
        serde_json::json!({ "firebase_token": "bad-token", "fcm_token": "device-fcm" }),
    )
    .await;
    assert_shape(
        observed,
        401,
        "unauthorized",
        "Invalid Firebase ID token: invalid token: unknown token",
    );
}

#[tokio::test]
async fn no_auth_material_at_all_is_400_bad_request() {
    let observed = post_register(
        MockFirebaseAuth::new(),
        serde_json::json!({ "fcm_token": "device-fcm" }),
    )
    .await;
    assert_shape(
        observed,
        400,
        "bad_request",
        "Missing auth: provide firebase_token, or public_key + auth proof",
    );
}

#[tokio::test]
async fn empty_public_key_is_treated_as_missing_auth() {
    // The `.filter(|s| !s.is_empty())` means an empty string takes the same
    // branch as an absent field, not the Base64 branch.
    let observed = post_register(
        MockFirebaseAuth::new(),
        serde_json::json!({ "fcm_token": "device-fcm", "public_key": "" }),
    )
    .await;
    assert_shape(
        observed,
        400,
        "bad_request",
        "Missing auth: provide firebase_token, or public_key + auth proof",
    );
}

#[tokio::test]
async fn non_base64_public_key_is_400_bad_request() {
    let observed = post_register(
        MockFirebaseAuth::new(),
        serde_json::json!({ "fcm_token": "device-fcm", "public_key": "not!valid!base64!" }),
    )
    .await;
    assert_shape(
        observed,
        400,
        "bad_request",
        "Invalid Base64 in public_key field",
    );
}

#[tokio::test]
async fn missing_auth_timestamp_is_400_bad_request() {
    let key = p256::ecdsa::SigningKey::random(&mut rand::thread_rng());
    let observed = post_register(
        MockFirebaseAuth::new(),
        serde_json::json!({ "fcm_token": "device-fcm", "public_key": spki_b64(&key) }),
    )
    .await;
    assert_shape(
        observed,
        400,
        "bad_request",
        "Missing required field: auth_timestamp",
    );
}

#[tokio::test]
async fn missing_auth_nonce_is_400_bad_request() {
    let key = p256::ecdsa::SigningKey::random(&mut rand::thread_rng());
    let observed = post_register(
        MockFirebaseAuth::new(),
        serde_json::json!({
            "fcm_token": "device-fcm",
            "public_key": spki_b64(&key),
            "auth_timestamp": unix_now()
        }),
    )
    .await;
    assert_shape(
        observed,
        400,
        "bad_request",
        "Missing required field: auth_nonce",
    );
}

#[tokio::test]
async fn missing_auth_signature_is_400_bad_request() {
    let key = p256::ecdsa::SigningKey::random(&mut rand::thread_rng());
    let observed = post_register(
        MockFirebaseAuth::new(),
        serde_json::json!({
            "fcm_token": "device-fcm",
            "public_key": spki_b64(&key),
            "auth_timestamp": unix_now(),
            "auth_nonce": "shape-nonce"
        }),
    )
    .await;
    assert_shape(
        observed,
        400,
        "bad_request",
        "Missing required field: auth_signature",
    );
}

#[tokio::test]
async fn rejected_keypair_proof_is_403_forbidden() {
    let key = p256::ecdsa::SigningKey::random(&mut rand::thread_rng());
    let other = p256::ecdsa::SigningKey::random(&mut rand::thread_rng());
    let ts = unix_now();
    // Signed by `other`, but `key`'s public key is supplied.
    let observed = post_register(
        MockFirebaseAuth::new(),
        serde_json::json!({
            "fcm_token": "device-fcm",
            "public_key": spki_b64(&key),
            "auth_timestamp": ts,
            "auth_nonce": "shape-nonce-bad-sig",
            "auth_signature": sign_register_proof(&other, ts, "shape-nonce-bad-sig")
        }),
    )
    .await;
    assert_shape(
        observed,
        403,
        "forbidden",
        "Keypair proof rejected: signature mismatch",
    );
}
