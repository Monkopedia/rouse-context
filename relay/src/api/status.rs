//! GET /status -- Health and metrics endpoint.

use axum::extract::State;
use axum::http::StatusCode;
use axum::response::{IntoResponse, Response};
use axum::Json;
use serde::Serialize;
use std::sync::atomic::Ordering;
use std::sync::Arc;

use crate::api::AppState;

#[derive(Debug, Serialize)]
pub struct StatusResponse {
    pub uptime_secs: u64,
    pub active_mux_connections: u32,
    pub active_streams: u32,
    pub total_sessions_served: u64,
    pub pending_fcm_wakeups: u32,
}

pub async fn handle_status(State(state): State<Arc<AppState>>) -> Response {
    let resp = StatusResponse {
        uptime_secs: state.relay_state.uptime_secs(),
        active_mux_connections: state.relay_state.active_mux_count(),
        active_streams: state.relay_state.active_stream_count(),
        total_sessions_served: state
            .relay_state
            .total_sessions_served
            .load(Ordering::Relaxed),
        pending_fcm_wakeups: state
            .relay_state
            .pending_fcm_wakeups
            .load(Ordering::Relaxed),
    };
    (StatusCode::OK, Json(resp)).into_response()
}
