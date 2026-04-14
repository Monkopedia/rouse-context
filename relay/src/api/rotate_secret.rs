//! POST /rotate-secret -- Rotate the per-integration secrets for a device.
//!
//! Authenticated via mTLS (the subdomain is extracted from the client cert).
//! Generates one `{adjective}-{integrationId}` secret per requested
//! integration id, replaces Firestore's `valid_secrets` with the full list,
//! refreshes the in-memory SNI cache, and returns the new mapping. The old
//! secrets become invalid as soon as Firestore is updated.

use axum::extract::State;
use axum::http::StatusCode;
use axum::response::{IntoResponse, Response};
use axum::Extension;
use axum::Json;
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::sync::Arc;
use tracing::info;

use crate::api::register::generate_integration_secrets;
use crate::api::ws::DeviceIdentity;
use crate::api::{ApiError, AppState};

#[derive(Debug, Deserialize)]
pub struct RotateSecretRequest {
    /// Subdomain to rotate. In production this is verified against the mTLS
    /// client cert identity; in tests it may be provided directly.
    pub subdomain: String,
    /// Integration IDs to generate fresh secrets for (e.g. `outreach`,
    /// `health`). The relay generates one `{adjective}-{integrationId}` secret
    /// per entry and returns the mapping in [`RotateSecretResponse::secrets`].
    pub integrations: Vec<String>,
}

#[derive(Debug, Serialize)]
pub struct RotateSecretResponse {
    pub success: bool,
    /// Map of integration id → newly generated secret.
    pub secrets: HashMap<String, String>,
}

pub async fn handle_rotate_secret(
    State(state): State<Arc<AppState>>,
    device_identity: Option<Extension<DeviceIdentity>>,
    Json(req): Json<RotateSecretRequest>,
) -> Response {
    // Determine subdomain: prefer mTLS identity, fall back to request body
    let subdomain = if let Some(Extension(identity)) = device_identity {
        identity.subdomain
    } else {
        req.subdomain
    };

    if subdomain.is_empty() {
        return ApiError::bad_request("Missing subdomain").into_response();
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

    // Generate fresh secrets on the relay — the relay is the sole source of
    // truth for the adjective list and the generated values.
    let secrets_map = generate_integration_secrets(&req.integrations);
    let valid_secrets: Vec<String> = secrets_map.values().cloned().collect();

    // Replace Firestore's valid_secrets list with the newly generated values.
    record.valid_secrets = valid_secrets.clone();

    // Update Firestore
    if let Err(e) = state.firestore.put_device(&subdomain, &record).await {
        return ApiError::internal(format!("Firestore write failed: {e}")).into_response();
    }

    // Update the in-memory valid_secrets cache so the next SNI connection for
    // this device does not have to wait for Firestore eventual consistency
    // to learn about the newly-generated secrets.
    state
        .relay_state
        .set_valid_secrets_cache(&subdomain, valid_secrets);

    info!(
        subdomain = %subdomain,
        integrations = ?req.integrations,
        "Secrets rotated"
    );

    (
        StatusCode::OK,
        Json(RotateSecretResponse {
            success: true,
            secrets: secrets_map,
        }),
    )
        .into_response()
}
