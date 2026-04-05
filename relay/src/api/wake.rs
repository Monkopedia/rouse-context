//! POST /wake/:subdomain -- Pre-flight wakeup.

use axum::extract::{Path, State};
use axum::http::StatusCode;
use axum::response::{IntoResponse, Response};
use axum::Json;
use serde::Serialize;
use std::sync::atomic::Ordering;
use std::sync::Arc;
use std::time::Duration;

use crate::api::{ApiError, AppState};
use crate::fcm;

#[derive(Debug, Serialize)]
pub struct WakeResponse {
    pub status: String,
}

pub async fn handle_wake(
    State(state): State<Arc<AppState>>,
    Path(subdomain): Path<String>,
) -> Response {
    // 1. Validate subdomain format
    if subdomain.is_empty() || subdomain.len() > 63 {
        return ApiError::bad_request("Invalid subdomain").into_response();
    }

    // 2. Check rate limit
    if let Err(retry_after) = state.rate_limiter.try_acquire(&subdomain) {
        return ApiError::rate_limited("Rate limit exceeded", retry_after).into_response();
    }

    // 3. Look up device in Firestore
    let device = match state.firestore.get_device(&subdomain).await {
        Ok(d) => d,
        Err(crate::firestore::FirestoreError::NotFound(_)) => {
            return ApiError::not_found("Device not found").into_response();
        }
        Err(e) => {
            return ApiError::internal(format!("Firestore lookup failed: {e}")).into_response();
        }
    };

    // 4. Check if device is already online
    if state.relay_state.is_device_online(&subdomain) {
        return (
            StatusCode::OK,
            Json(WakeResponse {
                status: "ready".to_string(),
            }),
        )
            .into_response();
    }

    // 5. Send FCM wake message
    let payload = fcm::wake_payload(&state.config.server.relay_hostname);
    if let Err(e) = state
        .fcm
        .send_data_message(&device.fcm_token, &payload, true)
        .await
    {
        return ApiError::internal(format!("Failed to send wakeup notification: {e}"))
            .into_response();
    }

    // 6. Wait for device to connect (up to timeout)
    state
        .relay_state
        .pending_fcm_wakeups
        .fetch_add(1, Ordering::Relaxed);
    let mut rx = state.relay_state.subscribe_connect(&subdomain);

    let timeout_secs = 20; // TODO: from config
    let result = tokio::time::timeout(Duration::from_secs(timeout_secs), rx.recv()).await;

    state
        .relay_state
        .pending_fcm_wakeups
        .fetch_sub(1, Ordering::Relaxed);

    match result {
        Ok(Ok(())) => (
            StatusCode::OK,
            Json(WakeResponse {
                status: "ready".to_string(),
            }),
        )
            .into_response(),
        _ => ApiError::timeout("Device did not connect within timeout").into_response(),
    }
}
