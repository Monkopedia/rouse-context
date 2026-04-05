//! GET /ws -- Mux WebSocket upgrade.
//!
//! This is a placeholder for the WebSocket upgrade handler. The actual
//! mux connection management (frame routing, stream lifecycle) will be
//! implemented in a later task. For now, this establishes the route and
//! rejects connections without a valid client certificate.

use axum::extract::State;
use axum::response::{IntoResponse, Response};
use std::sync::Arc;

use crate::api::{ApiError, AppState};

pub async fn handle_ws(State(_state): State<Arc<AppState>>) -> Response {
    // In a real deployment, mTLS middleware would extract the subdomain
    // from the client certificate before this handler runs. Without a
    // valid client cert, we reject.
    //
    // Full WebSocket upgrade + mux framing will be wired in the connection
    // lifecycle task.
    ApiError::unauthorized("Valid client certificate required").into_response()
}
