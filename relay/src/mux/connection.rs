//! Per-device mux connection state.
//!
//! Tracks active streams for a single device connection. The relay uses this
//! to enforce `max_streams_per_device` and to clean up when the device
//! disconnects.

use std::collections::HashMap;

/// State of a single mux stream.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum StreamState {
    Open,
    Closed,
}

/// Tracks all mux streams for one device connection.
pub struct MuxConnection {
    streams: HashMap<u32, StreamState>,
    max_streams: u32,
}

impl MuxConnection {
    pub fn new(max_streams: u32) -> Self {
        Self {
            streams: HashMap::new(),
            max_streams,
        }
    }

    /// Open a new stream. Returns `false` if the limit has been reached.
    pub fn open_stream(&mut self, stream_id: u32) -> bool {
        let active = self
            .streams
            .values()
            .filter(|s| **s == StreamState::Open)
            .count() as u32;
        if active >= self.max_streams {
            return false;
        }
        self.streams.insert(stream_id, StreamState::Open);
        true
    }

    /// Close a stream, freeing its slot.
    ///
    /// Returns `true` only if this call freed an open stream. Closing a stream
    /// that is already closed or was never opened is a no-op returning `false`,
    /// so a stream ended from both sides (device CLOSE and client hang-up, in
    /// either order) frees exactly one slot (#795). Closed streams are dropped
    /// from the map rather than kept as tombstones, so the map stays bounded by
    /// `max_streams` over a long-lived connection.
    pub fn close_stream(&mut self, stream_id: u32) -> bool {
        self.streams.remove(&stream_id) == Some(StreamState::Open)
    }

    /// Number of currently open streams.
    pub fn active_stream_count(&self) -> u32 {
        self.streams
            .values()
            .filter(|s| **s == StreamState::Open)
            .count() as u32
    }

    /// Whether a given stream is currently open.
    pub fn is_stream_open(&self, stream_id: u32) -> bool {
        self.streams.get(&stream_id) == Some(&StreamState::Open)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn open_and_close_streams() {
        let mut conn = MuxConnection::new(2);
        assert!(conn.open_stream(1));
        assert!(conn.open_stream(2));
        assert!(!conn.open_stream(3)); // limit reached
        assert_eq!(conn.active_stream_count(), 2);

        assert!(conn.close_stream(1));
        assert_eq!(conn.active_stream_count(), 1);
        assert!(conn.open_stream(3)); // now there's room
    }

    #[test]
    fn is_stream_open() {
        let mut conn = MuxConnection::new(8);
        assert!(!conn.is_stream_open(1));
        conn.open_stream(1);
        assert!(conn.is_stream_open(1));
        conn.close_stream(1);
        assert!(!conn.is_stream_open(1));
    }

    #[test]
    fn close_stream_frees_at_most_once() {
        let mut conn = MuxConnection::new(2);
        assert!(conn.open_stream(1));
        assert!(conn.open_stream(2));
        assert!(conn.close_stream(1));
        assert!(!conn.close_stream(1)); // second close of the same stream
        assert!(!conn.close_stream(99)); // never opened
        assert_eq!(conn.active_stream_count(), 1);
        assert!(conn.is_stream_open(2));
    }
}
