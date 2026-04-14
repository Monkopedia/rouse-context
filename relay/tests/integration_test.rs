//! Integration test: starts the relay, connects via WebSocket with a client cert,
//! exchanges mux frames, and disconnects.

mod test_helpers;

use futures_util::sink::SinkExt;
use futures_util::stream::StreamExt;
use rcgen::{CertificateParams, DistinguishedName, DnType, KeyPair};
use rouse_relay::api::ws::DeviceIdentity;
use rouse_relay::api::{self, AppState};
use rouse_relay::firestore::DeviceRecord;
use rouse_relay::mux::frame::{Frame, FrameType};
use rouse_relay::passthrough::SessionRegistry;
use rouse_relay::state::RelayState;
use std::sync::Arc;
use std::time::SystemTime;
use test_helpers::*;
use tokio::net::TcpListener;

/// Create a dummy DeviceRecord for tests.
fn dummy_device_record() -> DeviceRecord {
    DeviceRecord {
        fcm_token: "test-fcm-token".to_string(),
        firebase_uid: "test-uid".to_string(),
        public_key: String::new(),
        cert_expires: SystemTime::now(),
        registered_at: SystemTime::now(),
        last_rotation: None,
        renewal_nudge_sent: None,
        secret_prefix: None,
        valid_secrets: Vec::new(),
    }
}

/// Build a test CA + server cert + client cert using rcgen.
#[allow(dead_code)]
struct TestCerts {
    server_cert_pem: String,
    server_key_pem: String,
    ca_cert_pem: String,
    client_cert_pem: String,
    client_key_pem: String,
}

fn generate_test_certs(subdomain: &str) -> TestCerts {
    // Generate CA
    let ca_key = KeyPair::generate().unwrap();
    let mut ca_params = CertificateParams::default();
    ca_params.is_ca = rcgen::IsCa::Ca(rcgen::BasicConstraints::Unconstrained);
    let mut ca_dn = DistinguishedName::new();
    ca_dn.push(DnType::CommonName, "Test CA");
    ca_params.distinguished_name = ca_dn;
    let ca_cert = ca_params.self_signed(&ca_key).unwrap();

    // Generate server cert
    let server_key = KeyPair::generate().unwrap();
    let mut server_params = CertificateParams::new(vec![
        "relay.rousecontext.com".to_string(),
        "localhost".to_string(),
        "127.0.0.1".to_string(),
    ])
    .unwrap();
    let mut server_dn = DistinguishedName::new();
    server_dn.push(DnType::CommonName, "relay.rousecontext.com");
    server_params.distinguished_name = server_dn;
    let server_cert = server_params
        .signed_by(&server_key, &ca_cert, &ca_key)
        .unwrap();

    // Generate client cert with device subdomain
    let client_key = KeyPair::generate().unwrap();
    let dns_name = format!("{subdomain}.rousecontext.com");
    let mut client_params = CertificateParams::new(vec![dns_name.clone()]).unwrap();
    let mut client_dn = DistinguishedName::new();
    client_dn.push(DnType::CommonName, &dns_name);
    client_params.distinguished_name = client_dn;
    let client_cert = client_params
        .signed_by(&client_key, &ca_cert, &ca_key)
        .unwrap();

    TestCerts {
        server_cert_pem: server_cert.pem(),
        server_key_pem: server_key.serialize_pem(),
        ca_cert_pem: ca_cert.pem(),
        client_cert_pem: client_cert.pem(),
        client_key_pem: client_key.serialize_pem(),
    }
}

/// Start the relay axum router on a random port without TLS (plain HTTP)
/// for testing the WebSocket handler directly.
async fn start_test_server(
    app_state: Arc<AppState>,
) -> (std::net::SocketAddr, tokio::task::JoinHandle<()>) {
    let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let addr = listener.local_addr().unwrap();

    let router = api::build_router(app_state);

    let handle = tokio::spawn(async move {
        axum::serve(listener, router).await.unwrap();
    });

    (addr, handle)
}

#[tokio::test]
async fn ws_requires_device_identity() {
    // Without a DeviceIdentity extension, /ws should return 401
    let state = build_test_state(
        Arc::new(MockFirestore::new()),
        Arc::new(MockFcm::new()),
        Arc::new(MockAcme::new("cert")),
        Arc::new(MockFirebaseAuth::new()),
    );

    let (addr, _server) = start_test_server(state).await;

    // Try to connect via WebSocket -- should get 401 since no client cert
    let url = format!("ws://127.0.0.1:{}/ws", addr.port());
    let result = tokio_tungstenite::connect_async(&url).await;

    // The server should reject with 401, which shows up as an HTTP error
    assert!(
        result.is_err(),
        "Expected connection rejection without client cert"
    );
}

#[tokio::test]
async fn ws_upgrade_with_device_identity() {
    // Test the WebSocket handler with a DeviceIdentity extension injected
    // (simulating what mTLS would provide)
    let relay_state = Arc::new(RelayState::new());
    let session_registry = Arc::new(SessionRegistry::new());

    let firestore = MockFirestore::new().with_device("test-device", dummy_device_record());

    let app_state = Arc::new(AppState {
        relay_state: relay_state.clone(),
        session_registry: session_registry.clone(),
        firestore: Arc::new(firestore),
        fcm: Arc::new(MockFcm::new()),
        acme: Arc::new(MockAcme::new("cert")),
        dns: Arc::new(MockDns::new()),
        firebase_auth: Arc::new(MockFirebaseAuth::new()),
        subdomain_generator: rouse_relay::subdomain::SubdomainGenerator::new(),
        rate_limiter: rouse_relay::rate_limit::RateLimiter::new(
            rouse_relay::rate_limit::RateLimitConfig::default(),
        ),
        request_subdomain_rate_limiter: rouse_relay::rate_limit::RateLimiter::new(
            rouse_relay::rate_limit::RateLimitConfig::default(),
        ),
        config: rouse_relay::config::RelayConfig::default(),
        device_ca: None,
    });

    // Build router WITH a DeviceIdentity layer to simulate mTLS
    let router = api::build_router(app_state).layer(axum::Extension(DeviceIdentity {
        subdomain: "test-device".to_string(),
    }));

    let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let addr = listener.local_addr().unwrap();
    tokio::spawn(async move {
        axum::serve(listener, router).await.unwrap();
    });

    // Connect via WebSocket
    let url = format!("ws://127.0.0.1:{}/ws", addr.port());
    let (mut ws_stream, _) = tokio_tungstenite::connect_async(&url).await.unwrap();

    // Give the server time to register the session
    tokio::time::sleep(std::time::Duration::from_millis(100)).await;

    // Verify the device is registered
    assert!(relay_state.is_device_online("test-device"));

    // Send a mux OPEN frame from the "device" side
    let open_frame = Frame {
        frame_type: FrameType::Open,
        stream_id: 1,
        payload: b"test-device.rousecontext.com".to_vec(),
    };
    ws_stream
        .send(tungstenite::Message::Binary(open_frame.encode()))
        .await
        .unwrap();

    // Try to open a stream through the session registry
    let result = session_registry.try_open_stream("test-device").await;
    assert!(
        result.is_some(),
        "Should be able to open a stream on the registered session"
    );

    let (handle, stream_handle) = result.unwrap();
    assert_eq!(stream_handle.stream_id, 1);

    // Send a DATA frame via the handle and verify it arrives at the WebSocket
    let data_frame = Frame {
        frame_type: FrameType::Data,
        stream_id: stream_handle.stream_id,
        payload: b"hello from relay".to_vec(),
    };
    handle.send_frame(data_frame).await.unwrap();

    // Read the frame from the WebSocket
    let msg = ws_stream.next().await.unwrap().unwrap();
    let data = match msg {
        tungstenite::Message::Binary(b) => b.to_vec(),
        other => panic!("Expected binary message, got: {other:?}"),
    };
    let received_frame = Frame::decode(&data).unwrap();
    assert_eq!(received_frame.frame_type, FrameType::Data);
    assert_eq!(received_frame.stream_id, stream_handle.stream_id);
    assert_eq!(received_frame.payload, b"hello from relay");

    // Send a DATA frame from the device side
    let device_data = Frame {
        frame_type: FrameType::Data,
        stream_id: 1,
        payload: b"hello from device".to_vec(),
    };
    ws_stream
        .send(tungstenite::Message::Binary(device_data.encode()))
        .await
        .unwrap();

    // Close the WebSocket
    ws_stream
        .send(tungstenite::Message::Close(None))
        .await
        .unwrap();

    // Wait for cleanup
    tokio::time::sleep(std::time::Duration::from_millis(200)).await;

    // Verify the device is unregistered
    assert!(
        !relay_state.is_device_online("test-device"),
        "Device should be unregistered after WebSocket close"
    );
}

#[tokio::test]
async fn mux_frame_round_trip_through_ws() {
    // Verify that frames sent from a "client" via passthrough are routed correctly
    // through the mux session and back.
    let relay_state = Arc::new(RelayState::new());
    let session_registry = Arc::new(SessionRegistry::new());

    let firestore = MockFirestore::new().with_device("roundtrip-dev", dummy_device_record());

    let app_state = Arc::new(AppState {
        relay_state: relay_state.clone(),
        session_registry: session_registry.clone(),
        firestore: Arc::new(firestore),
        fcm: Arc::new(MockFcm::new()),
        acme: Arc::new(MockAcme::new("cert")),
        dns: Arc::new(MockDns::new()),
        firebase_auth: Arc::new(MockFirebaseAuth::new()),
        subdomain_generator: rouse_relay::subdomain::SubdomainGenerator::new(),
        rate_limiter: rouse_relay::rate_limit::RateLimiter::new(
            rouse_relay::rate_limit::RateLimitConfig::default(),
        ),
        request_subdomain_rate_limiter: rouse_relay::rate_limit::RateLimiter::new(
            rouse_relay::rate_limit::RateLimitConfig::default(),
        ),
        config: rouse_relay::config::RelayConfig::default(),
        device_ca: None,
    });

    let router = api::build_router(app_state).layer(axum::Extension(DeviceIdentity {
        subdomain: "roundtrip-dev".to_string(),
    }));

    let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let addr = listener.local_addr().unwrap();
    tokio::spawn(async move {
        axum::serve(listener, router).await.unwrap();
    });

    // Connect as the "device"
    let url = format!("ws://127.0.0.1:{}/ws", addr.port());
    let (mut ws_stream, _) = tokio_tungstenite::connect_async(&url).await.unwrap();

    tokio::time::sleep(std::time::Duration::from_millis(100)).await;

    // Open a stream through the registry (simulating a client connection)
    let (handle, stream_handle) = session_registry
        .try_open_stream("roundtrip-dev")
        .await
        .unwrap();
    let mut stream_rx = stream_handle.rx;

    // Send OPEN frame to device
    let open_frame = Frame {
        frame_type: FrameType::Open,
        stream_id: stream_handle.stream_id,
        payload: b"roundtrip-dev.rousecontext.com".to_vec(),
    };
    handle.send_frame(open_frame).await.unwrap();

    // Read the OPEN frame on the device side
    let msg = ws_stream.next().await.unwrap().unwrap();
    let frame = Frame::decode(&match msg {
        tungstenite::Message::Binary(b) => b.to_vec(),
        other => panic!("Expected binary, got {other:?}"),
    })
    .unwrap();
    assert_eq!(frame.frame_type, FrameType::Open);
    assert_eq!(frame.stream_id, stream_handle.stream_id);

    // Device responds with DATA
    let response = Frame {
        frame_type: FrameType::Data,
        stream_id: stream_handle.stream_id,
        payload: b"device response data".to_vec(),
    };
    ws_stream
        .send(tungstenite::Message::Binary(response.encode()))
        .await
        .unwrap();

    // Client receives the DATA frame through stream_rx
    let received = stream_rx.recv().await.unwrap();
    assert_eq!(received.frame_type, FrameType::Data);
    assert_eq!(received.payload, b"device response data");

    // Send CLOSE from client side
    let close_frame = Frame {
        frame_type: FrameType::Close,
        stream_id: stream_handle.stream_id,
        payload: Vec::new(),
    };
    handle.send_frame(close_frame).await.unwrap();

    // Device receives CLOSE
    let msg = ws_stream.next().await.unwrap().unwrap();
    let frame = Frame::decode(&match msg {
        tungstenite::Message::Binary(b) => b.to_vec(),
        other => panic!("Expected binary, got {other:?}"),
    })
    .unwrap();
    assert_eq!(frame.frame_type, FrameType::Close);

    // Clean up
    ws_stream
        .send(tungstenite::Message::Close(None))
        .await
        .unwrap();
}

#[test]
fn test_cert_generation_and_subdomain_extraction() {
    // Verify that our test cert generation produces certs from which
    // we can extract the subdomain.
    let certs = generate_test_certs("brave-falcon");

    // Parse the client cert DER
    let client_cert_der: Vec<_> = rustls_pemfile::certs(&mut certs.client_cert_pem.as_bytes())
        .collect::<Result<Vec<_>, _>>()
        .unwrap();

    let subdomain =
        rouse_relay::tls::extract_subdomain_from_peer_cert(&client_cert_der, "rousecontext.com");
    assert_eq!(subdomain, Some("brave-falcon".to_string()));
}
