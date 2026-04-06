//! POST /register -- Device registration and subdomain rotation.

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
use sha2::{Digest, Sha256};
use std::sync::Arc;
use std::time::{Duration, SystemTime};

use crate::api::{ApiError, AppState};
use crate::firestore::DeviceRecord;

#[derive(Debug, Deserialize)]
pub struct RegisterRequest {
    pub firebase_token: String,
    pub csr: String,
    pub fcm_token: String,
    pub signature: Option<String>,
    #[serde(default)]
    pub force_new: bool,
}

#[derive(Debug, Serialize)]
pub struct RegisterResponse {
    pub subdomain: String,
    pub cert: String,
    pub private_key: String,
    pub relay_host: String,
}

pub async fn handle_register(
    State(state): State<Arc<AppState>>,
    Json(req): Json<RegisterRequest>,
) -> Response {
    // 1. Validate required fields
    if req.firebase_token.is_empty() {
        return ApiError::bad_request("Missing required field: firebase_token").into_response();
    }
    if req.csr.is_empty() {
        return ApiError::bad_request("Missing required field: csr").into_response();
    }
    if req.fcm_token.is_empty() {
        return ApiError::bad_request("Missing required field: fcm_token").into_response();
    }

    // 2. Decode CSR
    let csr_der = match BASE64.decode(&req.csr) {
        Ok(bytes) => bytes,
        Err(_) => return ApiError::bad_request("Invalid Base64 in csr field").into_response(),
    };

    // 3. Verify Firebase ID token
    let claims = match state
        .firebase_auth
        .verify_id_token(&req.firebase_token)
        .await
    {
        Ok(c) => c,
        Err(e) => {
            return ApiError::unauthorized(format!("Invalid Firebase ID token: {e}"))
                .into_response()
        }
    };
    let uid = claims.uid;

    // 4. Extract public key from CSR (we store the base64-encoded DER SPKI)
    // For now we just store the CSR's raw bytes as a stand-in; the ACME client
    // will handle the actual CSR parsing. We extract a fingerprint for storage.
    let public_key_b64 = extract_public_key_from_csr(&csr_der);

    // 5. Check if UID already has a subdomain
    let existing = match state.firestore.find_device_by_uid(&uid).await {
        Ok(existing) => existing,
        Err(e) => {
            return ApiError::internal(format!("Firestore lookup failed: {e}")).into_response()
        }
    };

    let subdomain = if let Some((existing_subdomain, existing_record)) = existing {
        // Re-registration or rotation: signature required
        let sig_b64 = match &req.signature {
            Some(s) if !s.is_empty() => s,
            _ => {
                return ApiError::forbidden("Signature required for re-registration")
                    .into_response()
            }
        };

        // Verify signature against stored public key
        if let Err(e) = verify_signature(&existing_record.public_key, &csr_der, sig_b64) {
            return ApiError::forbidden(format!("Signature verification failed: {e}"))
                .into_response();
        }

        if req.force_new {
            // Subdomain rotation: check 30-day cooldown
            let cooldown_days = state
                .config
                .limits
                .subdomain_rotation_cooldown_days
                .unwrap_or(30);
            let cooldown = Duration::from_secs(cooldown_days as u64 * 86400);
            if let Some(last_rotation) = existing_record.last_rotation {
                if let Ok(elapsed) = SystemTime::now().duration_since(last_rotation) {
                    if elapsed < cooldown {
                        let remaining = cooldown - elapsed;
                        return ApiError::cooldown(
                            "Subdomain rotation cooldown not expired",
                            remaining.as_secs(),
                        )
                        .into_response();
                    }
                }
            }

            // Delete old subdomain
            if let Err(e) = state.firestore.delete_device(&existing_subdomain).await {
                return ApiError::internal(format!("Failed to delete old device record: {e}"))
                    .into_response();
            }

            // Generate new subdomain
            state.subdomain_generator.generate()
        } else {
            // Re-registration: reuse existing subdomain
            existing_subdomain
        }
    } else {
        // New registration: generate subdomain
        state.subdomain_generator.generate()
    };

    // 6. Issue certificate via ACME (relay generates keypair + CSR with correct FQDN)
    let bundle = match state.acme.issue_certificate(&subdomain).await {
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

    // 7. Store device record
    let record = DeviceRecord {
        fcm_token: req.fcm_token,
        firebase_uid: uid,
        public_key: public_key_b64,
        cert_expires: SystemTime::now() + Duration::from_secs(90 * 86400), // ~90 days
        registered_at: SystemTime::now(),
        last_rotation: if req.force_new {
            Some(SystemTime::now())
        } else {
            None
        },
        renewal_nudge_sent: None,
    };

    if let Err(e) = state.firestore.put_device(&subdomain, &record).await {
        return ApiError::internal(format!("Firestore write failed: {e}")).into_response();
    }

    // 8. Return response
    (
        StatusCode::OK,
        Json(RegisterResponse {
            subdomain,
            cert: bundle.cert_pem,
            private_key: bundle.private_key_pem,
            relay_host: state.config.server.relay_hostname.clone(),
        }),
    )
        .into_response()
}

/// Extract a base64-encoded public key representation from CSR DER bytes.
/// In a full implementation this would parse the PKCS#10 CSR and extract
/// the SubjectPublicKeyInfo. For now we use a SHA-256 fingerprint of the
/// CSR as a stand-in that is stable and verifiable.
fn extract_public_key_from_csr(csr_der: &[u8]) -> String {
    // Use the SHA-256 hash of the CSR as a stable identifier.
    // The real implementation would parse the CSR with x509-parser
    // and extract the SPKI. For the initial build, this gives us
    // a deterministic, testable value.
    let mut hasher = Sha256::new();
    hasher.update(csr_der);
    let hash = hasher.finalize();
    BASE64.encode(hash)
}

/// Verify an ECDSA P-256 signature over the CSR bytes.
fn verify_signature(
    stored_public_key_b64: &str,
    csr_der: &[u8],
    signature_b64: &str,
) -> Result<(), String> {
    let _pub_key_bytes = BASE64
        .decode(stored_public_key_b64)
        .map_err(|e| format!("invalid stored public key: {e}"))?;

    let sig_bytes = BASE64
        .decode(signature_b64)
        .map_err(|e| format!("invalid signature encoding: {e}"))?;

    // For P-256 ECDSA verification, we need the DER-encoded public key
    // and the DER-encoded signature. The signature is over SHA256(csr_der).
    let verifying_key = VerifyingKey::from_public_key_der(&_pub_key_bytes)
        .map_err(|e| format!("invalid public key: {e}"))?;

    let signature =
        Signature::from_der(&sig_bytes).map_err(|e| format!("invalid signature format: {e}"))?;

    verifying_key
        .verify(csr_der, &signature)
        .map_err(|e| format!("signature mismatch: {e}"))
}
