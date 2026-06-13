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
use std::collections::HashMap;
use std::sync::Arc;
use std::time::{Duration, SystemTime};

use crate::api::{ApiError, AppState};
use crate::firestore::{DeviceRecord, PushKind};
use crate::words::adjectives;

/// Round 1: Register a device and receive a subdomain assignment.
///
/// Two auth variants (issue #462):
/// - **Firebase** (`google` flavor): `firebase_token` is present and is the
///   sole authenticator; the UID becomes the Firestore foreign key.
/// - **Keypair** (`foss` flavor): `firebase_token` is absent and the request
///   instead carries `public_key` + a signed proof (`auth_timestamp`,
///   `auth_nonce`, `auth_signature`). The relay verifies the proof with the
///   supplied key and keys the device on the key thumbprint. See
///   [`crate::keypair_auth`].
#[derive(Debug, Deserialize)]
pub struct RegisterRequest {
    /// Firebase ID token (google flavor). Absent on the keypair path.
    #[serde(default)]
    pub firebase_token: Option<String>,
    /// FCM registration token (`google` flavor). Empty/absent for `foss`
    /// devices, which supply [`Self::push_endpoint`] instead.
    #[serde(default)]
    pub fcm_token: String,
    /// UnifiedPush endpoint URL (`foss` flavor). Accepted in lieu of
    /// `fcm_token`; foss devices have no FCM token (issues #463, #469, #470).
    #[serde(default)]
    pub push_endpoint: Option<String>,
    pub signature: Option<String>,
    #[serde(default)]
    pub force_new: bool,
    /// Integration IDs the device wants secrets generated for (e.g. `outreach`,
    /// `health`). The relay generates one `{adjective}-{integrationId}` secret
    /// per entry and returns the mapping in [`RegisterResponse::secrets`].
    #[serde(default)]
    pub integrations: Vec<String>,

    // ── Keypair-auth variant (foss flavor, issue #462) ──
    /// Base64-encoded DER SubjectPublicKeyInfo (ECDSA P-256) of the device key.
    #[serde(default)]
    pub public_key: Option<String>,
    /// Unix-seconds client timestamp the proof was signed at.
    #[serde(default)]
    pub auth_timestamp: Option<i64>,
    /// Opaque client nonce included in the signed proof.
    #[serde(default)]
    pub auth_nonce: Option<String>,
    /// Base64-encoded DER ECDSA signature over the canonical proof message
    /// (see [`crate::keypair_auth::canonical_message`]).
    #[serde(default)]
    pub auth_signature: Option<String>,
}

/// Round 1 response: subdomain assigned, device should now generate CSR.
#[derive(Debug, Serialize)]
pub struct RegisterResponse {
    pub subdomain: String,
    pub relay_host: String,
    /// Map of integration id → generated secret in the form
    /// `{adjective}-{integrationId}`. Empty when the request did not list any
    /// integrations.
    #[serde(default, skip_serializing_if = "HashMap::is_empty")]
    pub secrets: HashMap<String, String>,
}

/// Generate one `{adjective}-{integrationId}` secret per integration id.
///
/// The map is keyed by integration id; the values become the device's
/// `valid_secrets` entries in Firestore and the SNI cache.
pub(crate) fn generate_integration_secrets(integrations: &[String]) -> HashMap<String, String> {
    let mut rng = rand::thread_rng();
    integrations
        .iter()
        .map(|id| (id.clone(), generate_one_secret(id, &mut rng)))
        .collect()
}

/// Generate a single `{adjective}-{integrationId}` secret.
pub(crate) fn generate_one_secret(integration_id: &str, rng: &mut impl rand::Rng) -> String {
    let adjective = adjectives::pick_random(rng);
    format!("{adjective}-{integration_id}")
}

/// Round 2: Submit CSR to receive certificates.
///
/// `firebase_token` is present for the `google` flavor (device looked up by
/// UID) and absent for the `foss` keypair flavor, where the device is looked up
/// by the thumbprint of the public key embedded in the CSR. See issue #462.
#[derive(Debug, Deserialize)]
pub struct CertRequest {
    #[serde(default)]
    pub firebase_token: Option<String>,
    /// Base64-encoded DER CSR with correct FQDN (subdomain.rousecontext.com).
    pub csr: String,
    /// Base64-encoded DER ECDSA signature over the raw CSR DER bytes, using
    /// the device's registered private key. Required when the device record
    /// already has a public key on file (re-registration / proof-of-possession
    /// path). Ignored on fresh registrations where `record.public_key` is
    /// empty. See issue #201.
    #[serde(default)]
    pub signature: Option<String>,
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

/// The authenticated identity behind a `/register` request, after auth has
/// been verified. Abstracts the two flavors so the rest of the handler is
/// auth-agnostic. See issue #462.
enum RegisterIdentity {
    /// `google` flavor: keyed on the Firebase UID. Carries the raw token so the
    /// re-registration proof (signature over the token bytes) can be checked.
    Firebase { uid: String, token: String },
    /// `foss` flavor: keyed on the public-key thumbprint. Possession of the key
    /// was already proven by the verified registration proof, so no separate
    /// re-registration signature is required.
    Keypair { thumbprint: String },
}

/// Resolve a device's push target from a `/register` request.
///
/// Accepts EITHER a non-empty `fcm_token` (→ [`PushKind::Fcm`]) OR a non-empty
/// UnifiedPush `push_endpoint` (→ [`PushKind::UnifiedPush`]); `fcm_token` wins
/// if both are present (preserving existing `google` behavior). Returns the
/// chosen `(push_kind, fcm_token, push_endpoint)` tuple, or an error message
/// when neither is supplied. Closes the #469/#470 gap where `foss` devices —
/// which have no FCM token — could not register.
fn resolve_register_push_target(
    fcm_token: &str,
    push_endpoint: Option<&str>,
) -> Result<(PushKind, String, String), &'static str> {
    if !fcm_token.is_empty() {
        return Ok((PushKind::Fcm, fcm_token.to_string(), String::new()));
    }
    if let Some(endpoint) = push_endpoint.filter(|e| !e.is_empty()) {
        return Ok((PushKind::UnifiedPush, String::new(), endpoint.to_string()));
    }
    Err("Provide either a non-empty fcm_token or a non-empty push_endpoint")
}

/// Current Unix time in whole seconds (saturating at 0 before the epoch).
fn now_unix_secs() -> i64 {
    SystemTime::now()
        .duration_since(SystemTime::UNIX_EPOCH)
        .map(|d| d.as_secs() as i64)
        .unwrap_or(0)
}

/// Resolve and verify the caller's identity from the request. Returns the
/// verified [`RegisterIdentity`] or a ready-to-return error response.
async fn resolve_register_identity(
    state: &Arc<AppState>,
    req: &RegisterRequest,
) -> Result<RegisterIdentity, Response> {
    // Firebase path takes precedence when a token is present.
    if let Some(token) = req.firebase_token.as_deref().filter(|t| !t.is_empty()) {
        let claims = state
            .firebase_auth
            .verify_id_token(token)
            .await
            .map_err(|e| {
                ApiError::unauthorized(format!("Invalid Firebase ID token: {e}")).into_response()
            })?;
        return Ok(RegisterIdentity::Firebase {
            uid: claims.uid,
            token: token.to_string(),
        });
    }

    // Keypair path (foss): public key + signed proof.
    let public_key_b64 = req
        .public_key
        .as_deref()
        .filter(|s| !s.is_empty())
        .ok_or_else(|| {
            ApiError::bad_request(
                "Missing auth: provide firebase_token, or public_key + auth proof",
            )
            .into_response()
        })?;
    let public_key_der = BASE64
        .decode(public_key_b64)
        .map_err(|_| ApiError::bad_request("Invalid Base64 in public_key field").into_response())?;
    let timestamp = req.auth_timestamp.ok_or_else(|| {
        ApiError::bad_request("Missing required field: auth_timestamp").into_response()
    })?;
    let nonce = req.auth_nonce.as_deref().ok_or_else(|| {
        ApiError::bad_request("Missing required field: auth_nonce").into_response()
    })?;
    let signature = req.auth_signature.as_deref().ok_or_else(|| {
        ApiError::bad_request("Missing required field: auth_signature").into_response()
    })?;

    let thumbprint = crate::keypair_auth::verify_proof(
        &public_key_der,
        crate::keypair_auth::PURPOSE_REGISTER,
        timestamp,
        nonce,
        signature,
        now_unix_secs(),
    )
    .map_err(|e| ApiError::forbidden(format!("Keypair proof rejected: {e}")).into_response())?;

    Ok(RegisterIdentity::Keypair { thumbprint })
}

/// Round 1: POST /register
///
/// Device authenticates with either a Firebase ID token (`google`) or a keypair
/// proof (`foss`), plus an fcm_token. Relay assigns a subdomain and stores the
/// device record. The device then generates a keypair + CSR with the assigned
/// subdomain and calls POST /register/certs.
pub async fn handle_register(
    State(state): State<Arc<AppState>>,
    Json(req): Json<RegisterRequest>,
) -> Response {
    // 1. Resolve the push target: accept EITHER an FCM token (`google`) OR a
    //    UnifiedPush endpoint (`foss`). At least one is required.
    let (push_kind, fcm_token, push_endpoint) =
        match resolve_register_push_target(&req.fcm_token, req.push_endpoint.as_deref()) {
            Ok(target) => target,
            Err(msg) => return ApiError::bad_request(msg).into_response(),
        };

    // 2. Resolve + verify the caller's identity (Firebase token or keypair proof).
    let identity = match resolve_register_identity(&state, &req).await {
        Ok(id) => id,
        Err(resp) => return resp,
    };

    // 3. Check if this identity already has a subdomain.
    let existing = match &identity {
        RegisterIdentity::Firebase { uid, .. } => state.firestore.find_device_by_uid(uid).await,
        RegisterIdentity::Keypair { thumbprint } => {
            state.firestore.find_device_by_thumbprint(thumbprint).await
        }
    };
    let existing = match existing {
        Ok(existing) => existing,
        Err(e) => {
            return ApiError::internal(format!("Firestore lookup failed: {e}")).into_response()
        }
    };

    let subdomain = if let Some((existing_subdomain, existing_record)) = existing {
        // Re-registration or rotation. For Firebase, a signature over the token
        // bytes proves key possession (the token alone is bearer-only). For the
        // keypair path, the registration proof already proved possession.
        if let RegisterIdentity::Firebase { token, .. } = &identity {
            let sig_b64 = match &req.signature {
                Some(s) if !s.is_empty() => s,
                _ => {
                    return ApiError::forbidden("Signature required for re-registration")
                        .into_response()
                }
            };
            if let Err(e) = verify_signature(&existing_record.public_key, token.as_bytes(), sig_b64)
            {
                return ApiError::forbidden(format!("Signature verification failed: {e}"))
                    .into_response();
            }
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
        // New registration. The reservation flow (POST /request-subdomain) is
        // Firebase-only: it keys reservations on the UID. Keypair (foss) devices
        // skip reservation and go straight to generation.
        //
        // If this UID holds an active (non-expired) reservation, consume it:
        // adopt the reserved subdomain and delete the reservation (single-use).
        // Otherwise, fall through to the generator path.
        //
        // Errors looking up or deleting reservations are non-fatal: we log
        // and fall back to generation so /register never fails just because
        // the reservation bookkeeping had a hiccup.
        let uid = match &identity {
            RegisterIdentity::Firebase { uid, .. } => Some(uid.as_str()),
            RegisterIdentity::Keypair { .. } => None,
        };
        match uid {
            None => state.subdomain_generator.generate(),
            Some(uid) => match state.firestore.find_reservation_by_uid(uid).await {
                Ok(Some((reserved_subdomain, reservation)))
                    if reservation.expires_at > SystemTime::now() =>
                {
                    if let Err(e) = state
                        .firestore
                        .delete_reservation(&reserved_subdomain)
                        .await
                    {
                        tracing::warn!(
                            subdomain = %reserved_subdomain,
                            error = %e,
                            "Failed to delete consumed reservation (non-fatal)"
                        );
                    }
                    reserved_subdomain
                }
                Ok(Some((expired_subdomain, _))) => {
                    // Expired reservation: best-effort cleanup, then generate.
                    if let Err(e) = state.firestore.delete_reservation(&expired_subdomain).await {
                        tracing::warn!(
                            subdomain = %expired_subdomain,
                            error = %e,
                            "Failed to delete expired reservation (non-fatal)"
                        );
                    }
                    state.subdomain_generator.generate()
                }
                Ok(None) => state.subdomain_generator.generate(),
                Err(e) => {
                    tracing::warn!(
                        error = %e,
                        "Reservation lookup failed; falling back to generator"
                    );
                    state.subdomain_generator.generate()
                }
            },
        }
    };

    // 4. Generate one secret per requested integration id. The relay is the
    //    sole source of truth for the adjective list and generated values.
    //    Persist both the integration-keyed map (authoritative for merge-missing
    //    rotation) and the flat list used by the SNI fast path.
    let secrets_map = generate_integration_secrets(&req.integrations);
    let valid_secrets: Vec<String> = secrets_map.values().cloned().collect();

    // 5. Store device record (public_key will be set in round 2 when CSR
    //    arrives — for the keypair path the round-2 CSR's public key is matched
    //    to this record by its thumbprint).
    let (firebase_uid, key_thumbprint) = match identity {
        RegisterIdentity::Firebase { uid, .. } => (uid, None),
        RegisterIdentity::Keypair { thumbprint } => (String::new(), Some(thumbprint)),
    };
    let record = DeviceRecord {
        fcm_token,
        firebase_uid,
        key_thumbprint,
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
        push_kind,
        push_endpoint,
        valid_secrets: valid_secrets.clone(),
        integration_secrets: secrets_map.clone(),
    };

    if let Err(e) = state.firestore.put_device(&subdomain, &record).await {
        return ApiError::internal(format!("Firestore write failed: {e}")).into_response();
    }

    // Seed the in-memory valid_secrets cache so SNI routing picks up freshly
    // registered secrets without waiting for Firestore eventual consistency.
    state
        .relay_state
        .set_valid_secrets_cache(&subdomain, valid_secrets);

    // 6. Return subdomain, relay host, and the generated secrets map
    (
        StatusCode::OK,
        Json(RegisterResponse {
            subdomain,
            relay_host: state.config.server.relay_hostname.clone(),
            secrets: secrets_map,
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
    if req.csr.is_empty() {
        return ApiError::bad_request("Missing required field: csr").into_response();
    }

    // 2. Decode CSR
    let csr_der = match BASE64.decode(&req.csr) {
        Ok(bytes) => bytes,
        Err(_) => return ApiError::bad_request("Invalid Base64 in csr field").into_response(),
    };

    // 3. Resolve the device + subdomain by identity.
    //
    // Firebase (google): verify the token and look up by UID. Keypair (foss):
    // no token; the device is identified by the thumbprint of the public key in
    // the CSR. The thumbprint match against the round-1 record establishes
    // identity, and the CSR self-signature (verified below) proves possession.
    let (subdomain, record) = if let Some(token) =
        req.firebase_token.as_deref().filter(|t| !t.is_empty())
    {
        let claims = match state.firebase_auth.verify_id_token(token).await {
            Ok(c) => c,
            Err(e) => {
                return ApiError::unauthorized(format!("Invalid Firebase ID token: {e}"))
                    .into_response()
            }
        };
        match state.firestore.find_device_by_uid(&claims.uid).await {
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
        }
    } else {
        // Keypair path needs the CSR's public key to derive the identity.
        let public_key_der = match crate::device_ca::extract_public_key_from_csr_der(&csr_der) {
            Ok(spki) => spki,
            Err(e) => {
                return ApiError::bad_request(format!("Failed to parse CSR: {e}")).into_response()
            }
        };
        let thumbprint = crate::keypair_auth::thumbprint(&public_key_der);
        match state.firestore.find_device_by_thumbprint(&thumbprint).await {
            Ok(Some(pair)) => pair,
            Ok(None) => {
                return ApiError::not_found(
                    "No device registered for this key. Call POST /register first.",
                )
                .into_response()
            }
            Err(e) => {
                return ApiError::internal(format!("Firestore lookup failed: {e}")).into_response()
            }
        }
    };

    // 4. Extract the CSR public key (also verifies the CSR self-signature). For
    //    the Firebase path this is where a malformed CSR is rejected — after the
    //    device lookup, preserving the prior ordering.
    let public_key_der = match crate::device_ca::extract_public_key_from_csr_der(&csr_der) {
        Ok(spki) => spki,
        Err(e) => {
            return ApiError::bad_request(format!("Failed to parse CSR: {e}")).into_response()
        }
    };
    let public_key_b64 = BASE64.encode(&public_key_der);

    // 4a. Proof-of-possession check (issue #201).
    //
    // If the device record already has a public key on file — i.e. a prior
    // /register/certs round 2 has completed — require the caller to sign the
    // raw CSR DER bytes with the registered private key. This prevents an
    // attacker who only holds a valid Firebase ID token from re-issuing a
    // relay-CA client cert with an attacker-controlled key. (On the keypair
    // path the CSR thumbprint already had to match the registered identity, so
    // this is belt-and-suspenders, but it stays uniform across both flavors.)
    //
    // Fresh registrations (record.public_key empty) still work without a
    // signature: round 2 is the step that binds the key for the first time.
    let mut record = record;
    if !record.public_key.is_empty() {
        let sig_b64 = match req.signature.as_deref() {
            Some(s) if !s.is_empty() => s,
            _ => {
                return ApiError::forbidden(
                    "Signature required: device already has a registered key. \
                     Sign the CSR DER with the registered private key, or use /renew.",
                )
                .into_response()
            }
        };
        if let Err(e) = verify_signature(&record.public_key, &csr_der, sig_b64) {
            return ApiError::forbidden(format!("Signature verification failed: {e}"))
                .into_response();
        }
    }

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

    let client_bundle = match device_ca.sign_client_cert(&public_key_der, &subdomain, &base_domain)
    {
        Ok(b) => b,
        Err(e) => {
            return ApiError::internal(format!("Failed to sign client cert: {e}")).into_response()
        }
    };

    // 8. Update device record with public key and cert expiry
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

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn push_target_prefers_fcm_token() {
        let (kind, fcm, ep) =
            resolve_register_push_target("fcm-tok", Some("https://ntfy.sh/x")).unwrap();
        assert_eq!(kind, PushKind::Fcm);
        assert_eq!(fcm, "fcm-tok");
        assert!(ep.is_empty());
    }

    #[test]
    fn push_target_accepts_endpoint_without_fcm_token() {
        let (kind, fcm, ep) =
            resolve_register_push_target("", Some("https://ntfy.sh/up123")).unwrap();
        assert_eq!(kind, PushKind::UnifiedPush);
        assert!(fcm.is_empty());
        assert_eq!(ep, "https://ntfy.sh/up123");
    }

    #[test]
    fn push_target_rejects_when_neither_present() {
        assert!(resolve_register_push_target("", None).is_err());
        assert!(resolve_register_push_target("", Some("")).is_err());
    }
}
