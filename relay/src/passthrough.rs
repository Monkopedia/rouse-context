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
use tokio::sync::{mpsc, oneshot};
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
    /// I/O error on the client socket.
    Io(std::io::Error),
}

impl std::fmt::Display for PassthroughError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            PassthroughError::WakeupTimeout => write!(f, "device wakeup timed out"),
            PassthroughError::DeviceNotFound => write!(f, "device not found"),
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
}

impl SessionRegistry {
    pub fn new() -> Self {
        Self {
            sessions: DashMap::new(),
        }
    }

    /// Register a mux session for a subdomain.
    pub fn insert(&self, subdomain: &str, open_stream_tx: mpsc::Sender<OpenStreamRequest>) {
        self.sessions
            .insert(subdomain.to_string(), SessionEntry { open_stream_tx });
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
/// Returns a `MuxHandle` and opens a stream, or an error if the device cannot
/// be reached within the timeout.
pub async fn resolve_device_stream(
    ctx: &PassthroughContext,
    subdomain: &str,
    sni_hostname: &str,
) -> Result<ResolvedStream, PassthroughError> {
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
        // Look up FCM token and send wake
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
