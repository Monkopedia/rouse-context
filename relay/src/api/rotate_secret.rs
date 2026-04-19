//! POST /rotate-secret -- Replace-wholesale rotation of per-integration
//! secrets.
//!
//! Authenticated via mTLS (the subdomain is extracted from the client cert).
//!
//! Behavior (see #285):
//! * The `integrations` list in the request body is the AUTHORITATIVE set
//!   of integrations the device wants live on the relay.
//! * For each integration id in the request that already has a secret in
//!   `DeviceRecord::integration_secrets`, the existing secret is preserved
//!   (so repeated rotates-with-same-set are idempotent and don't invalidate
//!   live client URLs).
//! * For each integration id in the request not yet in the stored map, a
//!   fresh `{adjective}-{integrationId}` secret is generated.
//! * For each integration id in the stored map but NOT in the request, the
//!   entry is REMOVED. This is the fix for #285: disabling an integration
//!   on-device must be able to invalidate its secret on the relay.
//! * `valid_secrets` is rebuilt from the resulting map so the SNI fast path
//!   stays in sync. An empty request list legitimately produces an empty
//!   valid-secrets map and evicts the cache entry (device stays onboarded —
//!   subdomain, account, cert, etc. are untouched).
//! * The response returns the FULL integration→secret mapping; the client
//!   treats its local store as a server-delivered mirror and replaces it
//!   wholesale.
//!
//! Transitional behavior: a legacy device registered before the
//! `integration_secrets` field existed will hit this handler with an empty
//! map. Every integration in the request will look "missing" and get a new
//! secret — equivalent to the pre-#148 wholesale rotation, one time. After
//! this call the map matches the request exactly.
//!
//! Security note: replace-wholesale means this endpoint can invalidate
//! every active client URL for a device in one call. The mTLS identity
//! requirement (#202) ensures only the device itself can trigger this.

use axum::extract::State;
use axum::http::StatusCode;
use axum::response::{IntoResponse, Response};
use axum::Extension;
use axum::Json;
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::sync::Arc;
use tracing::info;

use crate::api::register::generate_one_secret;
use crate::api::ws::DeviceIdentity;
use crate::api::{ApiError, AppState};

#[derive(Debug, Deserialize)]
pub struct RotateSecretRequest {
    /// Integration IDs the client wants to have secrets for. This is the
    /// AUTHORITATIVE set (#285): the relay preserves existing secrets for
    /// IDs that remain in this list, mints fresh secrets for new IDs, and
    /// DROPS any previously stored secret whose id is not in this list.
    /// An empty list is valid and invalidates every active per-integration
    /// URL for this device.
    pub integrations: Vec<String>,
}

#[derive(Debug, Serialize)]
pub struct RotateSecretResponse {
    pub success: bool,
    /// Full integration id → secret mapping after replace-wholesale.
    /// Exactly matches the request's integration set. The client mirrors
    /// this wholesale into its local store.
    pub secrets: HashMap<String, String>,
}

pub async fn handle_rotate_secret(
    State(state): State<Arc<AppState>>,
    device_identity: Option<Extension<DeviceIdentity>>,
    Json(req): Json<RotateSecretRequest>,
) -> Response {
    // Subdomain is ALWAYS taken from the mTLS client cert identity.
    // There is no body fallback: rotating a device's secrets is a privileged
    // operation, and accepting a subdomain from an unauthenticated request
    // body turns this endpoint into a one-shot DoS of every registered
    // integration. See issue #202.
    let subdomain = match device_identity {
        Some(Extension(identity)) => identity.subdomain,
        None => {
            return ApiError::unauthorized("Valid client certificate required").into_response();
        }
    };

    if subdomain.is_empty() {
        return ApiError::unauthorized("Client certificate missing subdomain").into_response();
    }

    // Verify device exists
    let mut record = match state.firestore.get_device(&subdomain).await {
        Ok(r) => r,
        Err(crate::firestore::FirestoreError::NotFound(_)) => {
            return ApiError::not_found("Device not found").into_response()
        }
        Err(e) => {
            return ApiError::internal(format!("Firestore lookup failed: {e}")).into_response()
        }
    };

    // Replace-wholesale (#285): the request's integration list is the
    // authoritative set. Preserve secrets for integrations carried over
    // from the previous map (idempotent for unchanged IDs), mint fresh
    // secrets for new IDs, and DROP any previously stored secret whose
    // integration id is not in the request.
    //
    // Scope the ThreadRng to this block so it is dropped before the next
    // `.await`; ThreadRng is `!Send`, so the future would otherwise not be
    // `Send` and couldn't be used with axum's Handler trait.
    let previous = record.integration_secrets.clone();
    let mut replaced: HashMap<String, String> = HashMap::with_capacity(req.integrations.len());
    let mut newly_generated: Vec<String> = Vec::new();
    {
        let mut rng = rand::thread_rng();
        for integration_id in &req.integrations {
            if let Some(existing) = previous.get(integration_id) {
                replaced.insert(integration_id.clone(), existing.clone());
            } else {
                let secret = generate_one_secret(integration_id, &mut rng);
                replaced.insert(integration_id.clone(), secret);
                newly_generated.push(integration_id.clone());
            }
        }
    }
    let removed: Vec<String> = previous
        .keys()
        .filter(|id| !replaced.contains_key(id.as_str()))
        .cloned()
        .collect();

    // Persist both shapes: the mapping is authoritative; valid_secrets is
    // derived so the SNI fast path keeps a flat membership list.
    let valid_secrets: Vec<String> = replaced.values().cloned().collect();
    record.integration_secrets = replaced.clone();
    record.valid_secrets = valid_secrets.clone();

    // Update Firestore
    if let Err(e) = state.firestore.put_device(&subdomain, &record).await {
        return ApiError::internal(format!("Firestore write failed: {e}")).into_response();
    }

    // Update the in-memory valid_secrets cache so the next SNI connection for
    // this device does not have to wait for Firestore eventual consistency.
    // `set_valid_secrets_cache` evicts the entry entirely when the vec is
    // empty, which is the correct behavior for a wholesale-to-empty rotate
    // (every previously live URL becomes invalid immediately).
    state
        .relay_state
        .set_valid_secrets_cache(&subdomain, valid_secrets);

    info!(
        subdomain = %subdomain,
        requested = ?req.integrations,
        newly_generated = ?newly_generated,
        removed = ?removed,
        "Secrets replaced (replace-wholesale, #285)"
    );

    (
        StatusCode::OK,
        Json(RotateSecretResponse {
            success: true,
            secrets: replaced,
        }),
    )
        .into_response()
}
