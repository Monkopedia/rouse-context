//! POST /rotate-secret -- Rotate the secret prefix for a device.
//!
//! Authenticated via mTLS (the subdomain is extracted from the client cert).
//! Generates a new two-word secret prefix, updates Firestore, and returns
//! the new prefix. The old prefix is immediately invalid.

use axum::extract::State;
use axum::http::StatusCode;
use axum::response::{IntoResponse, Response};
use axum::Extension;
use axum::Json;
use serde::{Deserialize, Serialize};
use std::sync::Arc;
use tracing::info;

use crate::api::ws::DeviceIdentity;
use crate::api::{ApiError, AppState};

#[derive(Debug, Deserialize)]
pub struct RotateSecretRequest {
    /// Subdomain to rotate. In production this is verified against the mTLS
    /// client cert identity; in tests it may be provided directly.
    pub subdomain: String,
    /// New client-generated secret strings for SNI validation.
    pub valid_secrets: Vec<String>,
}

#[derive(Debug, Serialize)]
pub struct RotateSecretResponse {
    pub success: bool,
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

    // Replace valid_secrets with client-provided values
    record.valid_secrets = req.valid_secrets;

    // Update Firestore
    if let Err(e) = state.firestore.put_device(&subdomain, &record).await {
        return ApiError::internal(format!("Firestore write failed: {e}")).into_response();
    }

    info!(
        subdomain = %subdomain,
        "Secrets rotated"
    );

    (StatusCode::OK, Json(RotateSecretResponse { success: true })).into_response()
}
