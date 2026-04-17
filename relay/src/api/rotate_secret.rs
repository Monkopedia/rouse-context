//! POST /rotate-secret -- Merge-missing rotation of per-integration secrets.
//!
//! Authenticated via mTLS (the subdomain is extracted from the client cert).
//!
//! Behavior:
//! * For each integration id in the request that already has a secret in
//!   `DeviceRecord::integration_secrets`, the existing secret is preserved.
//! * For each integration id not yet in the map, a fresh
//!   `{adjective}-{integrationId}` secret is generated.
//! * `valid_secrets` is rebuilt from the resulting map so the SNI fast path
//!   stays in sync.
//! * The response returns the FULL integration→secret mapping; the client
//!   treats its local store as a server-delivered mirror and replaces it
//!   wholesale.
//!
//! Transitional behavior: a legacy device registered before the
//! `integration_secrets` field existed will hit this handler with an empty
//! map. Every integration in the request will look "missing" and get a new
//! secret — equivalent to the pre-#148 wholesale rotation, one time. After
//! this call the map is populated and subsequent rotations preserve
//! unchanged entries.
//!
//! Note: this handler does NOT drop integrations that disappear from the
//! request. Removing a secret is not a supported operation here — the client
//! calls this endpoint to add-or-refresh, not to trim.

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
    /// Integration IDs the client wants to have secrets for. The relay
    /// generates fresh `{adjective}-{integrationId}` secrets only for IDs
    /// not already present in the stored mapping.
    pub integrations: Vec<String>,
}

#[derive(Debug, Serialize)]
pub struct RotateSecretResponse {
    pub success: bool,
    /// Full integration id → secret mapping after merge-missing. Includes
    /// both preserved existing secrets and newly generated ones so the
    /// client can mirror server-owned state without a local merge step.
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

    // Merge-missing: keep existing secrets, generate fresh ones only for
    // integration ids not yet present in the stored mapping.
    //
    // Scope the ThreadRng to this block so it is dropped before the next
    // `.await`; ThreadRng is `!Send`, so the future would otherwise not be
    // `Send` and couldn't be used with axum's Handler trait.
    let mut merged = record.integration_secrets.clone();
    let mut newly_generated: Vec<String> = Vec::new();
    {
        let mut rng = rand::thread_rng();
        for integration_id in &req.integrations {
            if !merged.contains_key(integration_id) {
                let secret = generate_one_secret(integration_id, &mut rng);
                merged.insert(integration_id.clone(), secret);
                newly_generated.push(integration_id.clone());
            }
        }
    }

    // Persist both shapes: the mapping is authoritative; valid_secrets is
    // derived so the SNI fast path keeps a flat membership list.
    let valid_secrets: Vec<String> = merged.values().cloned().collect();
    record.integration_secrets = merged.clone();
    record.valid_secrets = valid_secrets.clone();

    // Update Firestore
    if let Err(e) = state.firestore.put_device(&subdomain, &record).await {
        return ApiError::internal(format!("Firestore write failed: {e}")).into_response();
    }

    // Update the in-memory valid_secrets cache so the next SNI connection for
    // this device does not have to wait for Firestore eventual consistency.
    state
        .relay_state
        .set_valid_secrets_cache(&subdomain, valid_secrets);

    info!(
        subdomain = %subdomain,
        requested = ?req.integrations,
        newly_generated = ?newly_generated,
        "Secrets merged (merge-missing)"
    );

    (
        StatusCode::OK,
        Json(RotateSecretResponse {
            success: true,
            secrets: merged,
        }),
    )
        .into_response()
}
