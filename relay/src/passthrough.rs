//! Client TCP passthrough to device mux streams.
//!
//! When an MCP client connects to `{subdomain}.rousecontext.com`, the relay:
//! 1. Buffers the TLS ClientHello (already read by the SNI router)
//! 2. Looks up the device's mux connection
//! 3. If offline, sends FCM wake and waits for the device to connect
//! 4. Opens a mux stream (OPEN frame with SNI hostname)
//! 5. Forwards the buffered ClientHello as a DATA frame
//! 6. Splices bidirectional data between the client TCP socket and the mux stream
//! 7. On disconnect from either side, sends CLOSE and tears down
//!
//! The relay NEVER terminates TLS. It forwards opaque ciphertext.

use crate::fcm::{self, FcmClient};
use crate::firestore::FirestoreClient;
use crate::mux::frame::{Frame, FrameType};
use crate::mux::lifecycle::{MuxHandle, StreamHandle};
use crate::rate_limit::FcmWakeThrottle;
use crate::state::RelayState;
use dashmap::DashMap;
use std::sync::atomic::Ordering;
use std::sync::Arc;
use std::time::Duration;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::TcpStream;
use tokio::sync::{mpsc, oneshot, Notify};
use tracing::{debug, info, warn};

/// Errors that can occur during passthrough.
#[derive(Debug)]
pub enum PassthroughError {
    /// Device has no mux connection and FCM wakeup timed out.
    WakeupTimeout,
    /// Device has no mux connection and no FCM token (not registered).
    DeviceNotFound,
    /// Max streams exceeded on the device's mux connection.
    StreamRefused,
    /// Failed to send FCM wake notification.
    FcmFailed(String),
    /// Firestore lookup failed.
    FirestoreFailed(String),
    /// Internal channel error.
    ChannelClosed,
    /// Secret prefix does not match the device record.
    InvalidSecret,
    /// I/O error on the client socket.
    Io(std::io::Error),
}

impl std::fmt::Display for PassthroughError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            PassthroughError::WakeupTimeout => write!(f, "device wakeup timed out"),
            PassthroughError::DeviceNotFound => write!(f, "device not found"),
            PassthroughError::InvalidSecret => write!(f, "invalid secret prefix"),
            PassthroughError::StreamRefused => write!(f, "max streams exceeded"),
            PassthroughError::FcmFailed(e) => write!(f, "FCM send failed: {e}"),
            PassthroughError::FirestoreFailed(e) => write!(f, "Firestore failed: {e}"),
            PassthroughError::ChannelClosed => write!(f, "mux channel closed"),
            PassthroughError::Io(e) => write!(f, "I/O error: {e}"),
        }
    }
}

/// A request to open a stream on a mux session.
/// The session owner processes these and sends back the result.
pub struct OpenStreamRequest {
    pub reply: oneshot::Sender<Option<(MuxHandle, StreamHandle)>>,
}

/// Entry in the session registry for a device.
/// Contains a channel sender for requesting stream opens from the session owner.
pub struct SessionEntry {
    pub open_stream_tx: mpsc::Sender<OpenStreamRequest>,
    /// Signals the ws mux task to abort immediately without sending a close
    /// frame. Used by the test-mode admin endpoint `POST /test/kill-ws` to
    /// simulate a relay-side network drop (see issue #249). In production the
    /// sender is never fired; the channel sits idle.
    pub kill_tx: mpsc::Sender<()>,
    /// Clone of the mux session's outbound frame channel. The write loop
    /// forwards each frame to the device's WebSocket. Used by test-mode admin
    /// endpoints (`POST /test/emit-stream-error`, `POST /test/open-stream`) to
    /// inject synthetic frames onto an already-registered session without
    /// going through the passthrough/SNI path. See issue #266 for the batch-C
    /// scenario that forced this hook (Conscrypt's SNI suppression under
    /// Robolectric blocked driving a real AI-client handshake). In production
    /// this sender is used exclusively by the session's own write loop.
    pub frame_tx: mpsc::Sender<Frame>,
}

/// Registry of active mux sessions, keyed by subdomain.
///
/// This is the bridge between the passthrough code (which needs to open streams)
/// and the mux lifecycle code (which owns the sessions). Both sides access it
/// through `Arc<SessionRegistry>`.
///
/// The registry does not own the `MuxSession` directly -- it communicates with
/// the session owner task via channels. This avoids holding mutex guards across
/// await points.
pub struct SessionRegistry {
    sessions: DashMap<String, SessionEntry>,
    /// Per-subdomain notifier fired when `insert` registers a new session.
    /// Enables deterministic "wait for session registered" semantics in
    /// tests without sleeping: `wait_until_registered` grabs (or creates)
    /// the `Notify` for a subdomain and awaits `notified()`. See issue #377
    /// for the flake this replaced (a hardcoded 500ms sleep in the tunnel
    /// integration tests was racing with `insert` under CI load).
    registration_signals: DashMap<String, Arc<Notify>>,
}

impl SessionRegistry {
    pub fn new() -> Self {
        Self {
            sessions: DashMap::new(),
            registration_signals: DashMap::new(),
        }
    }

    /// Register a mux session for a subdomain.
    pub fn insert(
        &self,
        subdomain: &str,
        open_stream_tx: mpsc::Sender<OpenStreamRequest>,
        kill_tx: mpsc::Sender<()>,
        frame_tx: mpsc::Sender<Frame>,
    ) {
        self.sessions.insert(
            subdomain.to_string(),
            SessionEntry {
                open_stream_tx,
                kill_tx,
                frame_tx,
            },
        );
        // Wake any `wait_until_registered` awaiters for this subdomain.
        if let Some(notify) = self.registration_signals.get(subdomain) {
            notify.notify_waiters();
        }
    }

    /// Return whether a mux session is currently registered for `subdomain`.
    pub fn contains(&self, subdomain: &str) -> bool {
        self.sessions.contains_key(subdomain)
    }

    /// Wait until a mux session is registered for `subdomain`, or `timeout`
    /// elapses. Returns `true` if a session is (or becomes) registered,
    /// `false` on timeout.
    ///
    /// Used by the test-mode admin `/test/wait-session-registered` endpoint to
    /// replace the 500ms sleep that used to guard the relay-side insertion
    /// race in integration tests. The approach mirrors the `CompletableDeferred`
    /// pattern from issue #341: a `Notify` primed once per subdomain lets
    /// `insert` wake awaiters directly instead of forcing them to poll.
    pub async fn wait_until_registered(&self, subdomain: &str, timeout: Duration) -> bool {
        // Grab (or create) the `Notify` for this subdomain BEFORE the fast
        // path check so that a concurrent `insert` racing between our check
        // and the `notified()` subscription is caught by `enable()` below.
        let notify = self
            .registration_signals
            .entry(subdomain.to_string())
            .or_insert_with(|| Arc::new(Notify::new()))
            .clone();
        // Per tokio docs, `notified()` only starts listening when polled.
        // `enable()` arms the future eagerly so any `notify_waiters` that
        // fires after this line will wake it, closing the classic
        // "notify between check and await" gap.
        let notified = notify.notified();
        tokio::pin!(notified);
        notified.as_mut().enable();
        if self.sessions.contains_key(subdomain) {
            return true;
        }
        tokio::time::timeout(timeout, notified).await.is_ok()
    }

    /// Send a frame directly to the device's mux WebSocket. Test-only hook
    /// used by `POST /test/emit-stream-error` and `POST /test/open-stream` to
    /// inject synthetic frames (ERROR for an existing stream, OPEN for a new
    /// one) onto a registered session. Returns `Ok(true)` if the frame was
    /// enqueued, `Ok(false)` if no session is registered for the subdomain,
    /// and `Err(())` if the session exists but its outbound channel was
    /// already closed (a racing teardown). See issue #266.
    pub async fn emit_frame(&self, subdomain: &str, frame: Frame) -> Result<bool, ()> {
        let frame_tx = match self.sessions.get(subdomain) {
            Some(entry) => entry.frame_tx.clone(),
            None => return Ok(false),
        };
        // Drop the DashMap ref before the await to avoid holding it across
        // suspension, matching the pattern in `try_open_stream`.
        frame_tx.send(frame).await.map(|_| true).map_err(|_| ())
    }

    /// Signal the mux session for `subdomain` to abort without sending a close
    /// frame. Returns `true` if a session existed and was signaled. Test-only.
    pub fn kill(&self, subdomain: &str) -> bool {
        match self.sessions.get(subdomain) {
            Some(entry) => {
                // `try_send` is used because `kill_tx` has capacity 1 and the
                // ws task drains it immediately on receipt. If it's already
                // full, the kill is already pending.
                let _ = entry.kill_tx.try_send(());
                true
            }
            None => false,
        }
    }

    /// Remove a mux session.
    pub fn remove(&self, subdomain: &str) {
        self.sessions.remove(subdomain);
    }

    /// Try to open a stream on the device's mux session.
    /// Returns `None` if no session exists or max streams reached.
    pub async fn try_open_stream(&self, subdomain: &str) -> Option<(MuxHandle, StreamHandle)> {
        let entry = self.sessions.get(subdomain)?;
        let (reply_tx, reply_rx) = oneshot::channel();
        let req = OpenStreamRequest { reply: reply_tx };
        entry.open_stream_tx.send(req).await.ok()?;
        // Don't hold the DashMap ref across the await
        drop(entry);
        reply_rx.await.ok()?
    }
}

impl Default for SessionRegistry {
    fn default() -> Self {
        Self::new()
    }
}

/// Context for a passthrough operation, holding all the dependencies.
pub struct PassthroughContext {
    pub relay_state: Arc<RelayState>,
    pub session_registry: Arc<SessionRegistry>,
    pub firestore: Arc<dyn FirestoreClient>,
    pub fcm: Arc<dyn FcmClient>,
    pub relay_hostname: String,
    pub fcm_wakeup_timeout: Duration,
    pub fcm_wake_throttle: Arc<FcmWakeThrottle>,
}

/// Result of resolving a mux handle for a device, potentially after FCM wakeup.
pub struct ResolvedStream {
    pub handle: MuxHandle,
    pub stream_id: u32,
    pub stream_rx: mpsc::Receiver<Frame>,
}

/// Attempt to get a mux handle for the device, waking it via FCM if needed.
///
/// `integration_secret` is the secret extracted from the SNI hostname (first label).
/// It is validated against the device's `valid_secrets` list,
/// falling back to `secret_prefix` for legacy devices. Empty string skips validation.
///
/// Returns a `MuxHandle` and opens a stream, or an error if the device cannot
/// be reached within the timeout.
pub async fn resolve_device_stream(
    ctx: &PassthroughContext,
    subdomain: &str,
    sni_hostname: &str,
    integration_secret: &str,
) -> Result<ResolvedStream, PassthroughError> {
    if !integration_secret.is_empty() {
        // Check the in-memory cache first. Write endpoints populate this on
        // every /register and /rotate-secret, so freshly-pushed secrets are
        // usable on the next connection without waiting for Firestore
        // eventual consistency to catch up.
        if let Some(cached) = ctx.relay_state.get_valid_secrets_cache(subdomain) {
            if cached.iter().any(|s| s == integration_secret) {
                debug!(subdomain, "Secret matched from cache");
            } else {
                info!(subdomain, "Secret mismatch vs cache (silent reject)");
                return Err(PassthroughError::InvalidSecret);
            }
        } else {
            // Cache miss: fall back to Firestore. Populate the cache from the
            // result so subsequent lookups are fast and survive restarts that
            // lost in-memory state.
            let device = ctx
                .firestore
                .get_device(subdomain)
                .await
                .map_err(|e| match e {
                    crate::firestore::FirestoreError::NotFound(_) => {
                        PassthroughError::DeviceNotFound
                    }
                    other => PassthroughError::FirestoreFailed(other.to_string()),
                })?;

            if !device.valid_secrets.is_empty() {
                // Seed the cache from Firestore for future lookups.
                ctx.relay_state
                    .set_valid_secrets_cache(subdomain, device.valid_secrets.clone());
                if !device.valid_secrets.iter().any(|s| s == integration_secret) {
                    info!(subdomain, "Secret mismatch (silent reject)");
                    return Err(PassthroughError::InvalidSecret);
                }
            } else if let Some(stored_secret) = &device.secret_prefix {
                // Fall back to legacy secret_prefix for devices that haven't migrated
                if stored_secret != integration_secret {
                    info!(subdomain, "Secret prefix mismatch (silent reject)");
                    return Err(PassthroughError::InvalidSecret);
                }
            }
            // If device has neither valid_secrets nor secret_prefix, skip validation
        }
    }

    // Try to open a stream on an existing mux connection
    if let Some((handle, stream)) = ctx.session_registry.try_open_stream(subdomain).await {
        send_open_frame(&handle, stream.stream_id, sni_hostname).await?;
        return Ok(ResolvedStream {
            handle,
            stream_id: stream.stream_id,
            stream_rx: stream.rx,
        });
    }

    // Device offline -- check FCM wake throttle before sending.
    // If throttled, we still wait for the device (FCM was already sent recently).
    // This lets multiple concurrent clients share a single FCM wake.
    if ctx.fcm_wake_throttle.should_wake(subdomain) {
        // Look up FCM token and send wake (may already be cached from above,
        // but re-fetch is fine -- Firestore lookups are fast for in-memory/stub).
        let device = ctx
            .firestore
            .get_device(subdomain)
            .await
            .map_err(|e| match e {
                crate::firestore::FirestoreError::NotFound(_) => PassthroughError::DeviceNotFound,
                other => PassthroughError::FirestoreFailed(other.to_string()),
            })?;

        let payload = fcm::wake_payload();
        ctx.fcm
            .send_data_message(&device.fcm_token, &payload, true)
            .await
            .map_err(|e| PassthroughError::FcmFailed(e.to_string()))?;
    } else {
        debug!(subdomain, "FCM wake throttled, waiting for existing wake");
    }

    // Wait for device to connect
    ctx.relay_state
        .pending_fcm_wakeups
        .fetch_add(1, Ordering::Relaxed);
    let mut rx = ctx.relay_state.subscribe_connect(subdomain);

    let result = tokio::time::timeout(ctx.fcm_wakeup_timeout, rx.recv()).await;

    ctx.relay_state
        .pending_fcm_wakeups
        .fetch_sub(1, Ordering::Relaxed);

    match result {
        Ok(Ok(())) => {
            // Device connected, try to open stream
            if let Some((handle, stream)) = ctx.session_registry.try_open_stream(subdomain).await {
                send_open_frame(&handle, stream.stream_id, sni_hostname).await?;
                Ok(ResolvedStream {
                    handle,
                    stream_id: stream.stream_id,
                    stream_rx: stream.rx,
                })
            } else {
                Err(PassthroughError::StreamRefused)
            }
        }
        _ => Err(PassthroughError::WakeupTimeout),
    }
}

async fn send_open_frame(
    handle: &MuxHandle,
    stream_id: u32,
    sni_hostname: &str,
) -> Result<(), PassthroughError> {
    let open_frame = Frame {
        frame_type: FrameType::Open,
        stream_id,
        payload: sni_hostname.as_bytes().to_vec(),
    };
    handle
        .send_frame(open_frame)
        .await
        .map_err(|_| PassthroughError::ChannelClosed)
}

/// Splice bidirectional data between a client TCP socket and a mux stream.
///
/// `client_hello` contains the buffered TLS ClientHello bytes that must be
/// forwarded as the first DATA frame.
///
/// This function runs until either the client disconnects, the device sends
/// CLOSE, or the mux channel closes. It handles cleanup (sending CLOSE) in
/// all cases.
pub async fn splice_stream(
    mut client: TcpStream,
    client_hello: &[u8],
    stream_id: u32,
    mux_handle: &MuxHandle,
    mut stream_rx: mpsc::Receiver<Frame>,
) -> Result<(), PassthroughError> {
    // 1. Forward buffered ClientHello as DATA
    let data_frame = Frame {
        frame_type: FrameType::Data,
        stream_id,
        payload: client_hello.to_vec(),
    };
    mux_handle
        .send_frame(data_frame)
        .await
        .map_err(|_| PassthroughError::ChannelClosed)?;

    info!(stream_id, "Passthrough splice started");

    // 2. Bidirectional splice
    let mut buf = vec![0u8; 16384];
    loop {
        tokio::select! {
            // Client -> Device: read from TCP, send DATA frame
            result = client.read(&mut buf) => {
                match result {
                    Ok(0) => {
                        // Client closed connection
                        debug!(stream_id, "Client disconnected");
                        let close_frame = Frame {
                            frame_type: FrameType::Close,
                            stream_id,
                            payload: Vec::new(),
                        };
                        let _ = mux_handle.send_frame(close_frame).await;
                        return Ok(());
                    }
                    Ok(n) => {
                        let data_frame = Frame {
                            frame_type: FrameType::Data,
                            stream_id,
                            payload: buf[..n].to_vec(),
                        };
                        if mux_handle.send_frame(data_frame).await.is_err() {
                            // Mux channel closed
                            debug!(stream_id, "Mux channel closed during splice");
                            return Err(PassthroughError::ChannelClosed);
                        }
                    }
                    Err(e) => {
                        debug!(stream_id, error = %e, "Client read error");
                        let close_frame = Frame {
                            frame_type: FrameType::Close,
                            stream_id,
                            payload: Vec::new(),
                        };
                        let _ = mux_handle.send_frame(close_frame).await;
                        return Err(PassthroughError::Io(e));
                    }
                }
            }
            // Device -> Client: receive frame from mux, write to TCP
            frame = stream_rx.recv() => {
                match frame {
                    Some(f) if f.frame_type == FrameType::Data => {
                        if let Err(e) = client.write_all(&f.payload).await {
                            debug!(stream_id, error = %e, "Client write error");
                            let close_frame = Frame {
                                frame_type: FrameType::Close,
                                stream_id,
                                payload: Vec::new(),
                            };
                            let _ = mux_handle.send_frame(close_frame).await;
                            return Err(PassthroughError::Io(e));
                        }
                    }
                    Some(f) if f.frame_type == FrameType::Close => {
                        debug!(stream_id, "Device closed stream");
                        let _ = client.shutdown().await;
                        return Ok(());
                    }
                    Some(f) if f.frame_type == FrameType::Error => {
                        warn!(stream_id, "Device sent ERROR for stream");
                        let _ = client.shutdown().await;
                        return Ok(());
                    }
                    Some(_) => {
                        // Unexpected frame type, ignore
                    }
                    None => {
                        // Mux channel closed (device disconnected)
                        debug!(stream_id, "Mux stream channel closed");
                        let _ = client.shutdown().await;
                        return Err(PassthroughError::ChannelClosed);
                    }
                }
            }
        }
    }
}
