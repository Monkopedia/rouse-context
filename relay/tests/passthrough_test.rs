//! Tests for client TCP passthrough to device mux streams.
//!
//! These tests verify:
//! - Client hello buffering and forwarding as DATA frame
//! - OPEN frame sent with correct SNI hostname
//! - Bidirectional data splice between client TCP and mux stream
//! - CLOSE teardown from either side
//! - FCM wakeup when device is offline
//! - Timeout when device does not connect after FCM

mod test_helpers;

use rouse_relay::mux::frame::{Frame, FrameType};
use rouse_relay::mux::lifecycle::{MuxHandle, MuxSession};
use rouse_relay::passthrough::{
    resolve_device_stream, splice_stream, OpenStreamRequest, PassthroughContext, PassthroughError,
    SessionRegistry,
};
use rouse_relay::rate_limit::FcmWakeThrottle;
use rouse_relay::state::RelayState;
use std::sync::Arc;
use std::time::{Duration, SystemTime};
use test_helpers::{MockFcm, MockFirestore};
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::TcpListener;
use tokio::sync::mpsc;

/// Helper: create a MuxSession, register it in both RelayState and SessionRegistry.
/// Returns the frame_rx so the test can inspect outgoing frames, and a task handle
/// for the session handler.
fn setup_device(
    relay_state: &Arc<RelayState>,
    registry: &Arc<SessionRegistry>,
    subdomain: &str,
    max_streams: u32,
) -> mpsc::Receiver<Frame> {
    let mut session = MuxSession::new(subdomain.to_string(), max_streams);
    let frame_rx = session.take_frame_rx().unwrap();

    let (open_tx, mut open_rx) = mpsc::channel::<OpenStreamRequest>(16);

    relay_state.register_mux_connection(subdomain);
    registry.insert(subdomain, open_tx);

    // Spawn a task to handle open-stream requests
    tokio::spawn(async move {
        while let Some(req) = open_rx.recv().await {
            let result = session
                .open_stream()
                .map(|stream| (session.handle(), stream));
            let _ = req.reply.send(result);
        }
    });

    frame_rx
}

fn make_ctx(
    relay_state: Arc<RelayState>,
    registry: Arc<SessionRegistry>,
    firestore: Arc<MockFirestore>,
    fcm: Arc<MockFcm>,
) -> PassthroughContext {
    PassthroughContext {
        relay_state,
        session_registry: registry,
        firestore,
        fcm,
        relay_hostname: "relay.rousecontext.com".to_string(),
        fcm_wakeup_timeout: Duration::from_secs(5),
        fcm_wake_throttle: Arc::new(FcmWakeThrottle::new(Duration::from_secs(30))),
    }
}

#[tokio::test]
async fn open_frame_sent_with_sni_hostname() {
    let relay_state = Arc::new(RelayState::new());
    let registry = Arc::new(SessionRegistry::new());

    let mut frame_rx = setup_device(&relay_state, &registry, "brave-falcon", 8);

    let ctx = make_ctx(
        relay_state,
        registry,
        Arc::new(MockFirestore::new()),
        Arc::new(MockFcm::new()),
    );

    let sni = "brave-falcon.rousecontext.com";
    let result = resolve_device_stream(&ctx, "brave-falcon", sni, "").await;

    assert!(result.is_ok());
    let resolved = result.unwrap();
    assert_eq!(resolved.stream_id, 1);

    // The OPEN frame should have been sent
    let frame = frame_rx.recv().await.unwrap();
    assert_eq!(frame.frame_type, FrameType::Open);
    assert_eq!(frame.stream_id, 1);
    assert_eq!(
        String::from_utf8(frame.payload).unwrap(),
        "brave-falcon.rousecontext.com"
    );
}

#[tokio::test]
async fn bidirectional_data_splice() {
    // Create a TCP pair using a loopback listener
    let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let addr = listener.local_addr().unwrap();

    let client_task =
        tokio::spawn(async move { tokio::net::TcpStream::connect(addr).await.unwrap() });

    let (server_stream, _) = listener.accept().await.unwrap();
    let mut client_stream = client_task.await.unwrap();

    // Set up mux handle and stream channel
    let (mux_frame_tx, mut mux_frame_rx) = mpsc::channel::<Frame>(64);
    let (stream_tx, stream_rx) = mpsc::channel::<Frame>(64);

    let mux_handle = MuxHandle {
        frame_tx: mux_frame_tx,
        max_streams: 8,
    };

    let client_hello = b"fake-client-hello";
    let stream_id = 1;

    // Run splice in background
    let splice_handle = tokio::spawn(async move {
        splice_stream(
            server_stream,
            client_hello,
            stream_id,
            &mux_handle,
            stream_rx,
        )
        .await
    });

    // Verify ClientHello was forwarded as DATA
    let frame = mux_frame_rx.recv().await.unwrap();
    assert_eq!(frame.frame_type, FrameType::Data);
    assert_eq!(frame.stream_id, 1);
    assert_eq!(frame.payload, b"fake-client-hello");

    // Client -> Device: write data from client TCP
    client_stream.write_all(b"client-data").await.unwrap();

    let frame = mux_frame_rx.recv().await.unwrap();
    assert_eq!(frame.frame_type, FrameType::Data);
    assert_eq!(frame.payload, b"client-data");

    // Device -> Client: send DATA frame from mux
    stream_tx
        .send(Frame {
            frame_type: FrameType::Data,
            stream_id: 1,
            payload: b"device-response".to_vec(),
        })
        .await
        .unwrap();

    let mut buf = vec![0u8; 1024];
    let n = client_stream.read(&mut buf).await.unwrap();
    assert_eq!(&buf[..n], b"device-response");

    // Device sends CLOSE
    stream_tx
        .send(Frame {
            frame_type: FrameType::Close,
            stream_id: 1,
            payload: Vec::new(),
        })
        .await
        .unwrap();

    // Splice should complete
    let result = splice_handle.await.unwrap();
    assert!(result.is_ok());
}

#[tokio::test]
async fn client_disconnect_sends_close_to_device() {
    let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let addr = listener.local_addr().unwrap();

    let client_task = tokio::spawn(async move {
        let mut s = tokio::net::TcpStream::connect(addr).await.unwrap();
        // Write some data, then close
        s.write_all(b"hello").await.unwrap();
        drop(s);
    });

    let (server_stream, _) = listener.accept().await.unwrap();
    client_task.await.unwrap();

    let (mux_frame_tx, mut mux_frame_rx) = mpsc::channel::<Frame>(64);
    let (_stream_tx, stream_rx) = mpsc::channel::<Frame>(64);

    let mux_handle = MuxHandle {
        frame_tx: mux_frame_tx,
        max_streams: 8,
    };

    let splice_handle = tokio::spawn(async move {
        splice_stream(server_stream, b"ch", 1, &mux_handle, stream_rx).await
    });

    // ClientHello DATA
    let f = mux_frame_rx.recv().await.unwrap();
    assert_eq!(f.frame_type, FrameType::Data);

    // Client data
    let f = mux_frame_rx.recv().await.unwrap();
    assert_eq!(f.frame_type, FrameType::Data);
    assert_eq!(f.payload, b"hello");

    // Client disconnect -> CLOSE frame sent
    let f = mux_frame_rx.recv().await.unwrap();
    assert_eq!(f.frame_type, FrameType::Close);
    assert_eq!(f.stream_id, 1);

    let result = splice_handle.await.unwrap();
    assert!(result.is_ok());
}

#[tokio::test]
async fn fcm_sent_when_device_offline() {
    let relay_state = Arc::new(RelayState::new());
    let registry = Arc::new(SessionRegistry::new());

    let device_record = rouse_relay::firestore::DeviceRecord {
        fcm_token: "fcm-token-123".to_string(),
        firebase_uid: "uid-1".to_string(),
        public_key: "key".to_string(),
        cert_expires: SystemTime::now() + Duration::from_secs(86400),
        registered_at: SystemTime::now(),
        last_rotation: None,
        renewal_nudge_sent: None,
        secret_prefix: None,
        valid_secrets: Vec::new(),
        integration_secrets: std::collections::HashMap::new(),
    };

    let firestore = Arc::new(MockFirestore::new().with_device("brave-falcon", device_record));
    let fcm = Arc::new(MockFcm::new());

    let ctx = PassthroughContext {
        relay_state,
        session_registry: registry,
        firestore,
        fcm: fcm.clone(),
        relay_hostname: "relay.rousecontext.com".to_string(),
        fcm_wakeup_timeout: Duration::from_millis(200),
        fcm_wake_throttle: Arc::new(FcmWakeThrottle::new(Duration::from_secs(30))),
    };

    // Device is NOT online, so FCM should be sent.
    // We don't simulate the device connecting back, so this should timeout.
    let result =
        resolve_device_stream(&ctx, "brave-falcon", "brave-falcon.rousecontext.com", "").await;

    // Should have timed out
    assert!(matches!(result, Err(PassthroughError::WakeupTimeout)));

    // FCM should have been sent
    let sent = fcm.sent.lock().unwrap();
    assert_eq!(sent.len(), 1);
    assert_eq!(sent[0].0, "fcm-token-123");
    assert!(sent[0].2); // high_priority = true
}

#[tokio::test]
async fn fcm_timeout_returns_error() {
    let relay_state = Arc::new(RelayState::new());
    let registry = Arc::new(SessionRegistry::new());

    let device_record = rouse_relay::firestore::DeviceRecord {
        fcm_token: "token".to_string(),
        firebase_uid: "uid".to_string(),
        public_key: "key".to_string(),
        cert_expires: SystemTime::now() + Duration::from_secs(86400),
        registered_at: SystemTime::now(),
        last_rotation: None,
        renewal_nudge_sent: None,
        secret_prefix: None,
        valid_secrets: Vec::new(),
        integration_secrets: std::collections::HashMap::new(),
    };
    let firestore = Arc::new(MockFirestore::new().with_device("test-sub", device_record));
    let fcm = Arc::new(MockFcm::new());

    let ctx = PassthroughContext {
        relay_state,
        session_registry: registry,
        firestore,
        fcm,
        relay_hostname: "relay.rousecontext.com".to_string(),
        fcm_wakeup_timeout: Duration::from_millis(50),
        fcm_wake_throttle: Arc::new(FcmWakeThrottle::new(Duration::from_secs(30))),
    };

    let result = resolve_device_stream(&ctx, "test-sub", "test-sub.rousecontext.com", "").await;

    assert!(matches!(result, Err(PassthroughError::WakeupTimeout)));
}

#[tokio::test]
async fn device_not_found_returns_error() {
    let relay_state = Arc::new(RelayState::new());
    let registry = Arc::new(SessionRegistry::new());

    let ctx = make_ctx(
        relay_state,
        registry,
        Arc::new(MockFirestore::new()),
        Arc::new(MockFcm::new()),
    );

    let result =
        resolve_device_stream(&ctx, "nonexistent", "nonexistent.rousecontext.com", "").await;

    assert!(matches!(result, Err(PassthroughError::DeviceNotFound)));
}

#[tokio::test]
async fn cold_client_fcm_then_device_connects() {
    let relay_state = Arc::new(RelayState::new());
    let registry = Arc::new(SessionRegistry::new());

    let device_record = rouse_relay::firestore::DeviceRecord {
        fcm_token: "fcm-cold".to_string(),
        firebase_uid: "uid".to_string(),
        public_key: "key".to_string(),
        cert_expires: SystemTime::now() + Duration::from_secs(86400),
        registered_at: SystemTime::now(),
        last_rotation: None,
        renewal_nudge_sent: None,
        secret_prefix: None,
        valid_secrets: Vec::new(),
        integration_secrets: std::collections::HashMap::new(),
    };
    let firestore = Arc::new(MockFirestore::new().with_device("cold-dev", device_record));
    let fcm = Arc::new(MockFcm::new());

    let ctx = PassthroughContext {
        relay_state: relay_state.clone(),
        session_registry: registry.clone(),
        firestore,
        fcm: fcm.clone(),
        relay_hostname: "relay.rousecontext.com".to_string(),
        fcm_wakeup_timeout: Duration::from_secs(5),
        fcm_wake_throttle: Arc::new(FcmWakeThrottle::new(Duration::from_secs(30))),
    };

    // Simulate the device connecting after a short delay
    let rs = relay_state.clone();
    let reg = registry.clone();
    tokio::spawn(async move {
        tokio::time::sleep(Duration::from_millis(50)).await;
        // Device wakes up and establishes mux
        let mut session = MuxSession::new("cold-dev".to_string(), 8);
        let (open_tx, mut open_rx) = mpsc::channel::<OpenStreamRequest>(16);
        reg.insert("cold-dev", open_tx);
        rs.register_mux_connection("cold-dev");
        // Handle open-stream requests
        while let Some(req) = open_rx.recv().await {
            let result = session
                .open_stream()
                .map(|stream| (session.handle(), stream));
            let _ = req.reply.send(result);
        }
    });

    let result = resolve_device_stream(&ctx, "cold-dev", "cold-dev.rousecontext.com", "").await;

    assert!(result.is_ok());
    let resolved = result.unwrap();
    assert_eq!(resolved.stream_id, 1);

    // FCM was sent
    let sent = fcm.sent.lock().unwrap();
    assert_eq!(sent.len(), 1);
    assert_eq!(sent[0].0, "fcm-cold");
}

// ---------------------------------------------------------------------------
// Secret prefix validation tests
// ---------------------------------------------------------------------------

#[tokio::test]
async fn valid_secret_prefix_proceeds_to_wake() {
    let relay_state = Arc::new(RelayState::new());
    let registry = Arc::new(SessionRegistry::new());

    let mut device_record = make_device_record("fcm-secret");
    device_record.secret_prefix = Some("brave-falcon".to_string());

    let firestore = Arc::new(MockFirestore::new().with_device("cool-penguin", device_record));
    let fcm = Arc::new(MockFcm::new());

    let ctx = PassthroughContext {
        relay_state: relay_state.clone(),
        session_registry: registry.clone(),
        firestore,
        fcm: fcm.clone(),
        relay_hostname: "relay.rousecontext.com".to_string(),
        fcm_wakeup_timeout: Duration::from_millis(200),
        fcm_wake_throttle: Arc::new(FcmWakeThrottle::new(Duration::from_secs(30))),
    };

    // Valid secret prefix but device offline -> should send FCM and timeout
    let result = resolve_device_stream(
        &ctx,
        "cool-penguin",
        "brave-falcon.cool-penguin.rousecontext.com",
        "brave-falcon",
    )
    .await;

    // Should timeout (not InvalidSecret), proving it got past the secret check
    assert!(matches!(result, Err(PassthroughError::WakeupTimeout)));

    // FCM was sent (secret was valid)
    let sent = fcm.sent.lock().unwrap();
    assert_eq!(sent.len(), 1);
}

#[tokio::test]
async fn invalid_secret_prefix_rejects_without_fcm() {
    let relay_state = Arc::new(RelayState::new());
    let registry = Arc::new(SessionRegistry::new());

    let mut device_record = make_device_record("fcm-secret2");
    device_record.secret_prefix = Some("brave-falcon".to_string());

    let firestore = Arc::new(MockFirestore::new().with_device("cool-penguin", device_record));
    let fcm = Arc::new(MockFcm::new());

    let ctx = PassthroughContext {
        relay_state: relay_state.clone(),
        session_registry: registry.clone(),
        firestore,
        fcm: fcm.clone(),
        relay_hostname: "relay.rousecontext.com".to_string(),
        fcm_wakeup_timeout: Duration::from_millis(200),
        fcm_wake_throttle: Arc::new(FcmWakeThrottle::new(Duration::from_secs(30))),
    };

    // Wrong secret prefix
    let result = resolve_device_stream(
        &ctx,
        "cool-penguin",
        "wrong-secret.cool-penguin.rousecontext.com",
        "wrong-secret",
    )
    .await;

    assert!(
        matches!(result, Err(PassthroughError::InvalidSecret)),
        "Expected InvalidSecret"
    );

    // FCM should NOT have been sent
    let sent = fcm.sent.lock().unwrap();
    assert!(sent.is_empty(), "No FCM should be sent for invalid secret");
}

#[tokio::test]
async fn valid_secret_with_online_device_opens_stream() {
    let relay_state = Arc::new(RelayState::new());
    let registry = Arc::new(SessionRegistry::new());

    let mut device_record = make_device_record("fcm-online");
    device_record.secret_prefix = Some("brave-falcon".to_string());

    // Device is online
    let mut _frame_rx = setup_device(&relay_state, &registry, "cool-penguin", 8);

    let firestore = Arc::new(MockFirestore::new().with_device("cool-penguin", device_record));
    let fcm = Arc::new(MockFcm::new());

    let ctx = make_ctx(relay_state, registry, firestore, fcm);

    let sni = "brave-falcon.cool-penguin.rousecontext.com";
    let result = resolve_device_stream(&ctx, "cool-penguin", sni, "brave-falcon").await;

    assert!(result.is_ok());
}

// ---------------------------------------------------------------------------
// valid_secrets list tests
// ---------------------------------------------------------------------------

#[tokio::test]
async fn valid_secret_in_list_proceeds() {
    let relay_state = Arc::new(RelayState::new());
    let registry = Arc::new(SessionRegistry::new());

    let mut device_record = make_device_record("fcm-int");
    device_record.valid_secrets = vec!["brave-health".to_string(), "swift-outreach".to_string()];

    // Device is online
    let mut _frame_rx = setup_device(&relay_state, &registry, "cool-penguin", 8);

    let firestore = Arc::new(MockFirestore::new().with_device("cool-penguin", device_record));
    let fcm = Arc::new(MockFcm::new());
    let ctx = make_ctx(relay_state, registry, firestore, fcm);

    let sni = "brave-health.cool-penguin.rousecontext.com";
    let result = resolve_device_stream(&ctx, "cool-penguin", sni, "brave-health").await;

    assert!(result.is_ok());
}

#[tokio::test]
async fn secret_not_in_valid_list_rejects() {
    let relay_state = Arc::new(RelayState::new());
    let registry = Arc::new(SessionRegistry::new());

    let mut device_record = make_device_record("fcm-int2");
    device_record.valid_secrets = vec!["brave-health".to_string()];

    let firestore = Arc::new(MockFirestore::new().with_device("cool-penguin", device_record));
    let fcm = Arc::new(MockFcm::new());
    let ctx = make_ctx(relay_state, registry, firestore, fcm);

    let result = resolve_device_stream(
        &ctx,
        "cool-penguin",
        "wrong-health.cool-penguin.rousecontext.com",
        "wrong-health",
    )
    .await;

    assert!(
        matches!(result, Err(PassthroughError::InvalidSecret)),
        "Expected InvalidSecret for secret not in valid_secrets list"
    );
}

#[tokio::test]
async fn legacy_secret_prefix_fallback_when_no_valid_secrets() {
    let relay_state = Arc::new(RelayState::new());
    let registry = Arc::new(SessionRegistry::new());

    let mut device_record = make_device_record("fcm-legacy");
    device_record.secret_prefix = Some("brave-falcon".to_string());
    // valid_secrets is empty (legacy device)

    // Device is online
    let mut _frame_rx = setup_device(&relay_state, &registry, "cool-penguin", 8);

    let firestore = Arc::new(MockFirestore::new().with_device("cool-penguin", device_record));
    let fcm = Arc::new(MockFcm::new());
    let ctx = make_ctx(relay_state, registry, firestore, fcm);

    let sni = "brave-falcon.cool-penguin.rousecontext.com";
    let result = resolve_device_stream(&ctx, "cool-penguin", sni, "brave-falcon").await;

    assert!(result.is_ok());
}

#[tokio::test]
async fn legacy_secret_prefix_wrong_value_rejects() {
    let relay_state = Arc::new(RelayState::new());
    let registry = Arc::new(SessionRegistry::new());

    let mut device_record = make_device_record("fcm-legacy2");
    device_record.secret_prefix = Some("brave-falcon".to_string());
    // valid_secrets is empty (legacy device)

    let firestore = Arc::new(MockFirestore::new().with_device("cool-penguin", device_record));
    let fcm = Arc::new(MockFcm::new());
    let ctx = make_ctx(relay_state, registry, firestore, fcm);

    let result = resolve_device_stream(
        &ctx,
        "cool-penguin",
        "wrong-secret.cool-penguin.rousecontext.com",
        "wrong-secret",
    )
    .await;

    assert!(
        matches!(result, Err(PassthroughError::InvalidSecret)),
        "Expected InvalidSecret for wrong legacy secret"
    );
}

#[tokio::test]
async fn valid_secrets_take_priority_over_secret_prefix() {
    // Device has both valid_secrets AND secret_prefix.
    // valid_secrets should be checked first.
    let relay_state = Arc::new(RelayState::new());
    let registry = Arc::new(SessionRegistry::new());

    let mut device_record = make_device_record("fcm-both");
    device_record.secret_prefix = Some("old-falcon".to_string());
    device_record.valid_secrets = vec!["brave-health".to_string()];

    // Device is online
    let mut _frame_rx = setup_device(&relay_state, &registry, "cool-penguin", 8);

    let firestore = Arc::new(MockFirestore::new().with_device("cool-penguin", device_record));
    let fcm = Arc::new(MockFcm::new());
    let ctx = make_ctx(relay_state, registry, firestore, fcm);

    // Using a valid secret should work
    let sni = "brave-health.cool-penguin.rousecontext.com";
    let result = resolve_device_stream(&ctx, "cool-penguin", sni, "brave-health").await;
    assert!(result.is_ok());
}

#[tokio::test]
async fn old_secret_prefix_rejected_when_valid_secrets_present() {
    // Device has both valid_secrets AND secret_prefix.
    // Using the old secret_prefix value should be rejected because
    // valid_secrets take priority.
    let relay_state = Arc::new(RelayState::new());
    let registry = Arc::new(SessionRegistry::new());

    let mut device_record = make_device_record("fcm-both2");
    device_record.secret_prefix = Some("old-falcon".to_string());
    device_record.valid_secrets = vec!["brave-health".to_string()];

    let firestore = Arc::new(MockFirestore::new().with_device("cool-penguin", device_record));
    let fcm = Arc::new(MockFcm::new());
    let ctx = make_ctx(relay_state, registry, firestore, fcm);

    // Using the old secret_prefix should NOT work (valid_secrets are checked first)
    let result = resolve_device_stream(
        &ctx,
        "cool-penguin",
        "old-falcon.cool-penguin.rousecontext.com",
        "old-falcon",
    )
    .await;

    assert!(
        matches!(result, Err(PassthroughError::InvalidSecret)),
        "Old secret_prefix should be rejected when valid_secrets are present"
    );
}

// ---------------------------------------------------------------------------
// Cold wake integration tests: resolve + splice end-to-end
// ---------------------------------------------------------------------------

/// Helper to create a device record with sensible defaults.
fn make_device_record(fcm_token: &str) -> rouse_relay::firestore::DeviceRecord {
    rouse_relay::firestore::DeviceRecord {
        fcm_token: fcm_token.to_string(),
        firebase_uid: "uid".to_string(),
        public_key: "key".to_string(),
        cert_expires: SystemTime::now() + Duration::from_secs(86400),
        registered_at: SystemTime::now(),
        last_rotation: None,
        renewal_nudge_sent: None,
        secret_prefix: None,
        valid_secrets: Vec::new(),
        integration_secrets: std::collections::HashMap::new(),
    }
}

/// Helper to create a TCP socket pair via a loopback listener.
/// Returns (server_stream, client_stream).
async fn tcp_pair() -> (tokio::net::TcpStream, tokio::net::TcpStream) {
    let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let addr = listener.local_addr().unwrap();
    let client_task =
        tokio::spawn(async move { tokio::net::TcpStream::connect(addr).await.unwrap() });
    let (server, _) = listener.accept().await.unwrap();
    let client = client_task.await.unwrap();
    (server, client)
}

/// Simulate a device waking up after a delay: creates MuxSession, registers in
/// both SessionRegistry and RelayState, and spawns a task to handle stream-open
/// requests. Returns the frame_rx for inspecting outgoing frames.
fn spawn_delayed_device(
    relay_state: Arc<RelayState>,
    registry: Arc<SessionRegistry>,
    subdomain: &str,
    max_streams: u32,
    delay: Duration,
) -> mpsc::Receiver<Frame> {
    let sub = subdomain.to_string();
    let mut session = MuxSession::new(sub.clone(), max_streams);
    let frame_rx = session.take_frame_rx().unwrap();

    let rs = relay_state;
    let reg = registry;
    tokio::spawn(async move {
        tokio::time::sleep(delay).await;
        let (open_tx, mut open_rx) = mpsc::channel::<OpenStreamRequest>(16);
        // Insert into registry BEFORE register_mux_connection so that
        // when subscribe_connect fires, the session is already available.
        reg.insert(&sub, open_tx);
        rs.register_mux_connection(&sub);
        while let Some(req) = open_rx.recv().await {
            let result = session
                .open_stream()
                .map(|stream| (session.handle(), stream));
            let _ = req.reply.send(result);
        }
    });

    frame_rx
}

/// Test 1: Cold wake with ClientHello buffering and full data round-trip.
///
/// Validates the complete cold-wake path: resolve_device_stream sends FCM and
/// waits for the device, then splice_stream forwards the buffered ClientHello
/// as the first DATA frame. Device responses flow back to the client socket.
///
/// This test uses resolve_device_stream for the wake/open phase, then manually
/// creates splice channels (like the bidirectional_data_splice test) so we can
/// inject "device" responses into the stream_rx channel.
#[tokio::test]
async fn cold_wake_full_data_roundtrip() {
    let relay_state = Arc::new(RelayState::new());
    let registry = Arc::new(SessionRegistry::new());

    let firestore =
        Arc::new(MockFirestore::new().with_device("wake-dev", make_device_record("fcm-wake")));
    let fcm = Arc::new(MockFcm::new());

    let ctx = PassthroughContext {
        relay_state: relay_state.clone(),
        session_registry: registry.clone(),
        firestore,
        fcm: fcm.clone(),
        relay_hostname: "relay.rousecontext.com".to_string(),
        fcm_wakeup_timeout: Duration::from_secs(5),
        fcm_wake_throttle: Arc::new(FcmWakeThrottle::new(Duration::from_secs(30))),
    };

    // Device wakes after 50ms
    let mut frame_rx = spawn_delayed_device(
        relay_state.clone(),
        registry.clone(),
        "wake-dev",
        8,
        Duration::from_millis(50),
    );

    // Phase 1: resolve (waits for FCM + device connect)
    let sni = "wake-dev.rousecontext.com";
    let resolved = resolve_device_stream(&ctx, "wake-dev", sni, "")
        .await
        .unwrap();
    assert_eq!(resolved.stream_id, 1);

    // Verify OPEN frame was sent with correct SNI
    let open_frame = frame_rx.recv().await.unwrap();
    assert_eq!(open_frame.frame_type, FrameType::Open);
    assert_eq!(open_frame.stream_id, 1);
    assert_eq!(
        String::from_utf8(open_frame.payload).unwrap(),
        "wake-dev.rousecontext.com"
    );

    // Phase 2: splice with manually-managed stream channel for device responses.
    // We use the MuxHandle from resolve (to send frames to device) but create
    // our own stream_tx/stream_rx pair so we can inject device->client data.
    let (stream_tx, stream_rx) = mpsc::channel::<Frame>(64);

    let (server_stream, mut client_stream) = tcp_pair().await;
    let client_hello = b"fake-tls-client-hello-bytes";
    let mux_handle = resolved.handle.clone();
    let stream_id = resolved.stream_id;

    let splice_handle = tokio::spawn(async move {
        splice_stream(
            server_stream,
            client_hello,
            stream_id,
            &mux_handle,
            stream_rx,
        )
        .await
    });

    // Verify ClientHello arrives as first DATA frame on the mux
    let data_frame = frame_rx.recv().await.unwrap();
    assert_eq!(data_frame.frame_type, FrameType::Data);
    assert_eq!(data_frame.stream_id, 1);
    assert_eq!(data_frame.payload, b"fake-tls-client-hello-bytes");

    // Simulate device sending response back through the stream channel
    stream_tx
        .send(Frame {
            frame_type: FrameType::Data,
            stream_id: 1,
            payload: b"server-hello-response".to_vec(),
        })
        .await
        .unwrap();

    // Verify response arrives on the client TCP socket
    let mut buf = vec![0u8; 1024];
    let n = client_stream.read(&mut buf).await.unwrap();
    assert_eq!(&buf[..n], b"server-hello-response");

    // Client writes more data
    client_stream.write_all(b"more-client-data").await.unwrap();

    let data_frame = frame_rx.recv().await.unwrap();
    assert_eq!(data_frame.frame_type, FrameType::Data);
    assert_eq!(data_frame.payload, b"more-client-data");

    // Device sends CLOSE to end the session
    stream_tx
        .send(Frame {
            frame_type: FrameType::Close,
            stream_id: 1,
            payload: Vec::new(),
        })
        .await
        .unwrap();

    // Splice should complete cleanly
    let result = splice_handle.await.unwrap();
    assert!(result.is_ok());

    // Verify FCM was sent exactly once with high priority
    let sent = fcm.sent.lock().unwrap();
    assert_eq!(sent.len(), 1);
    assert_eq!(sent[0].0, "fcm-wake");
    assert!(sent[0].2); // high_priority = true
}

/// Test 2: Multiple AI clients wake the same device concurrently.
///
/// Two clients connect to the same subdomain while the device is offline.
/// The device wakes once (one FCM). Both clients should get independent streams
/// with distinct stream IDs.
#[tokio::test]
async fn multiple_clients_wake_same_device() {
    let relay_state = Arc::new(RelayState::new());
    let registry = Arc::new(SessionRegistry::new());

    let firestore =
        Arc::new(MockFirestore::new().with_device("multi-dev", make_device_record("fcm-multi")));
    let fcm = Arc::new(MockFcm::new());

    let ctx = Arc::new(PassthroughContext {
        relay_state: relay_state.clone(),
        session_registry: registry.clone(),
        firestore,
        fcm: fcm.clone(),
        relay_hostname: "relay.rousecontext.com".to_string(),
        fcm_wakeup_timeout: Duration::from_secs(5),
        fcm_wake_throttle: Arc::new(FcmWakeThrottle::new(Duration::from_secs(30))),
    });

    // Device wakes after 100ms
    let mut frame_rx = spawn_delayed_device(
        relay_state.clone(),
        registry.clone(),
        "multi-dev",
        8,
        Duration::from_millis(100),
    );

    // Launch two concurrent resolve calls
    let ctx1 = ctx.clone();
    let resolve1 = tokio::spawn(async move {
        resolve_device_stream(&ctx1, "multi-dev", "multi-dev.rousecontext.com", "").await
    });

    let ctx2 = ctx.clone();
    let resolve2 = tokio::spawn(async move {
        resolve_device_stream(&ctx2, "multi-dev", "multi-dev.rousecontext.com", "").await
    });

    let r1 = resolve1.await.unwrap().unwrap();
    let r2 = resolve2.await.unwrap().unwrap();

    // Both should succeed with different stream IDs
    assert_ne!(r1.stream_id, r2.stream_id);
    // Stream IDs should be 1 and 2 (in some order)
    let mut ids = vec![r1.stream_id, r2.stream_id];
    ids.sort();
    assert_eq!(ids, vec![1, 2]);

    // Verify OPEN frames were sent for both streams
    let f1 = frame_rx.recv().await.unwrap();
    let f2 = frame_rx.recv().await.unwrap();
    assert_eq!(f1.frame_type, FrameType::Open);
    assert_eq!(f2.frame_type, FrameType::Open);
    let mut open_ids = vec![f1.stream_id, f2.stream_id];
    open_ids.sort();
    assert_eq!(open_ids, vec![1, 2]);

    // FCM should have been sent (at least once, possibly twice since both
    // clients fire independently before the device connects). The key invariant
    // is that both streams opened successfully.
    let sent = fcm.sent.lock().unwrap();
    assert!(!sent.is_empty());
    // All FCM messages should target the same token
    for msg in sent.iter() {
        assert_eq!(msg.0, "fcm-multi");
    }
}

/// Test 3: Device connects after FCM but has exhausted max_streams (0).
///
/// The device wakes and registers, but with max_streams=0 so no stream can be
/// opened. The client should get StreamRefused.
#[tokio::test]
async fn cold_wake_stream_refused_max_streams_zero() {
    let relay_state = Arc::new(RelayState::new());
    let registry = Arc::new(SessionRegistry::new());

    let firestore =
        Arc::new(MockFirestore::new().with_device("full-dev", make_device_record("fcm-full")));
    let fcm = Arc::new(MockFcm::new());

    let ctx = PassthroughContext {
        relay_state: relay_state.clone(),
        session_registry: registry.clone(),
        firestore,
        fcm: fcm.clone(),
        relay_hostname: "relay.rousecontext.com".to_string(),
        fcm_wakeup_timeout: Duration::from_secs(5),
        fcm_wake_throttle: Arc::new(FcmWakeThrottle::new(Duration::from_secs(30))),
    };

    // Device wakes with max_streams=0
    let _frame_rx = spawn_delayed_device(
        relay_state.clone(),
        registry.clone(),
        "full-dev",
        0, // no streams allowed
        Duration::from_millis(50),
    );

    let result = resolve_device_stream(&ctx, "full-dev", "full-dev.rousecontext.com", "").await;

    assert!(
        matches!(result, Err(PassthroughError::StreamRefused)),
        "Expected StreamRefused"
    );

    // FCM should still have been sent (device was offline)
    let sent = fcm.sent.lock().unwrap();
    assert_eq!(sent.len(), 1);
    assert_eq!(sent[0].0, "fcm-full");
}

/// Test 4: ClientHello bytes are preserved exactly through the wake delay.
///
/// Generates a realistic-size byte buffer (517 bytes, typical TLS ClientHello
/// size), goes through the cold wake path, and verifies the exact bytes arrive
/// on the mux as the first DATA frame with no truncation or corruption.
#[tokio::test]
async fn client_hello_preserved_through_wake_delay() {
    let relay_state = Arc::new(RelayState::new());
    let registry = Arc::new(SessionRegistry::new());

    let firestore =
        Arc::new(MockFirestore::new().with_device("exact-dev", make_device_record("fcm-exact")));
    let fcm = Arc::new(MockFcm::new());

    let ctx = PassthroughContext {
        relay_state: relay_state.clone(),
        session_registry: registry.clone(),
        firestore,
        fcm: fcm.clone(),
        relay_hostname: "relay.rousecontext.com".to_string(),
        fcm_wakeup_timeout: Duration::from_secs(5),
        fcm_wake_throttle: Arc::new(FcmWakeThrottle::new(Duration::from_secs(30))),
    };

    // Generate a realistic-looking ClientHello (517 bytes with TLS record header)
    let mut client_hello = vec![0u8; 517];
    // TLS record header: ContentType=Handshake(0x16), Version=TLS1.0(0x0301)
    client_hello[0] = 0x16;
    client_hello[1] = 0x03;
    client_hello[2] = 0x01;
    // Length of handshake message (517 - 5 = 512)
    client_hello[3] = 0x02;
    client_hello[4] = 0x00;
    // Handshake type: ClientHello(0x01)
    client_hello[5] = 0x01;
    // Fill the rest with a recognizable pattern
    for (i, byte) in client_hello.iter_mut().enumerate().skip(6) {
        *byte = (i % 256) as u8;
    }

    // Device wakes after 80ms
    let mut frame_rx = spawn_delayed_device(
        relay_state.clone(),
        registry.clone(),
        "exact-dev",
        8,
        Duration::from_millis(80),
    );

    // Resolve through the cold wake path
    let resolved = resolve_device_stream(&ctx, "exact-dev", "exact-dev.rousecontext.com", "")
        .await
        .unwrap();

    // Skip the OPEN frame
    let open_frame = frame_rx.recv().await.unwrap();
    assert_eq!(open_frame.frame_type, FrameType::Open);

    // Now splice with the ClientHello bytes
    let (stream_tx, stream_rx) = mpsc::channel::<Frame>(64);
    let (server_stream, _client_stream) = tcp_pair().await;
    let mux_handle = resolved.handle.clone();
    let stream_id = resolved.stream_id;
    let client_hello_clone = client_hello.clone();

    let splice_handle = tokio::spawn(async move {
        splice_stream(
            server_stream,
            &client_hello_clone,
            stream_id,
            &mux_handle,
            stream_rx,
        )
        .await
    });

    // Verify the exact ClientHello bytes arrive as the first DATA frame
    let data_frame = frame_rx.recv().await.unwrap();
    assert_eq!(data_frame.frame_type, FrameType::Data);
    assert_eq!(data_frame.stream_id, resolved.stream_id);
    assert_eq!(data_frame.payload.len(), 517, "ClientHello length mismatch");
    assert_eq!(
        data_frame.payload, client_hello,
        "ClientHello bytes were corrupted through the wake delay"
    );

    // Clean up: send CLOSE to end splice
    stream_tx
        .send(Frame {
            frame_type: FrameType::Close,
            stream_id: resolved.stream_id,
            payload: Vec::new(),
        })
        .await
        .unwrap();

    let result = splice_handle.await.unwrap();
    assert!(result.is_ok());
}
