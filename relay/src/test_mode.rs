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
    /// Last-seen client-cert presence flag per endpoint path.
    /// Indexed by normalised path (e.g. "/renew"). `true` means the request
    /// carried a `DeviceIdentity` extension (mTLS client cert validated).
    pub last_client_cert_seen: Mutex<std::collections::HashMap<String, bool>>,
    /// Synthetic FCM wake events captured via `POST /test/fcm-wake`.
    pub captured_wakes: Mutex<Vec<String>>,
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
            _ => {}
        }
        if let Ok(mut map) = self.last_client_cert_seen.lock() {
            map.insert(path.to_string(), had_client_cert);
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
    pub last_client_cert_seen: std::collections::HashMap<String, bool>,
    pub captured_wakes: Vec<String>,
}

pub async fn handle_stats(State(state): State<AdminState>) -> Response {
    let m = &state.metrics;
    let snapshot = StatsResponse {
        register_calls: m.register_calls.load(Ordering::Relaxed),
        register_certs_calls: m.register_certs_calls.load(Ordering::Relaxed),
        rotate_secret_calls: m.rotate_secret_calls.load(Ordering::Relaxed),
        renew_calls: m.renew_calls.load(Ordering::Relaxed),
        ws_calls: m.ws_calls.load(Ordering::Relaxed),
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
    };
    (StatusCode::OK, Json(snapshot)).into_response()
}

pub fn build_admin_router(admin_state: AdminState) -> axum::Router {
    axum::Router::new()
        .route("/test/kill-ws", axum::routing::post(handle_kill_ws))
        .route("/test/fcm-wake", axum::routing::post(handle_fcm_wake))
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
