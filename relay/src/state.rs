//! In-memory relay state: active mux connections, streams, device cache, metrics.
//!
//! This is the shared state that the API handlers, SNI router, and mux
//! connection manager all need access to.

use dashmap::DashMap;
use lru::LruCache;
use std::num::NonZeroUsize;
use std::sync::atomic::{AtomicU32, AtomicU64};
use std::sync::Mutex;
use std::time::Instant;
use tokio::sync::broadcast;

/// Default cap on `valid_secrets_cache` entries. See
/// `ServerConfig::cache_capacity` for rationale.
pub const DEFAULT_CACHE_CAPACITY: usize = 10_000;

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
    ///
    /// Bounded with LRU eviction (see #109) so that a long-running relay
    /// doesn't accumulate one entry per device ever connected. Evicted
    /// entries are re-populated from Firestore on the next hit.
    ///
    /// Uses a `Mutex` rather than a concurrent map because `LruCache`
    /// requires `&mut self` even on reads (LRU bookkeeping). This is cold
    /// path code — one lookup per new TLS connection — so lock contention
    /// is negligible. Callers must never hold the lock across `.await`.
    pub valid_secrets_cache: Mutex<LruCache<String, Vec<String>>>,
}

/// Info about an active mux connection for a device.
#[derive(Debug, Clone)]
pub struct MuxConnectionInfo {
    pub subdomain: String,
    pub active_streams: u32,
    pub connected_at: Instant,
}

impl RelayState {
    /// Build a new relay state with the default cache capacity
    /// (`DEFAULT_CACHE_CAPACITY`). Prefer `with_cache_capacity` when the
    /// production config value is available.
    pub fn new() -> Self {
        Self::with_cache_capacity(DEFAULT_CACHE_CAPACITY)
    }

    /// Build a new relay state with an explicit cache capacity. A capacity
    /// of zero falls back to `DEFAULT_CACHE_CAPACITY` so a misconfigured
    /// TOML can't accidentally disable the cache entirely.
    pub fn with_cache_capacity(cache_capacity: usize) -> Self {
        let cap = NonZeroUsize::new(cache_capacity)
            .unwrap_or_else(|| NonZeroUsize::new(DEFAULT_CACHE_CAPACITY).unwrap());
        Self {
            started_at: Instant::now(),
            mux_connections: DashMap::new(),
            connect_signals: DashMap::new(),
            total_sessions_served: AtomicU64::new(0),
            pending_fcm_wakeups: AtomicU32::new(0),
            valid_secrets_cache: Mutex::new(LruCache::new(cap)),
        }
    }

    /// Update the in-memory `valid_secrets` cache for a device. Called by
    /// write endpoints so that the passthrough path does not have to wait
    /// for Firestore eventual consistency after a rotate.
    ///
    /// An empty `secrets` list removes the entry entirely (rather than
    /// caching an empty list, which would break the cache-miss fall-through
    /// logic in the passthrough path).
    pub fn set_valid_secrets_cache(&self, subdomain: &str, secrets: Vec<String>) {
        let mut cache = self
            .valid_secrets_cache
            .lock()
            .expect("valid_secrets_cache mutex poisoned");
        if secrets.is_empty() {
            cache.pop(subdomain);
        } else {
            cache.put(subdomain.to_string(), secrets);
        }
    }

    /// Look up cached `valid_secrets` for a device. Returns `None` if the
    /// cache has no entry (callers should fall back to Firestore). Returns
    /// an owned `Vec<String>` so the caller is not holding the mutex across
    /// await points.
    pub fn get_valid_secrets_cache(&self, subdomain: &str) -> Option<Vec<String>> {
        let mut cache = self
            .valid_secrets_cache
            .lock()
            .expect("valid_secrets_cache mutex poisoned");
        cache.get(subdomain).cloned()
    }

    /// Number of entries currently held in the `valid_secrets_cache`.
    /// Exposed for metrics and tests.
    pub fn valid_secrets_cache_len(&self) -> usize {
        self.valid_secrets_cache
            .lock()
            .expect("valid_secrets_cache mutex poisoned")
            .len()
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

#[cfg(test)]
mod tests {
    use super::*;

    /// Inserting more than `cache_capacity` entries must evict the oldest
    /// entries in LRU order, bounding memory growth. This is the core
    /// invariant that fixes #109.
    #[test]
    fn cache_evicts_oldest_when_capacity_exceeded() {
        let state = RelayState::with_cache_capacity(3);

        // Fill to capacity.
        state.set_valid_secrets_cache("dev-1", vec!["s1".to_string()]);
        state.set_valid_secrets_cache("dev-2", vec!["s2".to_string()]);
        state.set_valid_secrets_cache("dev-3", vec!["s3".to_string()]);
        assert_eq!(state.valid_secrets_cache_len(), 3);

        // Exceed capacity: dev-1 (oldest) must be evicted.
        state.set_valid_secrets_cache("dev-4", vec!["s4".to_string()]);
        assert_eq!(state.valid_secrets_cache_len(), 3);
        assert_eq!(state.get_valid_secrets_cache("dev-1"), None);
        assert_eq!(
            state.get_valid_secrets_cache("dev-2"),
            Some(vec!["s2".to_string()])
        );
        assert_eq!(
            state.get_valid_secrets_cache("dev-4"),
            Some(vec!["s4".to_string()])
        );
    }

    /// `get` must refresh recency so a recently-read entry is not the next
    /// victim. This is what makes LRU different from FIFO and is the reason
    /// the cache performs well for a working set smaller than the cap.
    #[test]
    fn cache_get_refreshes_recency() {
        let state = RelayState::with_cache_capacity(2);
        state.set_valid_secrets_cache("dev-1", vec!["s1".to_string()]);
        state.set_valid_secrets_cache("dev-2", vec!["s2".to_string()]);

        // Touch dev-1 so dev-2 becomes the LRU victim.
        let _ = state.get_valid_secrets_cache("dev-1");

        // Insert dev-3: dev-2 must be evicted, dev-1 must survive.
        state.set_valid_secrets_cache("dev-3", vec!["s3".to_string()]);
        assert_eq!(
            state.get_valid_secrets_cache("dev-1"),
            Some(vec!["s1".to_string()])
        );
        assert_eq!(state.get_valid_secrets_cache("dev-2"), None);
        assert_eq!(
            state.get_valid_secrets_cache("dev-3"),
            Some(vec!["s3".to_string()])
        );
    }

    /// Below capacity, the cache behaves exactly like a hashmap: inserts
    /// are retained, overwrites replace the value, and empty-list writes
    /// remove the entry.
    #[test]
    fn cache_behaves_like_hashmap_below_capacity() {
        let state = RelayState::with_cache_capacity(100);
        state.set_valid_secrets_cache("dev-1", vec!["a".to_string()]);
        assert_eq!(
            state.get_valid_secrets_cache("dev-1"),
            Some(vec!["a".to_string()])
        );

        // Overwrite.
        state.set_valid_secrets_cache("dev-1", vec!["b".to_string(), "c".to_string()]);
        assert_eq!(
            state.get_valid_secrets_cache("dev-1"),
            Some(vec!["b".to_string(), "c".to_string()])
        );

        // Empty list removes.
        state.set_valid_secrets_cache("dev-1", vec![]);
        assert_eq!(state.get_valid_secrets_cache("dev-1"), None);
        assert_eq!(state.valid_secrets_cache_len(), 0);
    }

    /// A zero capacity in config must not disable the cache — it falls
    /// back to the default capacity instead.
    #[test]
    fn zero_capacity_falls_back_to_default() {
        let state = RelayState::with_cache_capacity(0);
        for i in 0..100 {
            state.set_valid_secrets_cache(&format!("dev-{i}"), vec![format!("s{i}")]);
        }
        assert_eq!(state.valid_secrets_cache_len(), 100);
    }
}
