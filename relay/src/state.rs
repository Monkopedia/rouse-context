//! In-memory relay state: active mux connections, streams, device cache, metrics.
//!
//! This is the shared state that the API handlers, SNI router, and mux
//! connection manager all need access to.

use dashmap::DashMap;
use std::sync::atomic::{AtomicU32, AtomicU64};
use std::time::Instant;
use tokio::sync::broadcast;

/// Shared relay state, wrapped in Arc at the call site.
pub struct RelayState {
    /// When the relay started (for uptime calculation).
    pub started_at: Instant,
    /// Active mux connections keyed by subdomain.
    pub mux_connections: DashMap<String, MuxConnectionInfo>,
    /// Broadcast channels for device-connect events, keyed by subdomain.
    /// Wake handlers subscribe to wait for a device to connect.
    pub connect_signals: DashMap<String, broadcast::Sender<()>>,
    /// Total streams opened since start (monotonically increasing).
    pub total_sessions_served: AtomicU64,
    /// Number of pending FCM wakeups (waiting for device to connect).
    pub pending_fcm_wakeups: AtomicU32,
    /// Optimistic per-device cache of `valid_secrets`. Populated by write
    /// endpoints (`/register`, `/rotate-secret`) so that the next SNI
    /// connection does not have to wait for Firestore eventual consistency
    /// to learn about freshly-pushed secrets. The passthrough path consults
    /// this cache before falling back to the Firestore record.
    pub valid_secrets_cache: DashMap<String, Vec<String>>,
}

/// Info about an active mux connection for a device.
#[derive(Debug, Clone)]
pub struct MuxConnectionInfo {
    pub subdomain: String,
    pub active_streams: u32,
    pub connected_at: Instant,
}

impl RelayState {
    pub fn new() -> Self {
        Self {
            started_at: Instant::now(),
            mux_connections: DashMap::new(),
            connect_signals: DashMap::new(),
            total_sessions_served: AtomicU64::new(0),
            pending_fcm_wakeups: AtomicU32::new(0),
            valid_secrets_cache: DashMap::new(),
        }
    }

    /// Update the in-memory `valid_secrets` cache for a device. Called by
    /// write endpoints so that the passthrough path does not have to wait
    /// for Firestore eventual consistency after a rotate.
    pub fn set_valid_secrets_cache(&self, subdomain: &str, secrets: Vec<String>) {
        if secrets.is_empty() {
            self.valid_secrets_cache.remove(subdomain);
        } else {
            self.valid_secrets_cache.insert(subdomain.to_string(), secrets);
        }
    }

    /// Look up cached `valid_secrets` for a device. Returns `None` if the
    /// cache has no entry (callers should fall back to Firestore).
    pub fn get_valid_secrets_cache(&self, subdomain: &str) -> Option<Vec<String>> {
        self.valid_secrets_cache
            .get(subdomain)
            .map(|entry| entry.value().clone())
    }

    /// Check whether a device has an active mux connection.
    pub fn is_device_online(&self, subdomain: &str) -> bool {
        self.mux_connections.contains_key(subdomain)
    }

    /// Register a device mux connection.
    pub fn register_mux_connection(&self, subdomain: &str) {
        let info = MuxConnectionInfo {
            subdomain: subdomain.to_string(),
            active_streams: 0,
            connected_at: Instant::now(),
        };
        self.mux_connections.insert(subdomain.to_string(), info);

        // Notify any wake handlers waiting for this device
        if let Some(sender) = self.connect_signals.get(subdomain) {
            let _ = sender.send(());
        }
    }

    /// Remove a device mux connection.
    pub fn remove_mux_connection(&self, subdomain: &str) {
        self.mux_connections.remove(subdomain);
    }

    /// Get a broadcast receiver to wait for a device to connect.
    pub fn subscribe_connect(&self, subdomain: &str) -> broadcast::Receiver<()> {
        let entry = self
            .connect_signals
            .entry(subdomain.to_string())
            .or_insert_with(|| broadcast::channel(4).0);
        entry.subscribe()
    }

    /// Count of active mux connections.
    pub fn active_mux_count(&self) -> u32 {
        self.mux_connections.len() as u32
    }

    /// Count of active streams across all devices.
    pub fn active_stream_count(&self) -> u32 {
        self.mux_connections
            .iter()
            .map(|entry| entry.value().active_streams)
            .sum()
    }

    /// Uptime in seconds.
    pub fn uptime_secs(&self) -> u64 {
        self.started_at.elapsed().as_secs()
    }
}

impl Default for RelayState {
    fn default() -> Self {
        Self::new()
    }
}
