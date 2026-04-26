//! HTTP API endpoints for the relay server.
//!
//! All endpoints are served over HTTPS on `relay.rousecontext.com`.

pub mod register;
pub mod renew;
pub mod request_subdomain;
pub mod rotate_secret;
pub mod status;
pub mod ws;

use axum::extract::Request;
use axum::http::StatusCode;
use axum::middleware::Next;
use axum::response::{IntoResponse, Response};
use axum::Json;
use serde::Serialize;

use crate::api::ws::DeviceIdentity;

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
    pub dns: std::sync::Arc<dyn crate::dns::DnsClient>,
    pub firebase_auth: std::sync::Arc<dyn crate::firebase_auth::FirebaseAuth>,
    pub subdomain_generator: crate::subdomain::SubdomainGenerator,
    pub rate_limiter: crate::rate_limit::RateLimiter,
    /// Per-Firebase-UID limiter for `POST /request-subdomain`. Prevents
    /// pool enumeration. Keyed by `firebase_uid`, independent from the
    /// wake/passthrough buckets.
    pub request_subdomain_rate_limiter: crate::rate_limit::RateLimiter,
    /// Shared FCM-wake throttle. Owned by the passthrough path (#423) — the
    /// `/ws` upgrade handler clears the entry for a subdomain once its mux
    /// session registers, so a disconnect+reconnect cycle inside the cooldown
    /// window still triggers a fresh FCM push. Wrapped in `Arc` because the
    /// passthrough connection handlers also hold a clone.
    pub fcm_wake_throttle: std::sync::Arc<crate::rate_limit::FcmWakeThrottle>,
    pub config: crate::config::RelayConfig,
    pub device_ca: Option<crate::device_ca::DeviceCa>,
    /// Optional test-mode instrumentation. `None` in production. When `Some`,
    /// every HTTP request that flows through the relay API router is counted
    /// in the shared metrics store (see `test_mode::record_api_request`).
    /// Only populated when the binary is built with `--features test-mode`
    /// and launched with `--test-mode <port>`.
    #[cfg(feature = "test-mode")]
    pub test_metrics: Option<std::sync::Arc<crate::test_mode::TestMetrics>>,
}

/// Tower middleware that rejects requests without a `DeviceIdentity`
/// extension (i.e. without a valid mTLS client certificate) before the
/// handler runs.
///
/// This exists so that the authenticated routes never have to repeat a
/// per-handler `DeviceIdentity.is_some()` check. Missing that check is
/// exactly how #202 slipped in: `/rotate-secret` trusted the request body's
/// `subdomain` field when no mTLS identity was present. Making the auth
/// boundary structural means any future handler added to the authed router
/// inherits the guarantee.
///
/// Public routes (`/register`, `/status`, `/.well-known/*`, etc.) are on a
/// separate router that does NOT apply this middleware, so they remain
/// reachable without a client cert.
pub async fn require_device_identity(req: Request, next: Next) -> Response {
    if req.extensions().get::<DeviceIdentity>().is_none() {
        return ApiError::unauthorized("Valid client certificate required").into_response();
    }
    next.run(req).await
}

/// Build the axum router with all API endpoints.
///
/// The router is split into two groups:
/// - **Public**: no auth layer; handlers either need no auth or do their own
///   (body-driven signature check, Firebase token, PoP signature, etc.).
/// - **Authed**: `require_device_identity` middleware rejects with 401 before
///   the handler body runs if no mTLS `DeviceIdentity` is present.
///
/// Currently only hard-mTLS routes live in the authed group:
/// - `POST /rotate-secret` — privileged per-device secret rotation.
/// - `GET /ws` — mux WebSocket upgrade.
///
/// Everything else (including `/renew`, which has a body-driven Firebase
/// fallback, and `/register/certs`, which uses a PoP signature rather than
/// mTLS) stays in the public group. Expanding the authed group is a matter
/// of moving the route across; the middleware enforcement is then automatic.
pub fn build_router(state: std::sync::Arc<AppState>) -> axum::Router {
    let public_router = axum::Router::new()
        .route("/register", axum::routing::post(register::handle_register))
        .route(
            "/register/certs",
            axum::routing::post(register::handle_register_certs),
        )
        .route(
            "/request-subdomain",
            axum::routing::post(request_subdomain::handle_request_subdomain),
        )
        .route("/renew", axum::routing::post(renew::handle_renew))
        .route("/status", axum::routing::get(status::handle_status));

    let authed_router = axum::Router::new()
        .route(
            "/rotate-secret",
            axum::routing::post(rotate_secret::handle_rotate_secret),
        )
        .route("/ws", axum::routing::get(ws::handle_ws))
        .layer(axum::middleware::from_fn(require_device_identity));

    let merged = public_router.merge(authed_router).with_state(state.clone());

    // In test-mode, wrap every request with the instrumentation middleware
    // that records call counts + client-cert presence. In release builds
    // (feature disabled) this branch is compiled out entirely.
    #[cfg(feature = "test-mode")]
    let merged = if let Some(metrics) = state.test_metrics.clone() {
        merged.layer(axum::middleware::from_fn_with_state(
            metrics,
            crate::test_mode::record_api_request,
        ))
    } else {
        merged
    };

    merged
}
