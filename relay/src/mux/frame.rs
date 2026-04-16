use thiserror::Error;

/// Header size: type (1 byte) + stream_id (4 bytes).
pub const HEADER_SIZE: usize = 5;

#[derive(Debug, Error)]
pub enum FrameError {
    #[error("frame too short: need at least {HEADER_SIZE} bytes, got {0}")]
    TooShort(usize),
    #[error("unknown frame type: 0x{0:02x}")]
    UnknownType(u8),
    #[error("unknown error code: {0}")]
    UnknownErrorCode(u32),
    #[error("error payload too short")]
    ErrorPayloadTooShort,
}

/// Mux frame types matching the Kotlin client.
///
/// `Ping` (0x04) and `Pong` (0x05) provide an application-layer keepalive on
/// top of the WebSocket. The receiver of a Ping must immediately reply with a
/// Pong carrying the identical payload (a u64 nonce encoded big-endian). Both
/// frame types reserve `stream_id = 0` and MUST NOT be interpreted as
/// stream-open requests. See issue #179 for the motivating half-open-socket
/// bug.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[repr(u8)]
pub enum FrameType {
    Data = 0x00,
    Open = 0x01,
    Close = 0x02,
    Error = 0x03,
    Ping = 0x04,
    Pong = 0x05,
}

impl FrameType {
    pub fn from_byte(b: u8) -> Result<Self, FrameError> {
        match b {
            0x00 => Ok(FrameType::Data),
            0x01 => Ok(FrameType::Open),
            0x02 => Ok(FrameType::Close),
            0x03 => Ok(FrameType::Error),
            0x04 => Ok(FrameType::Ping),
            0x05 => Ok(FrameType::Pong),
            other => Err(FrameError::UnknownType(other)),
        }
    }
}

/// Error codes for ERROR frames.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[repr(u32)]
pub enum ErrorCode {
    StreamRefused = 1,
    StreamReset = 2,
    ProtocolError = 3,
    InternalError = 4,
}

impl ErrorCode {
    pub fn from_u32(val: u32) -> Result<Self, FrameError> {
        match val {
            1 => Ok(ErrorCode::StreamRefused),
            2 => Ok(ErrorCode::StreamReset),
            3 => Ok(ErrorCode::ProtocolError),
            4 => Ok(ErrorCode::InternalError),
            other => Err(FrameError::UnknownErrorCode(other)),
        }
    }

    /// Encode an error payload: error_code (u32 BE) + optional UTF-8 message.
    pub fn encode_payload(&self, message: Option<&str>) -> Vec<u8> {
        let code = *self as u32;
        let mut buf = code.to_be_bytes().to_vec();
        if let Some(msg) = message {
            buf.extend_from_slice(msg.as_bytes());
        }
        buf
    }

    /// Decode an error payload: error_code (u32 BE) + optional UTF-8 message.
    pub fn decode_payload(data: &[u8]) -> Result<(ErrorCode, Option<String>), FrameError> {
        if data.len() < 4 {
            return Err(FrameError::ErrorPayloadTooShort);
        }
        let code_val = u32::from_be_bytes([data[0], data[1], data[2], data[3]]);
        let code = ErrorCode::from_u32(code_val)?;
        let msg = if data.len() > 4 {
            Some(String::from_utf8_lossy(&data[4..]).into_owned())
        } else {
            None
        };
        Ok((code, msg))
    }
}

/// A mux protocol frame.
///
/// Wire format: type (u8) + stream_id (u32 BE) + payload (remaining bytes).
/// The header is 5 bytes. The payload length is implicit (frame boundary
/// determined by the transport layer, e.g., WebSocket message boundary).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Frame {
    pub frame_type: FrameType,
    pub stream_id: u32,
    pub payload: Vec<u8>,
}

impl Frame {
    /// Encode the frame into a byte vector.
    pub fn encode(&self) -> Vec<u8> {
        let mut buf = Vec::with_capacity(HEADER_SIZE + self.payload.len());
        buf.push(self.frame_type as u8);
        buf.extend_from_slice(&self.stream_id.to_be_bytes());
        buf.extend_from_slice(&self.payload);
        buf
    }

    /// Decode a frame from a byte slice.
    pub fn decode(data: &[u8]) -> Result<Self, FrameError> {
        if data.len() < HEADER_SIZE {
            return Err(FrameError::TooShort(data.len()));
        }
        let frame_type = FrameType::from_byte(data[0])?;
        let stream_id = u32::from_be_bytes([data[1], data[2], data[3], data[4]]);
        let payload = data[HEADER_SIZE..].to_vec();
        Ok(Frame {
            frame_type,
            stream_id,
            payload,
        })
    }
}
