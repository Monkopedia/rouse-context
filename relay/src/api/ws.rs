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
use std::time::Duration;
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

/// Collected session parameters passed into `handle_mux_session` to avoid
/// exceeding clippy's argument-count limit.
struct SessionParams {
    subdomain: String,
    max_streams: u32,
    ping_interval: Duration,
    read_timeout: Duration,
    relay_state: Arc<crate::state::RelayState>,
    session_registry: Arc<SessionRegistry>,
    firestore: Arc<dyn crate::firestore::FirestoreClient>,
    firestore_has_record: bool,
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

    // mTLS client cert is sufficient authentication -- the cert was issued by
    // our relay CA during registration. If Firestore has no record (e.g. after
    // relay restart with InMemoryFirestore), we auto-create one below.
    let firestore_has_record = match state.firestore.get_device(&subdomain).await {
        Ok(_) => true,
        Err(crate::firestore::FirestoreError::NotFound(_)) => {
            warn!(
                subdomain = %subdomain,
                "Device not in Firestore (mTLS cert valid, will auto-create record on connect)"
            );
            false
        }
        Err(e) => {
            error!(subdomain = %subdomain, error = %e, "Firestore lookup failed");
            false
        }
    };

    let params = SessionParams {
        subdomain,
        max_streams: state.config.limits.max_streams_per_device,
        ping_interval: Duration::from_secs(state.config.limits.ws_ping_interval_secs),
        read_timeout: Duration::from_secs(state.config.limits.ws_read_timeout_secs),
        relay_state: state.relay_state.clone(),
        session_registry: state.session_registry.clone(),
        firestore: state.firestore.clone(),
        firestore_has_record,
    };

    info!(subdomain = %params.subdomain, "Device WebSocket upgrade accepted");

    ws.on_upgrade(move |socket| handle_mux_session(socket, params))
}

async fn handle_mux_session(socket: WebSocket, params: SessionParams) {
    let SessionParams {
        subdomain,
        max_streams,
        ping_interval,
        read_timeout,
        relay_state,
        session_registry,
        firestore,
        firestore_has_record,
    } = params;

    // If no Firestore record exists (e.g. after relay restart), auto-create one
    // with a placeholder FCM token. The device will send its real token shortly.
    if !firestore_has_record {
        let placeholder = crate::firestore::DeviceRecord {
            fcm_token: String::new(),
            firebase_uid: String::new(),
            public_key: String::new(),
            cert_expires: std::time::SystemTime::now() + std::time::Duration::from_secs(86400 * 90),
            registered_at: std::time::SystemTime::now(),
            last_rotation: None,
            renewal_nudge_sent: None,
            secret_prefix: None,
            integration_secrets: std::collections::HashMap::new(),
        };
        if let Err(e) = firestore.put_device(&subdomain, &placeholder).await {
            warn!(subdomain = %subdomain, error = %e, "Failed to auto-create Firestore record");
        } else {
            info!(subdomain = %subdomain, "Auto-created Firestore record from mTLS cert");
        }
    }
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

    // Register the session so passthrough can find it.
    // Order matters: insert into registry FIRST so try_open_stream() works,
    // THEN signal waiters via register_mux_connection(). Otherwise, FCM
    // waiters wake up but find no session entry and get StreamRefused.
    session_registry.insert(&subdomain, open_stream_tx);
    relay_state.register_mux_connection(&subdomain);

    info!(subdomain = %subdomain, "Mux session registered");

    let (mut ws_sender, mut ws_receiver) = socket.split();

    // Channel for the read loop to dispatch incoming frames
    let (incoming_tx, incoming_rx) = mpsc::channel::<Frame>(256);

    // Spawn WebSocket read task: reads WS messages, decodes frames, sends to incoming_tx.
    // Text messages are used as a control channel (e.g. FCM token registration).
    // A read timeout detects stale connections: if no message (including Pong
    // responses to our periodic Pings) arrives within `read_timeout`, the
    // connection is considered dead.
    let read_subdomain = subdomain.clone();
    let read_firestore = firestore.clone();
    let read_task = tokio::spawn(async move {
        loop {
            match tokio::time::timeout(read_timeout, ws_receiver.next()).await {
                Ok(Some(msg)) => match msg {
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
                    Ok(Message::Text(text)) => {
                        handle_control_message(&read_subdomain, &text, &read_firestore).await;
                    }
                    Ok(Message::Close(_)) => {
                        debug!(subdomain = %read_subdomain, "Device sent WS close");
                        break;
                    }
                    Ok(Message::Ping(_) | Message::Pong(_)) => {
                        // axum handles ping/pong automatically; these still reset our timeout
                    }
                    Err(e) => {
                        debug!(subdomain = %read_subdomain, error = %e, "WebSocket read error");
                        break;
                    }
                },
                Ok(None) => {
                    // Stream ended (WebSocket closed)
                    debug!(subdomain = %read_subdomain, "WebSocket stream ended");
                    break;
                }
                Err(_) => {
                    // Timeout: no message received within the deadline
                    info!(
                        subdomain = %read_subdomain,
                        timeout_secs = read_timeout.as_secs(),
                        "No WebSocket activity, closing stale connection"
                    );
                    break;
                }
            }
        }
        drop(incoming_tx);
    });

    // Spawn WebSocket write task: reads from frame_rx, encodes, sends to WS.
    // Also sends periodic Ping frames so the device's Pong responses keep the
    // read task's timeout from firing on idle-but-alive connections.
    let write_subdomain = subdomain.clone();
    let write_task = tokio::spawn(async move {
        let mut frame_rx = frame_rx;
        let mut ping_ticker = tokio::time::interval(ping_interval);
        // The first tick fires immediately; skip it so we don't ping on connect.
        ping_ticker.tick().await;
        loop {
            tokio::select! {
                frame = frame_rx.recv() => {
                    match frame {
                        Some(f) => {
                            let data = f.encode();
                            if let Err(e) = ws_sender.send(Message::Binary(data.into())).await {
                                debug!(subdomain = %write_subdomain, error = %e, "WebSocket write error");
                                break;
                            }
                        }
                        None => {
                            // Session closed, no more frames to send
                            break;
                        }
                    }
                }
                _ = ping_ticker.tick() => {
                    if let Err(e) = ws_sender.send(Message::Ping(vec![].into())).await {
                        debug!(subdomain = %write_subdomain, error = %e, "WebSocket ping error");
                        break;
                    }
                }
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

/// JSON control message sent by the device over a WebSocket text frame.
#[derive(serde::Deserialize)]
struct ControlMessage {
    #[serde(rename = "type")]
    msg_type: String,
    /// FCM registration token (present when msg_type == "fcm_token").
    #[serde(default)]
    token: String,
}

/// Handle a text WebSocket message as a control-plane command.
///
/// Currently supported:
/// - `{"type":"fcm_token","token":"..."}` -- updates the device's FCM token
///   in Firestore so the relay can wake it after a restart.
async fn handle_control_message(
    subdomain: &str,
    text: &str,
    firestore: &Arc<dyn crate::firestore::FirestoreClient>,
) {
    let msg: ControlMessage = match serde_json::from_str(text) {
        Ok(m) => m,
        Err(e) => {
            warn!(subdomain = %subdomain, error = %e, "Ignoring unparseable control message");
            return;
        }
    };

    match msg.msg_type.as_str() {
        "fcm_token" => {
            if msg.token.is_empty() {
                warn!(subdomain = %subdomain, "Received empty FCM token, ignoring");
                return;
            }
            // Update the existing Firestore record's FCM token
            match firestore.get_device(subdomain).await {
                Ok(mut record) => {
                    record.fcm_token = msg.token.clone();
                    if let Err(e) = firestore.put_device(subdomain, &record).await {
                        error!(subdomain = %subdomain, error = %e, "Failed to update FCM token");
                    } else {
                        info!(subdomain = %subdomain, "FCM token updated from device control message");
                    }
                }
                Err(e) => {
                    warn!(
                        subdomain = %subdomain,
                        error = %e,
                        "No Firestore record to update FCM token on"
                    );
                }
            }
        }
        other => {
            debug!(subdomain = %subdomain, msg_type = %other, "Unknown control message type");
        }
    }
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
                        break;
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

#[cfg(test)]
mod tests {
    use super::*;
    use crate::api::{self, AppState};
    use crate::config::RelayConfig;
    use crate::passthrough::SessionRegistry;
    use crate::state::RelayState;
    use axum::http::StatusCode;

    /// Mock Firestore that returns Ok for known subdomains and NotFound for others.
    struct MockFirestore {
        registered: Vec<String>,
    }

    #[async_trait::async_trait]
    impl crate::firestore::FirestoreClient for MockFirestore {
        async fn get_device(
            &self,
            subdomain: &str,
        ) -> Result<crate::firestore::DeviceRecord, crate::firestore::FirestoreError> {
            if self.registered.contains(&subdomain.to_string()) {
                Ok(crate::firestore::DeviceRecord {
                    fcm_token: "tok".to_string(),
                    firebase_uid: "uid".to_string(),
                    public_key: String::new(),
                    cert_expires: std::time::SystemTime::now(),
                    registered_at: std::time::SystemTime::now(),
                    last_rotation: None,
                    renewal_nudge_sent: None,
                    secret_prefix: None,
                    integration_secrets: std::collections::HashMap::new(),
                })
            } else {
                Err(crate::firestore::FirestoreError::NotFound(
                    subdomain.to_string(),
                ))
            }
        }
        async fn find_device_by_uid(
            &self,
            _uid: &str,
        ) -> Result<
            Option<(String, crate::firestore::DeviceRecord)>,
            crate::firestore::FirestoreError,
        > {
            Ok(None)
        }
        async fn put_device(
            &self,
            _s: &str,
            _r: &crate::firestore::DeviceRecord,
        ) -> Result<(), crate::firestore::FirestoreError> {
            Ok(())
        }
        async fn delete_device(&self, _s: &str) -> Result<(), crate::firestore::FirestoreError> {
            Ok(())
        }
        async fn put_pending_cert(
            &self,
            _s: &str,
            _p: &crate::firestore::PendingCert,
        ) -> Result<(), crate::firestore::FirestoreError> {
            Ok(())
        }
        async fn get_pending_cert(
            &self,
            s: &str,
        ) -> Result<crate::firestore::PendingCert, crate::firestore::FirestoreError> {
            Err(crate::firestore::FirestoreError::NotFound(s.to_string()))
        }
        async fn delete_pending_cert(
            &self,
            _s: &str,
        ) -> Result<(), crate::firestore::FirestoreError> {
            Ok(())
        }
        async fn list_devices(
            &self,
        ) -> Result<Vec<(String, crate::firestore::DeviceRecord)>, crate::firestore::FirestoreError>
        {
            Ok(Vec::new())
        }
        async fn list_pending_certs(
            &self,
        ) -> Result<Vec<(String, crate::firestore::PendingCert)>, crate::firestore::FirestoreError>
        {
            Ok(Vec::new())
        }
    }

    struct StubFcm;
    #[async_trait::async_trait]
    impl crate::fcm::FcmClient for StubFcm {
        async fn send_data_message(
            &self,
            _t: &str,
            _d: &crate::fcm::FcmData,
            _h: bool,
        ) -> Result<(), crate::fcm::FcmError> {
            Ok(())
        }
    }

    struct StubAcme;
    #[async_trait::async_trait]
    impl crate::acme::AcmeClient for StubAcme {
        async fn issue_certificate(
            &self,
            _s: &str,
            _csr_der: Option<&[u8]>,
        ) -> Result<crate::acme::CertificateBundle, crate::acme::AcmeError> {
            Ok(crate::acme::CertificateBundle {
                cert_pem: String::new(),
                private_key_pem: None,
            })
        }
    }

    struct StubFirebaseAuth;
    #[async_trait::async_trait]
    impl crate::firebase_auth::FirebaseAuth for StubFirebaseAuth {
        async fn verify_id_token(
            &self,
            _t: &str,
        ) -> Result<crate::firebase_auth::FirebaseClaims, crate::firebase_auth::FirebaseAuthError>
        {
            Err(crate::firebase_auth::FirebaseAuthError::InvalidToken(
                "stub".to_string(),
            ))
        }
    }

    fn build_test_state(registered_subdomains: Vec<String>) -> Arc<AppState> {
        Arc::new(AppState {
            relay_state: Arc::new(RelayState::new()),
            session_registry: Arc::new(SessionRegistry::new()),
            firestore: Arc::new(MockFirestore {
                registered: registered_subdomains,
            }),
            fcm: Arc::new(StubFcm),
            acme: Arc::new(StubAcme),
            firebase_auth: Arc::new(StubFirebaseAuth),
            subdomain_generator: crate::subdomain::SubdomainGenerator::new(),
            rate_limiter: crate::rate_limit::RateLimiter::new(
                crate::rate_limit::RateLimitConfig::default(),
            ),
            config: RelayConfig::default(),
            device_ca: None,
        })
    }

    /// Helper that mirrors the auth check from handle_ws (mTLS only, no
    /// Firestore gate), without requiring a WebSocket upgrade.
    async fn auth_check(
        _state: State<Arc<AppState>>,
        device: Option<axum::Extension<DeviceIdentity>>,
    ) -> Response {
        match device {
            Some(axum::Extension(_identity)) => StatusCode::OK.into_response(),
            None => ApiError::unauthorized("Valid client certificate required").into_response(),
        }
    }

    fn request(uri: &str) -> axum::http::Request<axum::body::Body> {
        axum::http::Request::builder()
            .uri(uri)
            .body(axum::body::Body::empty())
            .unwrap()
    }

    /// /status is accessible without any client certificate.
    #[tokio::test]
    async fn status_accessible_without_client_cert() {
        let app = api::build_router(build_test_state(vec![]));
        let resp = tower::ServiceExt::oneshot(app, request("/status"))
            .await
            .unwrap();
        assert_eq!(resp.status(), StatusCode::OK);
    }

    /// No DeviceIdentity extension (no client cert) -> 401.
    #[tokio::test]
    async fn ws_without_client_cert_returns_401() {
        let state = build_test_state(vec![]);
        let app = axum::Router::new()
            .route("/ws", axum::routing::get(auth_check))
            .with_state(state);

        let resp = tower::ServiceExt::oneshot(app, request("/ws"))
            .await
            .unwrap();
        assert_eq!(resp.status(), StatusCode::UNAUTHORIZED);
    }

    /// Valid cert for an unregistered subdomain -> 200.
    /// mTLS is sufficient auth; Firestore record is auto-created on connect.
    #[tokio::test]
    async fn ws_with_cert_for_unregistered_subdomain_accepted() {
        let state = build_test_state(vec![]); // no registered subdomains
        let app = axum::Router::new()
            .route("/ws", axum::routing::get(auth_check))
            .with_state(state)
            .layer(axum::Extension(DeviceIdentity {
                subdomain: "unknown-device".to_string(),
            }));

        let resp = tower::ServiceExt::oneshot(app, request("/ws"))
            .await
            .unwrap();
        assert_eq!(resp.status(), StatusCode::OK);
    }

    /// Valid cert for a registered subdomain -> accepted (200).
    #[tokio::test]
    async fn ws_with_cert_for_registered_subdomain_accepted() {
        let state = build_test_state(vec!["my-device".to_string()]);
        let app = axum::Router::new()
            .route("/ws", axum::routing::get(auth_check))
            .with_state(state)
            .layer(axum::Extension(DeviceIdentity {
                subdomain: "my-device".to_string(),
            }));

        let resp = tower::ServiceExt::oneshot(app, request("/ws"))
            .await
            .unwrap();
        assert_eq!(resp.status(), StatusCode::OK);
    }
}
