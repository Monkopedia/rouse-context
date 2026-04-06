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

    // Verify the subdomain is registered in Firestore
    match state.firestore.get_device(&subdomain).await {
        Ok(_) => {}
        Err(crate::firestore::FirestoreError::NotFound(_)) => {
            warn!(subdomain = %subdomain, "Device not registered in Firestore");
            return ApiError::forbidden("Device not registered").into_response();
        }
        Err(e) => {
            error!(subdomain = %subdomain, error = %e, "Firestore lookup failed");
            return ApiError::internal("Failed to verify device registration").into_response();
        }
    }

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
        ) -> Result<crate::acme::CertificateBundle, crate::acme::AcmeError> {
            Ok(crate::acme::CertificateBundle {
                cert_pem: String::new(),
                private_key_pem: String::new(),
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
        })
    }

    /// Helper that mirrors the auth + Firestore check from handle_ws,
    /// without requiring a WebSocket upgrade. This lets us test the
    /// auth/authz logic in isolation.
    async fn auth_check(
        State(state): State<Arc<AppState>>,
        device: Option<axum::Extension<DeviceIdentity>>,
    ) -> Response {
        let subdomain = match device {
            Some(axum::Extension(identity)) => identity.subdomain,
            None => {
                return ApiError::unauthorized("Valid client certificate required").into_response();
            }
        };
        match state.firestore.get_device(&subdomain).await {
            Ok(_) => StatusCode::OK.into_response(),
            Err(crate::firestore::FirestoreError::NotFound(_)) => {
                ApiError::forbidden("Device not registered").into_response()
            }
            Err(_) => ApiError::internal("Failed to verify device registration").into_response(),
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

    /// Valid cert for an unregistered subdomain -> 403.
    #[tokio::test]
    async fn ws_with_cert_for_unregistered_subdomain_returns_403() {
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
        assert_eq!(resp.status(), StatusCode::FORBIDDEN);
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
