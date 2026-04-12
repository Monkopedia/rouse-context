//! POST /renew -- Certificate renewal.
//!
//! Two auth paths:
//! - Path A (mTLS): valid client cert extracts subdomain, only CSR needed.
//! - Path B (Firebase): expired cert, needs firebase_token + subdomain + signature.

use axum::extract::State;
use axum::http::StatusCode;
use axum::response::{IntoResponse, Response};
use axum::Json;
use base64::engine::general_purpose::STANDARD as BASE64;
use base64::Engine;
use p256::ecdsa::signature::Verifier;
use p256::ecdsa::{Signature, VerifyingKey};
use p256::pkcs8::DecodePublicKey;
use serde::{Deserialize, Serialize};
use std::sync::Arc;
use std::time::{Duration, SystemTime};

use crate::api::{ApiError, AppState};

#[derive(Debug, Deserialize)]
pub struct RenewRequest {
    pub csr: String,
    /// Required for Path B (expired cert).
    pub subdomain: Option<String>,
    /// Required for Path B.
    pub firebase_token: Option<String>,
    /// Required for Path B.
    pub signature: Option<String>,
    /// Optional: set by middleware if a valid mTLS client cert was presented.
    /// This is extracted from the cert CN/SAN, not from the request body.
    #[serde(skip)]
    pub mtls_subdomain: Option<String>,
}

#[derive(Debug, Serialize)]
pub struct RenewResponse {
    /// PEM-encoded ACME server certificate (Let's Encrypt, serverAuth).
    pub server_cert: String,
    /// PEM-encoded relay CA client certificate (clientAuth).
    pub client_cert: String,
    /// PEM-encoded relay CA root certificate.
    pub relay_ca_cert: String,
}

pub async fn handle_renew(
    State(state): State<Arc<AppState>>,
    Json(req): Json<RenewRequest>,
) -> Response {
    // Validate CSR
    if req.csr.is_empty() {
        return ApiError::bad_request("Missing required field: csr").into_response();
    }

    let csr_der = match BASE64.decode(&req.csr) {
        Ok(bytes) => bytes,
        Err(_) => return ApiError::bad_request("Invalid Base64 in csr field").into_response(),
    };

    // Determine auth path.
    // In a real deployment, mtls_subdomain would be set by TLS middleware.
    // For now, we use Path B (Firebase + signature) when subdomain + firebase_token are present.
    let subdomain = if let Some(ref mtls_sub) = req.mtls_subdomain {
        // Path A: mTLS
        mtls_sub.clone()
    } else if let (Some(ref sub), Some(ref token)) = (&req.subdomain, &req.firebase_token) {
        // Path B: Firebase + signature
        if sub.is_empty() {
            return ApiError::bad_request("Missing required field: subdomain").into_response();
        }
        if token.is_empty() {
            return ApiError::bad_request("Missing required field: firebase_token").into_response();
        }

        // Verify Firebase token
        let claims = match state.firebase_auth.verify_id_token(token).await {
            Ok(c) => c,
            Err(e) => {
                return ApiError::unauthorized(format!("Invalid Firebase ID token: {e}"))
                    .into_response()
            }
        };

        // Look up device record
        let record = match state.firestore.get_device(sub).await {
            Ok(r) => r,
            Err(crate::firestore::FirestoreError::NotFound(_)) => {
                return ApiError::not_found("Device not found").into_response()
            }
            Err(e) => {
                return ApiError::internal(format!("Firestore lookup failed: {e}")).into_response()
            }
        };

        // Verify Firebase UID matches
        if record.firebase_uid != claims.uid {
            return ApiError::forbidden("Firebase UID does not match device record")
                .into_response();
        }

        // Verify signature
        let sig_b64 = match &req.signature {
            Some(s) if !s.is_empty() => s,
            _ => return ApiError::bad_request("Missing required field: signature").into_response(),
        };

        if let Err(e) = verify_signature(&record.public_key, &csr_der, sig_b64) {
            return ApiError::forbidden(format!("Signature verification failed: {e}"))
                .into_response();
        }

        sub.clone()
    } else {
        // No mTLS and no Firebase path fields
        return ApiError::unauthorized(
            "Valid client certificate required or provide firebase_token + subdomain + signature",
        )
        .into_response();
    };

    // Verify device exists (for Path A, we haven't checked yet)
    if req.mtls_subdomain.is_some() {
        match state.firestore.get_device(&subdomain).await {
            Ok(_) => {}
            Err(crate::firestore::FirestoreError::NotFound(_)) => {
                return ApiError::not_found("Device not found").into_response()
            }
            Err(e) => {
                return ApiError::internal(format!("Firestore lookup failed: {e}")).into_response()
            }
        }
    }

    // Extract public key from CSR for client cert issuance
    let public_key_der = match crate::device_ca::extract_public_key_from_csr_der(&csr_der) {
        Ok(spki) => spki,
        Err(e) => {
            return ApiError::bad_request(format!("Failed to parse CSR: {e}")).into_response()
        }
    };

    // Issue ACME server cert using the device's CSR
    let acme_bundle = match state
        .acme
        .issue_certificate(&subdomain, Some(&csr_der))
        .await
    {
        Ok(b) => b,
        Err(crate::acme::AcmeError::RateLimited { retry_after_secs }) => {
            return ApiError::acme_rate_limited(
                "Certificate issuance rate limited",
                retry_after_secs,
            )
            .into_response();
        }
        Err(crate::acme::AcmeError::ChallengeFailed(msg)) => {
            return ApiError::acme_failure(format!("ACME DNS-01 challenge failed: {msg}"))
                .into_response();
        }
        Err(e) => {
            return ApiError::internal(format!("ACME error: {e}")).into_response();
        }
    };

    // Issue relay CA client cert using the device's public key
    let device_ca = match &state.device_ca {
        Some(ca) => ca,
        None => {
            return ApiError::internal("Device CA not configured").into_response();
        }
    };

    let base_domain = state.config.server.resolved_base_domain();

    let client_bundle = match device_ca.sign_client_cert(&public_key_der, &subdomain, &base_domain) {
        Ok(b) => b,
        Err(e) => {
            return ApiError::internal(format!("Failed to sign client cert: {e}")).into_response()
        }
    };

    // Update cert_expires and clear renewal_nudge_sent
    if let Ok(mut record) = state.firestore.get_device(&subdomain).await {
        record.cert_expires = SystemTime::now() + Duration::from_secs(90 * 86400);
        record.renewal_nudge_sent = None;
        let _ = state.firestore.put_device(&subdomain, &record).await;
    }

    (
        StatusCode::OK,
        Json(RenewResponse {
            server_cert: acme_bundle.cert_pem,
            client_cert: client_bundle.cert_pem,
            relay_ca_cert: device_ca.ca_cert_pem().to_string(),
        }),
    )
        .into_response()
}

/// Verify an ECDSA P-256 signature over the CSR bytes.
fn verify_signature(
    stored_public_key_b64: &str,
    csr_der: &[u8],
    signature_b64: &str,
) -> Result<(), String> {
    let pub_key_bytes = BASE64
        .decode(stored_public_key_b64)
        .map_err(|e| format!("invalid stored public key: {e}"))?;

    let sig_bytes = BASE64
        .decode(signature_b64)
        .map_err(|e| format!("invalid signature encoding: {e}"))?;

    let verifying_key = VerifyingKey::from_public_key_der(&pub_key_bytes)
        .map_err(|e| format!("invalid public key: {e}"))?;

    let signature =
        Signature::from_der(&sig_bytes).map_err(|e| format!("invalid signature format: {e}"))?;

    verifying_key
        .verify(csr_der, &signature)
        .map_err(|e| format!("signature mismatch: {e}"))
}
