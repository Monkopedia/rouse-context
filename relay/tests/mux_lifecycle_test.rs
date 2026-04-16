//! Tests for mux WebSocket lifecycle management.
//!
//! Covers:
//! - Stream open/close lifecycle through MuxSession
//! - Frame dispatch to correct stream channels
//! - Max streams enforcement (STREAM_REFUSED error)
//! - WebSocket drop -> all client connections closed
//! - Multiple streams concurrent lifecycle

mod test_helpers;

use rouse_relay::mux::frame::{ErrorCode, Frame, FrameType};
use rouse_relay::mux::lifecycle::{run_mux_read_loop, MuxSession};
use rouse_relay::state::RelayState;
use std::sync::Arc;
use tokio::sync::mpsc;

#[tokio::test]
async fn open_stream_returns_sequential_ids() {
    let mut session = MuxSession::new("test-device".to_string(), 8);

    let s1 = session.open_stream().unwrap();
    assert_eq!(s1.stream_id, 1);

    let s2 = session.open_stream().unwrap();
    assert_eq!(s2.stream_id, 2);

    let s3 = session.open_stream().unwrap();
    assert_eq!(s3.stream_id, 3);

    assert_eq!(session.active_stream_count(), 3);
}

#[tokio::test]
async fn max_streams_exceeded_returns_none() {
    let mut session = MuxSession::new("test-device".to_string(), 2);

    let _s1 = session.open_stream().unwrap();
    let _s2 = session.open_stream().unwrap();

    // Third stream should fail
    assert!(session.open_stream().is_none());
}

#[tokio::test]
async fn close_stream_frees_slot() {
    let mut session = MuxSession::new("test-device".to_string(), 2);

    let s1 = session.open_stream().unwrap();
    let _s2 = session.open_stream().unwrap();
    assert!(session.open_stream().is_none());

    // Close first stream
    session.close_stream(s1.stream_id);
    assert_eq!(session.active_stream_count(), 1);

    // Now we can open another
    let s3 = session.open_stream().unwrap();
    assert_eq!(s3.stream_id, 3); // ID keeps incrementing
    assert_eq!(session.active_stream_count(), 2);
}

#[tokio::test]
async fn dispatch_data_to_correct_stream() {
    let relay_state = Arc::new(RelayState::new());
    let mut session = MuxSession::new("dev".to_string(), 8);
    relay_state.register_mux_connection("dev");

    let mut s1 = session.open_stream().unwrap();
    let mut s2 = session.open_stream().unwrap();

    // Dispatch DATA to stream 1
    let frame1 = Frame {
        frame_type: FrameType::Data,
        stream_id: 1,
        payload: b"data-for-stream-1".to_vec(),
    };
    session.dispatch_incoming(frame1, &relay_state).await;

    // Dispatch DATA to stream 2
    let frame2 = Frame {
        frame_type: FrameType::Data,
        stream_id: 2,
        payload: b"data-for-stream-2".to_vec(),
    };
    session.dispatch_incoming(frame2, &relay_state).await;

    // Each stream should receive its own data
    let received1 = s1.rx.recv().await.unwrap();
    assert_eq!(received1.payload, b"data-for-stream-1");

    let received2 = s2.rx.recv().await.unwrap();
    assert_eq!(received2.payload, b"data-for-stream-2");
}

#[tokio::test]
async fn dispatch_close_removes_stream() {
    let relay_state = Arc::new(RelayState::new());
    let mut session = MuxSession::new("dev".to_string(), 8);
    relay_state.register_mux_connection("dev");

    let mut s1 = session.open_stream().unwrap();
    assert_eq!(session.active_stream_count(), 1);

    // Device sends CLOSE for stream 1
    let close_frame = Frame {
        frame_type: FrameType::Close,
        stream_id: 1,
        payload: Vec::new(),
    };
    session.dispatch_incoming(close_frame, &relay_state).await;

    assert_eq!(session.active_stream_count(), 0);

    // The stream channel should have received the CLOSE
    let received = s1.rx.recv().await.unwrap();
    assert_eq!(received.frame_type, FrameType::Close);
}

#[tokio::test]
async fn dispatch_error_closes_stream_and_forwards() {
    let relay_state = Arc::new(RelayState::new());
    let mut session = MuxSession::new("dev".to_string(), 8);
    relay_state.register_mux_connection("dev");

    let mut s1 = session.open_stream().unwrap();

    let error_payload = ErrorCode::StreamRefused.encode_payload(Some("refused"));
    let error_frame = Frame {
        frame_type: FrameType::Error,
        stream_id: 1,
        payload: error_payload,
    };
    session.dispatch_incoming(error_frame, &relay_state).await;

    assert_eq!(session.active_stream_count(), 0);

    let received = s1.rx.recv().await.unwrap();
    assert_eq!(received.frame_type, FrameType::Error);
}

#[tokio::test]
async fn data_for_unknown_stream_is_ignored() {
    let relay_state = Arc::new(RelayState::new());
    let mut session = MuxSession::new("dev".to_string(), 8);
    relay_state.register_mux_connection("dev");

    // No streams open — DATA for stream 99 should be silently ignored
    let frame = Frame {
        frame_type: FrameType::Data,
        stream_id: 99,
        payload: b"orphan".to_vec(),
    };
    session.dispatch_incoming(frame, &relay_state).await;

    // No panic, no error — just dropped
    assert_eq!(session.active_stream_count(), 0);
}

#[tokio::test]
async fn close_all_streams_sends_close_to_all() {
    let mut session = MuxSession::new("dev".to_string(), 8);

    let mut s1 = session.open_stream().unwrap();
    let mut s2 = session.open_stream().unwrap();
    let mut s3 = session.open_stream().unwrap();

    session.close_all_streams().await;

    // All stream channels should receive CLOSE
    let f1 = s1.rx.recv().await.unwrap();
    assert_eq!(f1.frame_type, FrameType::Close);
    assert_eq!(f1.stream_id, 1);

    let f2 = s2.rx.recv().await.unwrap();
    assert_eq!(f2.frame_type, FrameType::Close);
    assert_eq!(f2.stream_id, 2);

    let f3 = s3.rx.recv().await.unwrap();
    assert_eq!(f3.frame_type, FrameType::Close);
    assert_eq!(f3.stream_id, 3);
}

#[tokio::test]
async fn websocket_drop_cleans_up_via_read_loop() {
    let relay_state = Arc::new(RelayState::new());
    relay_state.register_mux_connection("dev");

    let mut session = MuxSession::new("dev".to_string(), 8);
    let mut s1 = session.open_stream().unwrap();
    let mut s2 = session.open_stream().unwrap();

    // Create a channel that simulates WebSocket incoming frames
    let (ws_tx, ws_rx) = mpsc::channel::<Frame>(16);

    // Run the read loop in background
    let rs = relay_state.clone();
    let loop_handle = tokio::spawn(async move {
        run_mux_read_loop(&mut session, ws_rx, &rs).await;
    });

    // Send some data through
    ws_tx
        .send(Frame {
            frame_type: FrameType::Data,
            stream_id: 1,
            payload: b"hello".to_vec(),
        })
        .await
        .unwrap();

    let received = s1.rx.recv().await.unwrap();
    assert_eq!(received.payload, b"hello");

    // Drop the ws sender to simulate WebSocket disconnect
    drop(ws_tx);

    // The loop should complete
    loop_handle.await.unwrap();

    // Device should be removed from relay state
    assert!(!relay_state.is_device_online("dev"));

    // Stream channels should receive CLOSE
    let f1 = s1.rx.recv().await.unwrap();
    assert_eq!(f1.frame_type, FrameType::Close);

    let f2 = s2.rx.recv().await.unwrap();
    assert_eq!(f2.frame_type, FrameType::Close);
}

#[tokio::test]
async fn mux_handle_send_frame_works() {
    let mut session = MuxSession::new("dev".to_string(), 8);
    let handle = session.handle();
    let mut frame_rx = session.take_frame_rx().unwrap();

    let frame = Frame {
        frame_type: FrameType::Data,
        stream_id: 42,
        payload: b"test-payload".to_vec(),
    };
    handle.send_frame(frame).await.unwrap();

    let received = frame_rx.recv().await.unwrap();
    assert_eq!(received.frame_type, FrameType::Data);
    assert_eq!(received.stream_id, 42);
    assert_eq!(received.payload, b"test-payload");
}

#[tokio::test]
async fn send_stream_refused_sends_error_frame() {
    let mut session = MuxSession::new("dev".to_string(), 8);
    let mut frame_rx = session.take_frame_rx().unwrap();

    session.send_stream_refused(5).await;

    let frame = frame_rx.recv().await.unwrap();
    assert_eq!(frame.frame_type, FrameType::Error);
    assert_eq!(frame.stream_id, 5);

    let (code, msg) = ErrorCode::decode_payload(&frame.payload).unwrap();
    assert_eq!(code, ErrorCode::StreamRefused);
    assert_eq!(msg.unwrap(), "max streams exceeded");
}

#[tokio::test]
async fn ping_frame_is_echoed_as_pong_without_opening_stream() {
    // The relay must reply to a device Ping with Pong carrying the same nonce,
    // and MUST NOT treat the Ping as a stream-open request.
    let relay_state = Arc::new(RelayState::new());
    relay_state.register_mux_connection("dev");

    let mut session = MuxSession::new("dev".to_string(), 8);
    let mut frame_rx = session.take_frame_rx().unwrap();

    assert_eq!(session.active_stream_count(), 0);

    let nonce: u64 = 0xAAAA_BBBB_CCCC_DDDD;
    let ping = Frame {
        frame_type: FrameType::Ping,
        stream_id: 0,
        payload: nonce.to_be_bytes().to_vec(),
    };
    session.dispatch_incoming(ping, &relay_state).await;

    // A Pong should have been queued for sending back over the WebSocket.
    let pong = tokio::time::timeout(std::time::Duration::from_millis(500), frame_rx.recv())
        .await
        .expect("Pong not sent within 500ms")
        .expect("frame_rx closed");

    assert_eq!(pong.frame_type, FrameType::Pong);
    assert_eq!(pong.stream_id, 0);
    assert_eq!(pong.payload.len(), 8);
    let recovered = u64::from_be_bytes(pong.payload.try_into().unwrap());
    assert_eq!(recovered, nonce, "Pong must echo the Ping nonce");

    // Critically: Ping must NOT have been interpreted as an Open.
    assert_eq!(session.active_stream_count(), 0);
}

#[tokio::test]
async fn pong_frame_is_noop_on_relay() {
    // Relay currently only receives Pongs in response to client-initiated Pings.
    // It should accept them without opening streams or erroring out.
    let relay_state = Arc::new(RelayState::new());
    relay_state.register_mux_connection("dev");

    let mut session = MuxSession::new("dev".to_string(), 8);
    let _frame_rx = session.take_frame_rx().unwrap();

    let pong = Frame {
        frame_type: FrameType::Pong,
        stream_id: 0,
        payload: 42u64.to_be_bytes().to_vec(),
    };
    session.dispatch_incoming(pong, &relay_state).await;
    assert_eq!(session.active_stream_count(), 0);
}

#[tokio::test]
async fn full_lifecycle_open_data_close() {
    let relay_state = Arc::new(RelayState::new());
    relay_state.register_mux_connection("dev");

    let mut session = MuxSession::new("dev".to_string(), 8);
    let mut frame_rx = session.take_frame_rx().unwrap();

    // Open stream
    let mut stream = session.open_stream().unwrap();
    assert_eq!(stream.stream_id, 1);
    assert_eq!(session.active_stream_count(), 1);

    // Send OPEN frame to device
    let handle = session.handle();
    handle
        .send_frame(Frame {
            frame_type: FrameType::Open,
            stream_id: 1,
            payload: b"brave-falcon.rousecontext.com".to_vec(),
        })
        .await
        .unwrap();

    let open_frame = frame_rx.recv().await.unwrap();
    assert_eq!(open_frame.frame_type, FrameType::Open);

    // Device sends DATA back
    let (ws_tx, ws_rx) = mpsc::channel::<Frame>(16);
    let rs = relay_state.clone();
    let loop_handle = tokio::spawn(async move {
        run_mux_read_loop(&mut session, ws_rx, &rs).await;
    });

    ws_tx
        .send(Frame {
            frame_type: FrameType::Data,
            stream_id: 1,
            payload: b"response-data".to_vec(),
        })
        .await
        .unwrap();

    let data = stream.rx.recv().await.unwrap();
    assert_eq!(data.payload, b"response-data");

    // Device sends CLOSE
    ws_tx
        .send(Frame {
            frame_type: FrameType::Close,
            stream_id: 1,
            payload: Vec::new(),
        })
        .await
        .unwrap();

    let close = stream.rx.recv().await.unwrap();
    assert_eq!(close.frame_type, FrameType::Close);

    // Clean up
    drop(ws_tx);
    loop_handle.await.unwrap();
}
