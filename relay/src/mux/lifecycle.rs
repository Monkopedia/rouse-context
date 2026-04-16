//! Mux WebSocket lifecycle management.
//!
//! A `MuxSession` owns the WebSocket connection to a device and manages all
//! multiplexed streams. It routes frames between the WebSocket and per-stream
//! client connections.

use crate::mux::connection::MuxConnection;
use crate::mux::frame::{ErrorCode, Frame, FrameType};
use crate::state::RelayState;
use std::collections::HashMap;
use std::sync::atomic::Ordering;
use std::sync::Arc;
use tokio::sync::mpsc;
use tracing::{debug, info, warn};

/// A handle for sending frames into a mux session's WebSocket.
/// Held by passthrough tasks that need to send OPEN/DATA/CLOSE to the device.
#[derive(Clone)]
pub struct MuxHandle {
    /// Send frames to the WebSocket write loop.
    pub frame_tx: mpsc::Sender<Frame>,
    /// Max concurrent streams for this device.
    pub max_streams: u32,
}

/// Per-stream handle: the client passthrough task uses this to receive DATA
/// and CLOSE frames destined for its stream.
pub struct StreamHandle {
    pub stream_id: u32,
    pub rx: mpsc::Receiver<Frame>,
}

/// Manages the lifecycle of a single device's mux WebSocket connection.
///
/// The session runs two loops:
/// - **Read loop**: reads frames from the WebSocket and dispatches to stream channels
/// - **Write loop**: reads frames from the outbound channel and writes to the WebSocket
///
/// External code interacts via `MuxHandle` (to send frames) and `StreamHandle`
/// (to receive frames for a specific stream).
pub struct MuxSession {
    pub subdomain: String,
    pub max_streams: u32,
    connection: MuxConnection,
    next_stream_id: u32,
    /// Per-stream sender for routing incoming frames to the right client.
    stream_senders: HashMap<u32, mpsc::Sender<Frame>>,
    /// Channel for the write loop (frames going out to the WebSocket).
    frame_tx: mpsc::Sender<Frame>,
    frame_rx: Option<mpsc::Receiver<Frame>>,
}

impl MuxSession {
    /// Create a new mux session for a device.
    pub fn new(subdomain: String, max_streams: u32) -> Self {
        let (frame_tx, frame_rx) = mpsc::channel(256);
        Self {
            subdomain,
            max_streams,
            connection: MuxConnection::new(max_streams),
            next_stream_id: 1,
            stream_senders: HashMap::new(),
            frame_tx,
            frame_rx: Some(frame_rx),
        }
    }

    /// Get a handle for sending frames into this session's WebSocket.
    pub fn handle(&self) -> MuxHandle {
        MuxHandle {
            frame_tx: self.frame_tx.clone(),
            max_streams: self.max_streams,
        }
    }

    /// Open a new stream. Returns a `StreamHandle` for the client passthrough
    /// task to receive frames, and the assigned stream ID.
    ///
    /// Returns `None` if the max stream limit has been reached.
    pub fn open_stream(&mut self) -> Option<StreamHandle> {
        let stream_id = self.next_stream_id;
        if !self.connection.open_stream(stream_id) {
            return None;
        }
        self.next_stream_id += 1;
        let (tx, rx) = mpsc::channel(64);
        self.stream_senders.insert(stream_id, tx);
        Some(StreamHandle { stream_id, rx })
    }

    /// Close a stream from the relay side.
    pub fn close_stream(&mut self, stream_id: u32) {
        self.connection.close_stream(stream_id);
        self.stream_senders.remove(&stream_id);
    }

    /// Take the frame receiver (for the write loop). Can only be called once.
    pub fn take_frame_rx(&mut self) -> Option<mpsc::Receiver<Frame>> {
        self.frame_rx.take()
    }

    /// Dispatch an incoming frame (from the WebSocket read loop) to the
    /// appropriate stream channel.
    pub async fn dispatch_incoming(&mut self, frame: Frame, relay_state: &Arc<RelayState>) {
        let stream_id = frame.stream_id;
        match frame.frame_type {
            FrameType::Data => {
                if let Some(tx) = self.stream_senders.get(&stream_id) {
                    if tx.send(frame).await.is_err() {
                        debug!(
                            subdomain = %self.subdomain,
                            stream_id,
                            "Stream receiver dropped, closing stream"
                        );
                        self.close_stream(stream_id);
                    }
                } else {
                    debug!(
                        subdomain = %self.subdomain,
                        stream_id,
                        "DATA for unknown stream, ignoring"
                    );
                }
            }
            FrameType::Close => {
                if let Some(tx) = self.stream_senders.get(&stream_id) {
                    let _ = tx.send(frame).await;
                }
                self.close_stream(stream_id);
                self.update_stream_count(relay_state);
            }
            FrameType::Error => {
                if let Some(tx) = self.stream_senders.get(&stream_id) {
                    let _ = tx.send(frame).await;
                }
                self.close_stream(stream_id);
                self.update_stream_count(relay_state);
            }
            FrameType::Open => {
                // Device should not send OPEN to relay; ignore.
                warn!(
                    subdomain = %self.subdomain,
                    stream_id,
                    "Unexpected OPEN frame from device"
                );
            }
            FrameType::Ping => {
                // Echo the nonce back in a Pong frame. stream_id and payload
                // are copied verbatim so the client can match the response to
                // its request. See issue #179 for the wire-format rationale.
                let pong = Frame {
                    frame_type: FrameType::Pong,
                    stream_id: frame.stream_id,
                    payload: frame.payload,
                };
                if let Err(e) = self.frame_tx.send(pong).await {
                    debug!(
                        subdomain = %self.subdomain,
                        error = %e,
                        "Failed to send Pong reply"
                    );
                }
            }
            FrameType::Pong => {
                // Relay does not currently initiate Pings, so Pongs arriving
                // here are stray. Accept silently; future keepalive telemetry
                // could track them.
                debug!(subdomain = %self.subdomain, "Received unsolicited Pong, ignoring");
            }
        }
    }

    /// Close all streams (e.g., on WebSocket disconnect or shutdown).
    /// Sends a CLOSE frame for each open stream via the frame_tx channel.
    pub async fn close_all_streams(&mut self) {
        let stream_ids: Vec<u32> = self.stream_senders.keys().copied().collect();
        for stream_id in &stream_ids {
            // Notify the client passthrough that the stream is closed
            if let Some(tx) = self.stream_senders.get(stream_id) {
                let close_frame = Frame {
                    frame_type: FrameType::Close,
                    stream_id: *stream_id,
                    payload: Vec::new(),
                };
                let _ = tx.send(close_frame).await;
            }
        }
        // Also send CLOSE frames to the device for each stream
        for stream_id in &stream_ids {
            let close_frame = Frame {
                frame_type: FrameType::Close,
                stream_id: *stream_id,
                payload: Vec::new(),
            };
            let _ = self.frame_tx.send(close_frame).await;
        }
        self.stream_senders.clear();
    }

    /// Number of currently open streams.
    pub fn active_stream_count(&self) -> u32 {
        self.connection.active_stream_count()
    }

    /// Update the stream count in RelayState.
    fn update_stream_count(&self, relay_state: &Arc<RelayState>) {
        if let Some(mut info) = relay_state.mux_connections.get_mut(&self.subdomain) {
            info.active_streams = self.connection.active_stream_count();
        }
    }

    /// Send an ERROR frame for a stream (stream refused).
    pub async fn send_stream_refused(&self, stream_id: u32) {
        let payload = ErrorCode::StreamRefused.encode_payload(Some("max streams exceeded"));
        let frame = Frame {
            frame_type: FrameType::Error,
            stream_id,
            payload,
        };
        let _ = self.frame_tx.send(frame).await;
    }
}

impl MuxHandle {
    /// Send a frame to the device via the mux WebSocket.
    pub async fn send_frame(&self, frame: Frame) -> Result<(), mpsc::error::SendError<Frame>> {
        self.frame_tx.send(frame).await
    }
}

/// Run the mux session read/dispatch loop.
///
/// This processes incoming frames from the WebSocket and dispatches them to
/// the appropriate stream channels. Returns when the WebSocket is closed or
/// an error occurs.
///
/// `incoming_rx` is the channel receiving decoded frames from the WebSocket
/// read task.
pub async fn run_mux_read_loop(
    session: &mut MuxSession,
    mut incoming_rx: mpsc::Receiver<Frame>,
    relay_state: &Arc<RelayState>,
) {
    info!(subdomain = %session.subdomain, "Mux read loop started");

    while let Some(frame) = incoming_rx.recv().await {
        session.dispatch_incoming(frame, relay_state).await;
    }

    // WebSocket closed — clean up all streams
    info!(
        subdomain = %session.subdomain,
        active_streams = session.active_stream_count(),
        "Mux WebSocket closed, cleaning up"
    );
    session.close_all_streams().await;
    relay_state.remove_mux_connection(&session.subdomain);
    relay_state
        .total_sessions_served
        .fetch_add(1, Ordering::Relaxed);
}
