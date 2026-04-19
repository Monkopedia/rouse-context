//! Test-mode admin HTTP server + instrumentation.
//!
//! Compiled under `#[cfg(feature = "test-mode")]` so it is excluded from
//! release builds. When the `--test-mode <port>` CLI flag (or `TEST_MODE_PORT`
//! env var) is set, the relay binds a second, unauthenticated HTTP listener
//! on `127.0.0.1:<port>` that exposes hooks the integration-test fixture in
//! `core/tunnel/src/jvmTest/...` uses to drive failure scenarios:
//!
//! - `POST /test/kill-ws?subdomain=<sub>` — abort the active mux WebSocket
//!   for a device without sending a close frame. Simulates relay-side crash
//!   or network drop so the device-side half-open detector fires.
//! - `POST /test/fcm-wake?subdomain=<sub>` — record a synthetic FCM wake in
//!   the in-memory fake. The test harness reads this via `/test/stats`.
//! - `POST /test/open-stream?subdomain=<sub>&stream_id=<id>&sni=<host>` —
//!   push a mux OPEN frame to the device as if the relay's passthrough code
//!   had routed an inbound AI-client connection for `stream_id`. Used by
//!   `:app` integration tests to drive inbound mux streams without having to
//!   perform a real SNI-routed TLS handshake (Conscrypt suppresses SNI under
//!   Robolectric — see issue #262). The device-side `MuxDemux` will surface
//!   the new stream on `TunnelClient.incomingSessions`.
//! - `POST /test/emit-stream-error?subdomain=<sub>&stream_id=<id>&code=<n>&message=<s>` —
//!   push a mux ERROR frame onto an already-open stream so tests can assert
//!   that stream-level failures do not tear down the tunnel. `code` is a
//!   `MuxErrorCode` value (1..=4); `message` is an optional UTF-8 diagnostic.
//! - `GET  /test/stats` — per-endpoint request counters + synthetic-wake log.
//!
//! See issue #249 for the motivating scenarios (#247 integration tier).
//!
//! # Security
//!
//! This admin server has zero authentication. It MUST NEVER be enabled in a
//! release build. The `test-mode` feature guard is the compile-time barrier;
//! the `--test-mode` CLI flag is the runtime barrier.

use axum::extract::{Query, State};
use axum::http::StatusCode;
use axum::response::{IntoResponse, Response};
use axum::Json;
use serde::{Deserialize, Serialize};
use std::net::SocketAddr;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Mutex};
use tokio::net::TcpListener;
use tracing::{info, warn};

use crate::mux::frame::{ErrorCode, Frame, FrameType};
use crate::passthrough::SessionRegistry;

/// Per-endpoint request counters and captured synthetic wake events.
///
/// Populated by:
/// - `record_api_request` middleware for each inbound relay-API request
/// - `/test/fcm-wake` admin handler
///
/// Read back via `/test/stats`.
#[derive(Default)]
pub struct TestMetrics {
    pub register_calls: AtomicU64,
    pub register_certs_calls: AtomicU64,
    pub rotate_secret_calls: AtomicU64,
    pub renew_calls: AtomicU64,
    pub ws_calls: AtomicU64,
    pub request_subdomain_calls: AtomicU64,
    /// Last-seen client-cert presence flag per endpoint path.
    /// Indexed by normalised path (e.g. "/renew"). `true` means the request
    /// carried a `DeviceIdentity` extension (mTLS client cert validated).
    pub last_client_cert_seen: Mutex<std::collections::HashMap<String, bool>>,
    /// Synthetic FCM wake events captured via `POST /test/fcm-wake`.
    pub captured_wakes: Mutex<Vec<String>>,
    /// SNI hostnames seen on successfully-routed device-passthrough
    /// connections, in arrival order. Populated by `record_routed_passthrough`
    /// from `main.rs::handle_connection` when a ClientHello is dispatched to
    /// a device's mux session. Used by `:app` integration tests (#262) to
    /// prove the synthetic AI client's outbound TLS actually carried an SNI
    /// extension — previously Conscrypt (Robolectric's default JSSE) silently
    /// dropped it.
    pub routed_passthrough_snis: Mutex<Vec<String>>,
}

impl TestMetrics {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn record_endpoint(&self, path: &str, had_client_cert: bool) {
        match path {
            "/register" => {
                self.register_calls.fetch_add(1, Ordering::Relaxed);
            }
            "/register/certs" => {
                self.register_certs_calls.fetch_add(1, Ordering::Relaxed);
            }
            "/rotate-secret" => {
                self.rotate_secret_calls.fetch_add(1, Ordering::Relaxed);
            }
            "/renew" => {
                self.renew_calls.fetch_add(1, Ordering::Relaxed);
            }
            "/ws" => {
                self.ws_calls.fetch_add(1, Ordering::Relaxed);
            }
            "/request-subdomain" => {
                self.request_subdomain_calls.fetch_add(1, Ordering::Relaxed);
            }
            _ => {}
        }
        if let Ok(mut map) = self.last_client_cert_seen.lock() {
            map.insert(path.to_string(), had_client_cert);
        }
    }

    /// Record an SNI hostname that was resolved to a device-passthrough route.
    /// Called from the SNI router in `main.rs::handle_connection` right
    /// before the splice task starts.
    pub fn record_routed_passthrough(&self, sni: &str) {
        if let Ok(mut v) = self.routed_passthrough_snis.lock() {
            v.push(sni.to_string());
        }
    }
}

/// Axum middleware that records each request's path + mTLS identity presence
/// into the shared `TestMetrics`. Attached via
/// `axum::middleware::from_fn_with_state(metrics, record_api_request)` so the
/// non-test-mode router has zero overhead (it never inserts this layer).
pub async fn record_api_request(
    State(metrics): State<Arc<TestMetrics>>,
    req: axum::extract::Request,
    next: axum::middleware::Next,
) -> Response {
    let path = req.uri().path().to_string();
    let has_ident = req
        .extensions()
        .get::<crate::api::ws::DeviceIdentity>()
        .is_some();
    metrics.record_endpoint(&path, has_ident);
    next.run(req).await
}

#[derive(Clone)]
pub struct AdminState {
    pub metrics: Arc<TestMetrics>,
    pub session_registry: Arc<SessionRegistry>,
}

#[derive(Deserialize)]
pub struct SubdomainQuery {
    subdomain: String,
}

#[derive(Serialize)]
pub struct KillResponse {
    killed: bool,
    subdomain: String,
}

pub async fn handle_kill_ws(
    State(state): State<AdminState>,
    Query(q): Query<SubdomainQuery>,
) -> Response {
    let killed = state.session_registry.kill(&q.subdomain);
    warn!(
        subdomain = %q.subdomain,
        killed,
        "test-mode: kill-ws triggered"
    );
    (
        StatusCode::OK,
        Json(KillResponse {
            killed,
            subdomain: q.subdomain,
        }),
    )
        .into_response()
}

#[derive(Serialize)]
pub struct WakeResponse {
    captured: usize,
}

pub async fn handle_fcm_wake(
    State(state): State<AdminState>,
    Query(q): Query<SubdomainQuery>,
) -> Response {
    let captured = {
        let mut guard = state.metrics.captured_wakes.lock().unwrap();
        guard.push(q.subdomain.clone());
        guard.len()
    };
    info!(subdomain = %q.subdomain, "test-mode: synthetic FCM wake captured");
    (StatusCode::OK, Json(WakeResponse { captured })).into_response()
}

#[derive(Serialize)]
pub struct StatsResponse {
    pub register_calls: u64,
    pub register_certs_calls: u64,
    pub rotate_secret_calls: u64,
    pub renew_calls: u64,
    pub ws_calls: u64,
    pub request_subdomain_calls: u64,
    pub last_client_cert_seen: std::collections::HashMap<String, bool>,
    pub captured_wakes: Vec<String>,
    pub routed_passthrough_snis: Vec<String>,
}

pub async fn handle_stats(State(state): State<AdminState>) -> Response {
    let m = &state.metrics;
    let snapshot = StatsResponse {
        register_calls: m.register_calls.load(Ordering::Relaxed),
        register_certs_calls: m.register_certs_calls.load(Ordering::Relaxed),
        rotate_secret_calls: m.rotate_secret_calls.load(Ordering::Relaxed),
        renew_calls: m.renew_calls.load(Ordering::Relaxed),
        ws_calls: m.ws_calls.load(Ordering::Relaxed),
        request_subdomain_calls: m.request_subdomain_calls.load(Ordering::Relaxed),
        last_client_cert_seen: m
            .last_client_cert_seen
            .lock()
            .map(|g| g.clone())
            .unwrap_or_default(),
        captured_wakes: m
            .captured_wakes
            .lock()
            .map(|g| g.clone())
            .unwrap_or_default(),
        routed_passthrough_snis: m
            .routed_passthrough_snis
            .lock()
            .map(|g| g.clone())
            .unwrap_or_default(),
    };
    (StatusCode::OK, Json(snapshot)).into_response()
}

/// Query parameters for `/test/open-stream`: subdomain + stream id + SNI hostname.
///
/// `sni` is surfaced as the OPEN frame payload so the device side sees the
/// same bytes a real passthrough would carry (the device currently ignores
/// the payload, but keeping the shape identical avoids divergence if it ever
/// starts validating).
#[derive(Deserialize)]
pub struct OpenStreamQuery {
    subdomain: String,
    stream_id: u32,
    #[serde(default)]
    sni: Option<String>,
}

#[derive(Serialize)]
pub struct OpenStreamResponse {
    opened: bool,
    subdomain: String,
    stream_id: u32,
}

/// Push a synthetic OPEN frame to the device. See module docs.
pub async fn handle_open_stream(
    State(state): State<AdminState>,
    Query(q): Query<OpenStreamQuery>,
) -> Response {
    let payload = q.sni.as_deref().unwrap_or_default().as_bytes().to_vec();
    let frame = Frame {
        frame_type: FrameType::Open,
        stream_id: q.stream_id,
        payload,
    };
    match state.session_registry.emit_frame(&q.subdomain, frame).await {
        Ok(true) => {
            info!(
                subdomain = %q.subdomain,
                stream_id = q.stream_id,
                "test-mode: synthetic OPEN frame emitted"
            );
            (
                StatusCode::OK,
                Json(OpenStreamResponse {
                    opened: true,
                    subdomain: q.subdomain,
                    stream_id: q.stream_id,
                }),
            )
                .into_response()
        }
        Ok(false) => (
            StatusCode::NOT_FOUND,
            Json(OpenStreamResponse {
                opened: false,
                subdomain: q.subdomain,
                stream_id: q.stream_id,
            }),
        )
            .into_response(),
        Err(()) => (
            StatusCode::CONFLICT,
            Json(OpenStreamResponse {
                opened: false,
                subdomain: q.subdomain,
                stream_id: q.stream_id,
            }),
        )
            .into_response(),
    }
}

/// Query parameters for `/test/emit-stream-error`.
#[derive(Deserialize)]
pub struct EmitStreamErrorQuery {
    subdomain: String,
    stream_id: u32,
    /// Mux error code (1..=4). Defaults to `STREAM_RESET` (2) if omitted.
    #[serde(default)]
    code: Option<u32>,
    /// Optional diagnostic message appended to the ERROR payload.
    #[serde(default)]
    message: Option<String>,
}

#[derive(Serialize)]
pub struct EmitStreamErrorResponse {
    emitted: bool,
    subdomain: String,
    stream_id: u32,
}

/// Push a synthetic ERROR frame onto an already-open stream. See module docs.
pub async fn handle_emit_stream_error(
    State(state): State<AdminState>,
    Query(q): Query<EmitStreamErrorQuery>,
) -> Response {
    let code_raw = q.code.unwrap_or(ErrorCode::StreamReset as u32);
    let code = match ErrorCode::from_u32(code_raw) {
        Ok(c) => c,
        Err(_) => {
            warn!(
                subdomain = %q.subdomain,
                code = code_raw,
                "test-mode: invalid error code on emit-stream-error"
            );
            return (
                StatusCode::BAD_REQUEST,
                Json(EmitStreamErrorResponse {
                    emitted: false,
                    subdomain: q.subdomain,
                    stream_id: q.stream_id,
                }),
            )
                .into_response();
        }
    };
    let payload = code.encode_payload(q.message.as_deref());
    let frame = Frame {
        frame_type: FrameType::Error,
        stream_id: q.stream_id,
        payload,
    };
    match state.session_registry.emit_frame(&q.subdomain, frame).await {
        Ok(true) => {
            info!(
                subdomain = %q.subdomain,
                stream_id = q.stream_id,
                code = code_raw,
                "test-mode: synthetic ERROR frame emitted"
            );
            (
                StatusCode::OK,
                Json(EmitStreamErrorResponse {
                    emitted: true,
                    subdomain: q.subdomain,
                    stream_id: q.stream_id,
                }),
            )
                .into_response()
        }
        Ok(false) => (
            StatusCode::NOT_FOUND,
            Json(EmitStreamErrorResponse {
                emitted: false,
                subdomain: q.subdomain,
                stream_id: q.stream_id,
            }),
        )
            .into_response(),
        Err(()) => (
            StatusCode::CONFLICT,
            Json(EmitStreamErrorResponse {
                emitted: false,
                subdomain: q.subdomain,
                stream_id: q.stream_id,
            }),
        )
            .into_response(),
    }
}

pub fn build_admin_router(admin_state: AdminState) -> axum::Router {
    axum::Router::new()
        .route("/test/kill-ws", axum::routing::post(handle_kill_ws))
        .route("/test/fcm-wake", axum::routing::post(handle_fcm_wake))
        .route("/test/open-stream", axum::routing::post(handle_open_stream))
        .route(
            "/test/emit-stream-error",
            axum::routing::post(handle_emit_stream_error),
        )
        .route("/test/stats", axum::routing::get(handle_stats))
        .with_state(admin_state)
}

/// Bind a plain-HTTP admin listener on `127.0.0.1:<port>`. Spawned as a
/// detached tokio task. Intentionally does not participate in graceful
/// shutdown: the relay subprocess is SIGKILL'd by the test fixture.
pub async fn spawn_admin_server(
    port: u16,
    metrics: Arc<TestMetrics>,
    session_registry: Arc<SessionRegistry>,
) -> std::io::Result<()> {
    let addr = SocketAddr::from(([127, 0, 0, 1], port));
    let listener = TcpListener::bind(addr).await?;
    let actual_addr = listener.local_addr()?;
    info!(addr = %actual_addr, "test-mode admin server listening");

    let router = build_admin_router(AdminState {
        metrics,
        session_registry,
    });

    tokio::spawn(async move {
        if let Err(e) = axum::serve(listener, router).await {
            warn!(error = %e, "test-mode admin server exited");
        }
    });

    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::mux::frame::{ErrorCode, FrameType};
    use axum::body::Body;
    use axum::http::{Request, StatusCode};
    use tokio::sync::mpsc;
    use tower::ServiceExt;

    /// Register a fake session entry on the registry and hand back the
    /// receiver end of its `frame_tx`, so tests can observe what the admin
    /// handler actually enqueued.
    fn register_fake_session(registry: &SessionRegistry, subdomain: &str) -> mpsc::Receiver<Frame> {
        let (open_tx, _open_rx) = mpsc::channel(4);
        let (kill_tx, _kill_rx) = mpsc::channel(1);
        let (frame_tx, frame_rx) = mpsc::channel(16);
        registry.insert(subdomain, open_tx, kill_tx, frame_tx);
        frame_rx
    }

    fn admin_state() -> (AdminState, Arc<SessionRegistry>) {
        let registry = Arc::new(SessionRegistry::new());
        let state = AdminState {
            metrics: Arc::new(TestMetrics::new()),
            session_registry: registry.clone(),
        };
        (state, registry)
    }

    async fn post(router: axum::Router, uri: &str) -> axum::response::Response {
        router
            .oneshot(
                Request::builder()
                    .method("POST")
                    .uri(uri)
                    .body(Body::empty())
                    .unwrap(),
            )
            .await
            .unwrap()
    }

    #[tokio::test]
    async fn emit_stream_error_writes_error_frame_to_registered_session() {
        let (state, registry) = admin_state();
        let mut frame_rx = register_fake_session(&registry, "dev-1");

        let router = build_admin_router(state);
        let resp = post(
            router,
            "/test/emit-stream-error?subdomain=dev-1&stream_id=7&code=2&message=boom",
        )
        .await;
        assert_eq!(resp.status(), StatusCode::OK);

        let frame = frame_rx.try_recv().expect("ERROR frame should be enqueued");
        assert_eq!(frame.frame_type, FrameType::Error);
        assert_eq!(frame.stream_id, 7);
        let (code, msg) = ErrorCode::decode_payload(&frame.payload).unwrap();
        assert_eq!(code, ErrorCode::StreamReset);
        assert_eq!(msg.as_deref(), Some("boom"));
    }

    #[tokio::test]
    async fn emit_stream_error_default_code_is_stream_reset() {
        let (state, registry) = admin_state();
        let mut frame_rx = register_fake_session(&registry, "dev-2");

        let router = build_admin_router(state);
        let resp = post(
            router,
            "/test/emit-stream-error?subdomain=dev-2&stream_id=3",
        )
        .await;
        assert_eq!(resp.status(), StatusCode::OK);

        let frame = frame_rx.try_recv().expect("frame expected");
        let (code, msg) = ErrorCode::decode_payload(&frame.payload).unwrap();
        assert_eq!(code, ErrorCode::StreamReset);
        assert!(msg.is_none());
    }

    #[tokio::test]
    async fn emit_stream_error_rejects_unknown_subdomain() {
        let (state, _registry) = admin_state();
        let router = build_admin_router(state);
        let resp = post(
            router,
            "/test/emit-stream-error?subdomain=nope&stream_id=1&code=2",
        )
        .await;
        assert_eq!(resp.status(), StatusCode::NOT_FOUND);
    }

    #[tokio::test]
    async fn emit_stream_error_rejects_invalid_code() {
        let (state, registry) = admin_state();
        let _frame_rx = register_fake_session(&registry, "dev-3");
        let router = build_admin_router(state);
        let resp = post(
            router,
            "/test/emit-stream-error?subdomain=dev-3&stream_id=1&code=999",
        )
        .await;
        assert_eq!(resp.status(), StatusCode::BAD_REQUEST);
    }

    #[tokio::test]
    async fn open_stream_writes_open_frame_with_sni_payload() {
        let (state, registry) = admin_state();
        let mut frame_rx = register_fake_session(&registry, "dev-4");

        let router = build_admin_router(state);
        let resp = post(
            router,
            "/test/open-stream?subdomain=dev-4&stream_id=5&sni=ai.example.com",
        )
        .await;
        assert_eq!(resp.status(), StatusCode::OK);

        let frame = frame_rx.try_recv().expect("OPEN frame should be enqueued");
        assert_eq!(frame.frame_type, FrameType::Open);
        assert_eq!(frame.stream_id, 5);
        assert_eq!(frame.payload, b"ai.example.com");
    }

    #[tokio::test]
    async fn open_stream_rejects_unknown_subdomain() {
        let (state, _registry) = admin_state();
        let router = build_admin_router(state);
        let resp = post(router, "/test/open-stream?subdomain=missing&stream_id=1").await;
        assert_eq!(resp.status(), StatusCode::NOT_FOUND);
    }
}
