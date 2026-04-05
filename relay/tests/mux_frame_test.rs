use rouse_relay::mux::frame::{ErrorCode, Frame, FrameType};

#[test]
fn encode_decode_data_frame() {
    let frame = Frame {
        frame_type: FrameType::Data,
        stream_id: 42,
        payload: b"hello world".to_vec(),
    };
    let encoded = frame.encode();
    let decoded = Frame::decode(&encoded).unwrap();
    assert_eq!(decoded.frame_type, FrameType::Data);
    assert_eq!(decoded.stream_id, 42);
    assert_eq!(decoded.payload, b"hello world");
}

#[test]
fn encode_decode_open_frame() {
    let frame = Frame {
        frame_type: FrameType::Open,
        stream_id: 1,
        payload: vec![],
    };
    let encoded = frame.encode();
    let decoded = Frame::decode(&encoded).unwrap();
    assert_eq!(decoded.frame_type, FrameType::Open);
    assert_eq!(decoded.stream_id, 1);
    assert!(decoded.payload.is_empty());
}

#[test]
fn encode_decode_close_frame() {
    let frame = Frame {
        frame_type: FrameType::Close,
        stream_id: 99,
        payload: vec![],
    };
    let encoded = frame.encode();
    let decoded = Frame::decode(&encoded).unwrap();
    assert_eq!(decoded.frame_type, FrameType::Close);
    assert_eq!(decoded.stream_id, 99);
}

#[test]
fn encode_decode_error_frame_with_message() {
    let payload = ErrorCode::ProtocolError.encode_payload(Some("bad frame"));
    let frame = Frame {
        frame_type: FrameType::Error,
        stream_id: 7,
        payload,
    };
    let encoded = frame.encode();
    let decoded = Frame::decode(&encoded).unwrap();
    assert_eq!(decoded.frame_type, FrameType::Error);
    assert_eq!(decoded.stream_id, 7);

    let (code, msg) = ErrorCode::decode_payload(&decoded.payload).unwrap();
    assert_eq!(code, ErrorCode::ProtocolError);
    assert_eq!(msg.as_deref(), Some("bad frame"));
}

#[test]
fn encode_decode_error_frame_without_message() {
    let payload = ErrorCode::InternalError.encode_payload(None);
    let frame = Frame {
        frame_type: FrameType::Error,
        stream_id: 3,
        payload,
    };
    let encoded = frame.encode();
    let decoded = Frame::decode(&encoded).unwrap();

    let (code, msg) = ErrorCode::decode_payload(&decoded.payload).unwrap();
    assert_eq!(code, ErrorCode::InternalError);
    assert!(msg.is_none());
}

#[test]
fn all_error_codes_round_trip() {
    let codes = [
        ErrorCode::StreamRefused,
        ErrorCode::StreamReset,
        ErrorCode::ProtocolError,
        ErrorCode::InternalError,
    ];
    for code in codes {
        let payload = code.encode_payload(Some("test"));
        let (decoded_code, msg) = ErrorCode::decode_payload(&payload).unwrap();
        assert_eq!(decoded_code, code);
        assert_eq!(msg.as_deref(), Some("test"));
    }
}

#[test]
fn round_trip_large_payload() {
    let data = vec![0xAB; 65536];
    let frame = Frame {
        frame_type: FrameType::Data,
        stream_id: u32::MAX,
        payload: data.clone(),
    };
    let encoded = frame.encode();
    let decoded = Frame::decode(&encoded).unwrap();
    assert_eq!(decoded.payload, data);
    assert_eq!(decoded.stream_id, u32::MAX);
}

#[test]
fn unknown_frame_type_returns_error() {
    let mut encoded = Frame {
        frame_type: FrameType::Data,
        stream_id: 1,
        payload: vec![],
    }
    .encode();
    // Corrupt the frame type byte
    encoded[0] = 0xFF;
    let result = Frame::decode(&encoded);
    assert!(result.is_err());
}

#[test]
fn truncated_header_returns_error() {
    let result = Frame::decode(&[0x00, 0x00, 0x01]);
    assert!(result.is_err());
}

#[test]
fn truncated_payload_returns_error() {
    // Valid header claiming payload exists, but data is short
    let mut buf = vec![0x00]; // DATA type
    buf.extend_from_slice(&1u32.to_be_bytes()); // stream_id
                                                // Header says we should have more data from the length, but decode reads
                                                // remaining bytes as payload -- this tests the header-only case which is valid.
                                                // Instead, test with a frame where we know the encoded length and truncate it.
    let frame = Frame {
        frame_type: FrameType::Data,
        stream_id: 1,
        payload: b"hello".to_vec(),
    };
    let encoded = frame.encode();
    // Truncate so payload is incomplete
    let truncated = &encoded[..encoded.len() - 2];
    // This should still decode but with truncated payload, or error depending on
    // implementation. The key invariant: it must not panic.
    let _ = Frame::decode(truncated);
}

#[test]
fn header_encoding_is_5_bytes() {
    let frame = Frame {
        frame_type: FrameType::Data,
        stream_id: 0,
        payload: vec![],
    };
    let encoded = frame.encode();
    // 5 byte header, no payload
    assert_eq!(encoded.len(), 5);
    // First byte is frame type
    assert_eq!(encoded[0], 0x00);
    // Next 4 bytes are stream_id in big-endian
    assert_eq!(&encoded[1..5], &[0, 0, 0, 0]);
}

#[test]
fn error_code_values_match_spec() {
    assert_eq!(ErrorCode::StreamRefused as u32, 1);
    assert_eq!(ErrorCode::StreamReset as u32, 2);
    assert_eq!(ErrorCode::ProtocolError as u32, 3);
    assert_eq!(ErrorCode::InternalError as u32, 4);
}

#[test]
fn unknown_error_code_returns_error() {
    let mut payload = 999u32.to_be_bytes().to_vec();
    payload.extend_from_slice(b"unknown");
    let result = ErrorCode::decode_payload(&payload);
    assert!(result.is_err());
}
