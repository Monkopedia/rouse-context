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
    resolve_device_stream, splice_stream, PassthroughContext, PassthroughError, SessionRegistry,
};
use rouse_relay::state::RelayState;
use std::sync::{Arc, Mutex};
use std::time::{Duration, SystemTime};
use test_helpers::{MockFcm, MockFirestore};
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::TcpListener;
use tokio::sync::mpsc;

/// Helper: create a MuxSession, register it in both RelayState and SessionRegistry.
fn setup_device(
    relay_state: &Arc<RelayState>,
    registry: &Arc<SessionRegistry>,
    subdomain: &str,
    max_streams: u32,
) -> Arc<Mutex<MuxSession>> {
    let session = Arc::new(Mutex::new(MuxSession::new(
        subdomain.to_string(),
        max_streams,
    )));
    relay_state.register_mux_connection(subdomain);
    registry.insert(subdomain, session.clone());
    session
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
    }
}

#[tokio::test]
async fn open_frame_sent_with_sni_hostname() {
    let relay_state = Arc::new(RelayState::new());
    let registry = Arc::new(SessionRegistry::new());

    let session = setup_device(&relay_state, &registry, "brave-falcon", 8);
    let mut frame_rx = session.lock().unwrap().take_frame_rx().unwrap();

    let ctx = make_ctx(
        relay_state,
        registry,
        Arc::new(MockFirestore::new()),
        Arc::new(MockFcm::new()),
    );

    let sni = "brave-falcon.rousecontext.com";
    let result = resolve_device_stream(&ctx, "brave-falcon", sni).await;

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
    };

    // Device is NOT online, so FCM should be sent.
    // We don't simulate the device connecting back, so this should timeout.
    let result = resolve_device_stream(&ctx, "brave-falcon", "brave-falcon.rousecontext.com").await;

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
    };

    let result = resolve_device_stream(&ctx, "test-sub", "test-sub.rousecontext.com").await;

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

    let result = resolve_device_stream(&ctx, "nonexistent", "nonexistent.rousecontext.com").await;

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
    };

    // Simulate the device connecting after a short delay
    let rs = relay_state.clone();
    let reg = registry.clone();
    tokio::spawn(async move {
        tokio::time::sleep(Duration::from_millis(50)).await;
        // Device wakes up and establishes mux
        let session = Arc::new(Mutex::new(MuxSession::new("cold-dev".to_string(), 8)));
        reg.insert("cold-dev", session);
        rs.register_mux_connection("cold-dev");
    });

    let result = resolve_device_stream(&ctx, "cold-dev", "cold-dev.rousecontext.com").await;

    assert!(result.is_ok());
    let resolved = result.unwrap();
    assert_eq!(resolved.stream_id, 1);

    // FCM was sent
    let sent = fcm.sent.lock().unwrap();
    assert_eq!(sent.len(), 1);
    assert_eq!(sent[0].0, "fcm-cold");
}
