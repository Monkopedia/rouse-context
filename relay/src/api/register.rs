//! POST /register -- Device registration (round 1: assign subdomain).
//! POST /register/certs -- Certificate issuance (round 2: device sends CSR).

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
use crate::firestore::DeviceRecord;

/// Round 1: Register a device and receive a subdomain assignment.
#[derive(Debug, Deserialize)]
pub struct RegisterRequest {
    pub firebase_token: String,
    pub fcm_token: String,
    pub signature: Option<String>,
    #[serde(default)]
    pub force_new: bool,
    /// Client-generated secret strings for SNI validation.
    #[serde(default)]
    pub valid_secrets: Vec<String>,
}

/// Round 1 response: subdomain assigned, device should now generate CSR.
#[derive(Debug, Serialize)]
pub struct RegisterResponse {
    pub subdomain: String,
    pub relay_host: String,
}

/// Round 2: Submit CSR to receive certificates.
#[derive(Debug, Deserialize)]
pub struct CertRequest {
    pub firebase_token: String,
    /// Base64-encoded DER CSR with correct FQDN (subdomain.rousecontext.com).
    pub csr: String,
}

/// Round 2 response: both certificates, no private key.
#[derive(Debug, Serialize)]
pub struct CertResponse {
    pub subdomain: String,
    /// PEM-encoded ACME server certificate (Let's Encrypt, serverAuth).
    pub server_cert: String,
    /// PEM-encoded relay CA client certificate (clientAuth).
    pub client_cert: String,
    /// PEM-encoded relay CA root certificate.
    pub relay_ca_cert: String,
    pub relay_host: String,
}

/// Round 1: POST /register
///
/// Device sends firebase_token + fcm_token. Relay assigns a subdomain and
/// stores the device record. The device then generates a keypair + CSR with
/// the assigned subdomain and calls POST /register/certs.
pub async fn handle_register(
    State(state): State<Arc<AppState>>,
    Json(req): Json<RegisterRequest>,
) -> Response {
    // 1. Validate required fields
    if req.firebase_token.is_empty() {
        return ApiError::bad_request("Missing required field: firebase_token").into_response();
    }
    if req.fcm_token.is_empty() {
        return ApiError::bad_request("Missing required field: fcm_token").into_response();
    }

    // 2. Verify Firebase ID token
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

    // 3. Check if UID already has a subdomain
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

        // Verify signature over the firebase_token bytes
        if let Err(e) = verify_signature(
            &existing_record.public_key,
            req.firebase_token.as_bytes(),
            sig_b64,
        ) {
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

            // Delete old subdomain's DNS records (best-effort, log but don't block)
            if let Err(e) = state
                .dns
                .delete_subdomain_records(&existing_subdomain)
                .await
            {
                tracing::warn!(
                    old_subdomain = %existing_subdomain,
                    error = %e,
                    "Failed to delete old DNS records during rotation (non-fatal)"
                );
            }

            // Delete old Firestore device record
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

    // 4. Store device record (public_key will be set in round 2 when CSR arrives)
    let record = DeviceRecord {
        fcm_token: req.fcm_token,
        firebase_uid: uid,
        public_key: String::new(),            // set in round 2
        cert_expires: SystemTime::UNIX_EPOCH, // set in round 2
        registered_at: SystemTime::now(),
        last_rotation: if req.force_new {
            Some(SystemTime::now())
        } else {
            None
        },
        renewal_nudge_sent: None,
        secret_prefix: None,
        valid_secrets: req.valid_secrets,
    };

    if let Err(e) = state.firestore.put_device(&subdomain, &record).await {
        return ApiError::internal(format!("Firestore write failed: {e}")).into_response();
    }

    // 5. Return subdomain and relay host
    (
        StatusCode::OK,
        Json(RegisterResponse {
            subdomain,
            relay_host: state.config.server.relay_hostname.clone(),
        }),
    )
        .into_response()
}

/// Round 2: POST /register/certs
///
/// Device sends its CSR (containing its public key with the correct FQDN).
/// Relay issues an ACME server cert and a relay CA client cert, both using
/// the device's public key.
pub async fn handle_register_certs(
    State(state): State<Arc<AppState>>,
    Json(req): Json<CertRequest>,
) -> Response {
    // 1. Validate required fields
    if req.firebase_token.is_empty() {
        return ApiError::bad_request("Missing required field: firebase_token").into_response();
    }
    if req.csr.is_empty() {
        return ApiError::bad_request("Missing required field: csr").into_response();
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

    // 4. Look up existing device by UID
    let (subdomain, _record) = match state.firestore.find_device_by_uid(&uid).await {
        Ok(Some(pair)) => pair,
        Ok(None) => {
            return ApiError::not_found(
                "No device registered for this UID. Call POST /register first.",
            )
            .into_response()
        }
        Err(e) => {
            return ApiError::internal(format!("Firestore lookup failed: {e}")).into_response()
        }
    };

    // 5. Extract public key from CSR
    let public_key_der = match crate::device_ca::extract_public_key_from_csr_der(&csr_der) {
        Ok(spki) => spki,
        Err(e) => {
            return ApiError::bad_request(format!("Failed to parse CSR: {e}")).into_response()
        }
    };

    // Store the public key fingerprint for future re-registration verification
    let public_key_b64 = BASE64.encode(&public_key_der);

    // 6. Issue ACME server cert using the device's CSR
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

    // 7. Issue relay CA client cert using the device's public key
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

    // 8. Update device record with public key and cert expiry
    let mut record = _record;
    record.public_key = public_key_b64;
    record.cert_expires = SystemTime::now() + Duration::from_secs(90 * 86400);

    if let Err(e) = state.firestore.put_device(&subdomain, &record).await {
        return ApiError::internal(format!("Firestore write failed: {e}")).into_response();
    }

    // 9. Return both certs
    (
        StatusCode::OK,
        Json(CertResponse {
            subdomain,
            server_cert: acme_bundle.cert_pem,
            client_cert: client_bundle.cert_pem,
            relay_ca_cert: device_ca.ca_cert_pem().to_string(),
            relay_host: state.config.server.relay_hostname.clone(),
        }),
    )
        .into_response()
}

/// Verify an ECDSA P-256 signature over the given data.
fn verify_signature(
    stored_public_key_b64: &str,
    data: &[u8],
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
        .verify(data, &signature)
        .map_err(|e| format!("signature mismatch: {e}"))
}
