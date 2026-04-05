//! GET /ws -- Mux WebSocket upgrade for device connections.
//!
//! Devices connect here after FCM wakeup to establish a mux session.
//! The connection flow:
//! 1. mTLS verifies the device's client certificate
//! 2. Subdomain is extracted from the client cert's CN/SAN
//! 3. HTTP connection is upgraded to WebSocket
//! 4. A MuxSession is created and registered in the SessionRegistry
//! 5. Read and write loops run until disconnect
//! 6. On disconnect: session is unregistered, streams cleaned up

use axum::extract::ws::{Message, WebSocket};
use axum::extract::{State, WebSocketUpgrade};
use axum::response::{IntoResponse, Response};
use futures_util::sink::SinkExt;
use futures_util::stream::StreamExt;
use std::sync::Arc;
use tokio::sync::mpsc;
use tracing::{debug, error, info, warn};

use crate::api::{ApiError, AppState};
use crate::mux::frame::Frame;
use crate::mux::lifecycle::MuxSession;
use crate::passthrough::{OpenStreamRequest, SessionRegistry};

/// Axum request extension set by the TLS layer when a valid client cert is present.
#[derive(Debug, Clone)]
pub struct DeviceIdentity {
    pub subdomain: String,
}

pub async fn handle_ws(
    State(state): State<Arc<AppState>>,
    device: Option<axum::Extension<DeviceIdentity>>,
    ws: WebSocketUpgrade,
) -> Response {
    let subdomain = match device {
        Some(axum::Extension(identity)) => identity.subdomain,
        None => {
            return ApiError::unauthorized("Valid client certificate required").into_response();
        }
    };

    let max_streams = state.config.limits.max_streams_per_device;
    let relay_state = state.relay_state.clone();
    let session_registry = state.session_registry.clone();

    info!(subdomain = %subdomain, "Device WebSocket upgrade accepted");

    ws.on_upgrade(move |socket| {
        handle_mux_session(
            socket,
            subdomain,
            max_streams,
            relay_state,
            session_registry,
        )
    })
}

async fn handle_mux_session(
    socket: WebSocket,
    subdomain: String,
    max_streams: u32,
    relay_state: Arc<crate::state::RelayState>,
    session_registry: Arc<SessionRegistry>,
) {
    let mut session = MuxSession::new(subdomain.clone(), max_streams);
    let frame_rx = match session.take_frame_rx() {
        Some(rx) => rx,
        None => {
            error!(subdomain = %subdomain, "Failed to take frame_rx from MuxSession");
            return;
        }
    };

    // Channel for passthrough code to request new streams
    let (open_stream_tx, mut open_stream_rx) = mpsc::channel::<OpenStreamRequest>(16);

    // Register the session so passthrough can find it
    relay_state.register_mux_connection(&subdomain);
    session_registry.insert(&subdomain, open_stream_tx);

    info!(subdomain = %subdomain, "Mux session registered");

    let (mut ws_sender, mut ws_receiver) = socket.split();

    // Channel for the read loop to dispatch incoming frames
    let (incoming_tx, incoming_rx) = mpsc::channel::<Frame>(256);

    // Spawn WebSocket read task: reads WS messages, decodes frames, sends to incoming_tx
    let read_subdomain = subdomain.clone();
    let read_task = tokio::spawn(async move {
        while let Some(msg) = ws_receiver.next().await {
            match msg {
                Ok(Message::Binary(data)) => match Frame::decode(&data) {
                    Ok(frame) => {
                        if incoming_tx.send(frame).await.is_err() {
                            debug!(subdomain = %read_subdomain, "Incoming channel closed");
                            break;
                        }
                    }
                    Err(e) => {
                        warn!(subdomain = %read_subdomain, error = %e, "Bad frame from device");
                    }
                },
                Ok(Message::Close(_)) => {
                    debug!(subdomain = %read_subdomain, "Device sent WS close");
                    break;
                }
                Ok(Message::Ping(_) | Message::Pong(_)) => {
                    // axum handles ping/pong automatically
                }
                Ok(_) => {
                    // Text or other messages are unexpected, ignore
                }
                Err(e) => {
                    debug!(subdomain = %read_subdomain, error = %e, "WebSocket read error");
                    break;
                }
            }
        }
        drop(incoming_tx);
    });

    // Spawn WebSocket write task: reads from frame_rx, encodes, sends to WS
    let write_subdomain = subdomain.clone();
    let write_task = tokio::spawn(async move {
        let mut frame_rx = frame_rx;
        while let Some(frame) = frame_rx.recv().await {
            let data = frame.encode();
            if let Err(e) = ws_sender.send(Message::Binary(data.into())).await {
                debug!(subdomain = %write_subdomain, error = %e, "WebSocket write error");
                break;
            }
        }
    });

    // Run the mux dispatch loop concurrently with open-stream request handling.
    // The read loop processes incoming frames from the device.
    // The open-stream handler processes requests from passthrough code.
    //
    // We use select! to handle both in the same task, which owns the MuxSession
    // without needing a mutex.
    run_session_loop(&mut session, incoming_rx, &mut open_stream_rx, &relay_state).await;

    // Clean up
    session_registry.remove(&subdomain);
    relay_state.remove_mux_connection(&subdomain);

    // Wait for read/write tasks to finish
    let _ = read_task.await;
    let _ = write_task.await;

    info!(subdomain = %subdomain, "Mux session ended");
}

/// Run the mux session's main loop. Handles:
/// - Incoming frames from the WebSocket (via incoming_rx)
/// - Open-stream requests from passthrough code (via open_stream_rx)
///
/// The session is owned by this function and not shared, avoiding mutex issues.
async fn run_session_loop(
    session: &mut MuxSession,
    mut incoming_rx: mpsc::Receiver<Frame>,
    open_stream_rx: &mut mpsc::Receiver<OpenStreamRequest>,
    relay_state: &Arc<crate::state::RelayState>,
) {
    info!(subdomain = %session.subdomain, "Mux session loop started");

    loop {
        tokio::select! {
            frame = incoming_rx.recv() => {
                match frame {
                    Some(f) => {
                        session.dispatch_incoming(f, relay_state).await;
                    }
                    None => {
                        // WebSocket closed
                        info!(
                            subdomain = %session.subdomain,
                            active_streams = session.active_stream_count(),
                            "Mux WebSocket closed, cleaning up"
                        );
                        break;
                    }
                }
            }
            req = open_stream_rx.recv() => {
                match req {
                    Some(open_req) => {
                        let result = session.open_stream().map(|stream| {
                            (session.handle(), stream)
                        });
                        let _ = open_req.reply.send(result);
                    }
                    None => {
                        // Registry dropped, shouldn't normally happen while session is alive
                        debug!(subdomain = %session.subdomain, "Open-stream channel closed");
                    }
                }
            }
        }
    }

    session.close_all_streams().await;
    relay_state.remove_mux_connection(&session.subdomain);
    relay_state
        .total_sessions_served
        .fetch_add(1, std::sync::atomic::Ordering::Relaxed);
}
