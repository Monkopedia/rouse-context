//! HTTP API endpoints for the relay server.
//!
//! All endpoints are served over HTTPS on `relay.rousecontext.com`.

pub mod register;
pub mod renew;
pub mod status;
pub mod wake;
pub mod ws;

use axum::http::StatusCode;
use axum::response::{IntoResponse, Response};
use axum::Json;
use serde::Serialize;

/// Standard error response envelope.
#[derive(Debug, Clone, Serialize)]
pub struct ApiError {
    pub error: String,
    pub message: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub retry_after_secs: Option<u64>,
}

impl ApiError {
    pub fn bad_request(message: impl Into<String>) -> (StatusCode, Json<ApiError>) {
        (
            StatusCode::BAD_REQUEST,
            Json(ApiError {
                error: "bad_request".to_string(),
                message: message.into(),
                retry_after_secs: None,
            }),
        )
    }

    pub fn unauthorized(message: impl Into<String>) -> (StatusCode, Json<ApiError>) {
        (
            StatusCode::UNAUTHORIZED,
            Json(ApiError {
                error: "unauthorized".to_string(),
                message: message.into(),
                retry_after_secs: None,
            }),
        )
    }

    pub fn forbidden(message: impl Into<String>) -> (StatusCode, Json<ApiError>) {
        (
            StatusCode::FORBIDDEN,
            Json(ApiError {
                error: "forbidden".to_string(),
                message: message.into(),
                retry_after_secs: None,
            }),
        )
    }

    pub fn not_found(message: impl Into<String>) -> (StatusCode, Json<ApiError>) {
        (
            StatusCode::NOT_FOUND,
            Json(ApiError {
                error: "not_found".to_string(),
                message: message.into(),
                retry_after_secs: None,
            }),
        )
    }

    pub fn rate_limited(
        message: impl Into<String>,
        retry_after_secs: u64,
    ) -> (StatusCode, Json<ApiError>) {
        (
            StatusCode::TOO_MANY_REQUESTS,
            Json(ApiError {
                error: "rate_limited".to_string(),
                message: message.into(),
                retry_after_secs: Some(retry_after_secs),
            }),
        )
    }

    pub fn cooldown(
        message: impl Into<String>,
        retry_after_secs: u64,
    ) -> (StatusCode, Json<ApiError>) {
        (
            StatusCode::TOO_MANY_REQUESTS,
            Json(ApiError {
                error: "cooldown".to_string(),
                message: message.into(),
                retry_after_secs: Some(retry_after_secs),
            }),
        )
    }

    pub fn acme_rate_limited(
        message: impl Into<String>,
        retry_after_secs: u64,
    ) -> (StatusCode, Json<ApiError>) {
        (
            StatusCode::TOO_MANY_REQUESTS,
            Json(ApiError {
                error: "acme_rate_limited".to_string(),
                message: message.into(),
                retry_after_secs: Some(retry_after_secs),
            }),
        )
    }

    pub fn acme_failure(message: impl Into<String>) -> (StatusCode, Json<ApiError>) {
        (
            StatusCode::BAD_GATEWAY,
            Json(ApiError {
                error: "acme_failure".to_string(),
                message: message.into(),
                retry_after_secs: None,
            }),
        )
    }

    pub fn timeout(message: impl Into<String>) -> (StatusCode, Json<ApiError>) {
        (
            StatusCode::GATEWAY_TIMEOUT,
            Json(ApiError {
                error: "timeout".to_string(),
                message: message.into(),
                retry_after_secs: None,
            }),
        )
    }

    pub fn internal(message: impl Into<String>) -> (StatusCode, Json<ApiError>) {
        (
            StatusCode::INTERNAL_SERVER_ERROR,
            Json(ApiError {
                error: "internal".to_string(),
                message: message.into(),
                retry_after_secs: None,
            }),
        )
    }
}

/// Type alias for API handler results.
pub type ApiResult = Result<Response, Response>;

/// Convert (StatusCode, Json<ApiError>) into a Response for use in Result::Err.
impl IntoResponse for ApiError {
    fn into_response(self) -> Response {
        let status = match self.error.as_str() {
            "bad_request" => StatusCode::BAD_REQUEST,
            "unauthorized" => StatusCode::UNAUTHORIZED,
            "forbidden" => StatusCode::FORBIDDEN,
            "not_found" => StatusCode::NOT_FOUND,
            "rate_limited" | "cooldown" | "acme_rate_limited" => StatusCode::TOO_MANY_REQUESTS,
            "acme_failure" => StatusCode::BAD_GATEWAY,
            "timeout" => StatusCode::GATEWAY_TIMEOUT,
            _ => StatusCode::INTERNAL_SERVER_ERROR,
        };
        (status, Json(self)).into_response()
    }
}

/// Shared application state passed to all handlers via axum's State extractor.
pub struct AppState {
    pub relay_state: std::sync::Arc<crate::state::RelayState>,
    pub session_registry: std::sync::Arc<crate::passthrough::SessionRegistry>,
    pub firestore: std::sync::Arc<dyn crate::firestore::FirestoreClient>,
    pub fcm: std::sync::Arc<dyn crate::fcm::FcmClient>,
    pub acme: std::sync::Arc<dyn crate::acme::AcmeClient>,
    pub firebase_auth: std::sync::Arc<dyn crate::firebase_auth::FirebaseAuth>,
    pub subdomain_generator: crate::subdomain::SubdomainGenerator,
    pub rate_limiter: crate::rate_limit::RateLimiter,
    pub config: crate::config::RelayConfig,
}

/// Build the axum router with all API endpoints.
pub fn build_router(state: std::sync::Arc<AppState>) -> axum::Router {
    axum::Router::new()
        .route("/register", axum::routing::post(register::handle_register))
        .route("/renew", axum::routing::post(renew::handle_renew))
        .route("/wake/{subdomain}", axum::routing::post(wake::handle_wake))
        .route("/status", axum::routing::get(status::handle_status))
        .route("/ws", axum::routing::get(ws::handle_ws))
        .with_state(state)
}
