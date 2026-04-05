//! Graceful shutdown coordinator.
//!
//! On SIGTERM/SIGINT:
//! 1. Stop accepting new client TCP connections and new mux WebSocket connections
//! 2. Send CLOSE for every active stream on every mux connection
//! 3. Wait for in-flight DATA frames to drain (configurable timeout)
//! 4. Close all mux WebSockets and client TCP connections
//! 5. Log shutdown stats (sessions closed, mux connections dropped)
//! 6. Exit

use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use std::time::Duration;
use tokio::sync::Notify;
use tracing::info;

/// Coordinates graceful shutdown across all relay components.
///
/// Components check `is_shutting_down()` before accepting new work.
/// The shutdown sequence is triggered by calling `initiate()`.
#[derive(Clone)]
pub struct ShutdownController {
    inner: Arc<ShutdownInner>,
}

struct ShutdownInner {
    shutting_down: AtomicBool,
    notify: Notify,
}

/// Stats collected during shutdown for logging.
#[derive(Debug, Default)]
pub struct ShutdownStats {
    pub mux_connections_closed: u32,
    pub streams_closed: u32,
    pub drain_completed: bool,
}

impl ShutdownController {
    pub fn new() -> Self {
        Self {
            inner: Arc::new(ShutdownInner {
                shutting_down: AtomicBool::new(false),
                notify: Notify::new(),
            }),
        }
    }

    /// Check whether shutdown has been initiated.
    pub fn is_shutting_down(&self) -> bool {
        self.inner.shutting_down.load(Ordering::Acquire)
    }

    /// Initiate shutdown. All waiters are notified.
    pub fn initiate(&self) {
        if !self.inner.shutting_down.swap(true, Ordering::Release) {
            info!("Shutdown initiated");
            self.inner.notify.notify_waiters();
        }
    }

    /// Wait until shutdown is initiated.
    pub async fn wait_for_shutdown(&self) {
        if self.is_shutting_down() {
            return;
        }
        self.inner.notify.notified().await;
    }

    /// Install signal handlers for SIGTERM and SIGINT.
    /// Returns a future that resolves when a signal is received and shutdown
    /// has been initiated.
    pub async fn listen_for_signals(self) {
        let ctrl_c = tokio::signal::ctrl_c();

        #[cfg(unix)]
        {
            use tokio::signal::unix::{signal, SignalKind};
            let mut sigterm =
                signal(SignalKind::terminate()).expect("failed to install SIGTERM handler");

            tokio::select! {
                _ = ctrl_c => {
                    info!("Received SIGINT");
                }
                _ = sigterm.recv() => {
                    info!("Received SIGTERM");
                }
            }
        }

        #[cfg(not(unix))]
        {
            ctrl_c.await.expect("failed to listen for ctrl-c");
            info!("Received SIGINT");
        }

        self.initiate();
    }
}

impl Default for ShutdownController {
    fn default() -> Self {
        Self::new()
    }
}

/// Execute the graceful shutdown sequence.
///
/// - Sends CLOSE for all active streams via the provided closure
/// - Waits for drain with a timeout
/// - Returns stats about what was cleaned up
pub async fn execute_shutdown(
    controller: &ShutdownController,
    drain_timeout: Duration,
    close_all_fn: impl FnOnce() -> (u32, u32),
    drain_fn: impl std::future::Future<Output = ()>,
) -> ShutdownStats {
    controller.initiate();

    let (mux_connections_closed, streams_closed) = close_all_fn();

    info!(
        mux_connections = mux_connections_closed,
        streams = streams_closed,
        "Closing all connections"
    );

    // Wait for drain with timeout
    let drain_completed = tokio::time::timeout(drain_timeout, drain_fn).await.is_ok();

    if drain_completed {
        info!("Drain completed cleanly");
    } else {
        info!("Drain timeout reached, forcing close");
    }

    let stats = ShutdownStats {
        mux_connections_closed,
        streams_closed,
        drain_completed,
    };

    info!(?stats, "Shutdown complete");

    stats
}
