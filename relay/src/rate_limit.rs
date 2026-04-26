//! Rate limiters for the relay.
//!
//! In-memory state that resets on relay restart.
//!
//! - `RateLimiter`: Per-subdomain token bucket for the `/wake` HTTP endpoint.
//! - `FcmWakeThrottle`: Prevents redundant FCM pushes when a device was already
//!   woken recently (one wake per subdomain within a cooldown window).
//! - `ConnectionRateLimiter`: Per-(source IP, subdomain) sliding-window counter
//!   that rejects scanners hammering the same device from a single IP.

use dashmap::DashMap;
use std::net::IpAddr;
use std::sync::Arc;
use std::time::{Duration, Instant};

/// Configuration for the token bucket rate limiter.
#[derive(Debug, Clone)]
pub struct RateLimitConfig {
    /// Maximum number of tokens (burst size).
    pub max_tokens: u32,
    /// How often a token is added.
    pub refill_interval: Duration,
}

impl Default for RateLimitConfig {
    fn default() -> Self {
        Self {
            max_tokens: 6,
            refill_interval: Duration::from_secs(10),
        }
    }
}

struct Bucket {
    tokens: f64,
    last_refill: Instant,
}

pub struct RateLimiter {
    config: RateLimitConfig,
    buckets: DashMap<String, Bucket>,
}

impl RateLimiter {
    pub fn new(config: RateLimitConfig) -> Self {
        Self {
            config,
            buckets: DashMap::new(),
        }
    }

    /// Number of entries currently held (for tests / metrics).
    pub fn len(&self) -> usize {
        self.buckets.len()
    }

    /// Returns `true` if the underlying map is empty.
    pub fn is_empty(&self) -> bool {
        self.buckets.is_empty()
    }

    /// Drop entries whose bucket would already be fully refilled.
    ///
    /// Once a bucket has been idle long enough to refill to `max_tokens`, its
    /// state is indistinguishable from a freshly-created bucket, so it is safe
    /// to evict. We use twice the full-refill interval as a safety margin so
    /// we never race a caller that is about to read the same entry.
    pub fn sweep_expired(&self) {
        self.sweep_expired_at(Instant::now());
    }

    /// Testable version that accepts a specific time instant.
    pub fn sweep_expired_at(&self, now: Instant) {
        let full_refill = self
            .config
            .refill_interval
            .saturating_mul(self.config.max_tokens.max(1));
        let ttl = full_refill.saturating_mul(2);
        self.buckets
            .retain(|_, bucket| now.duration_since(bucket.last_refill) < ttl);
    }

    /// Try to consume a token for the given subdomain.
    /// Returns `Ok(())` if allowed, or `Err(retry_after_secs)` if rate limited.
    pub fn try_acquire(&self, subdomain: &str) -> Result<(), u64> {
        self.try_acquire_at(subdomain, Instant::now())
    }

    /// Testable version that accepts a specific time instant.
    pub fn try_acquire_at(&self, subdomain: &str, now: Instant) -> Result<(), u64> {
        let max = self.config.max_tokens as f64;
        let refill_secs = self.config.refill_interval.as_secs_f64();

        let mut entry = self
            .buckets
            .entry(subdomain.to_string())
            .or_insert_with(|| Bucket {
                tokens: max,
                last_refill: now,
            });

        let bucket = entry.value_mut();

        // Refill tokens based on elapsed time
        let elapsed = now.duration_since(bucket.last_refill).as_secs_f64();
        let refilled = elapsed / refill_secs;
        bucket.tokens = (bucket.tokens + refilled).min(max);
        bucket.last_refill = now;

        if bucket.tokens >= 1.0 {
            bucket.tokens -= 1.0;
            Ok(())
        } else {
            // Calculate how long until the next token is available
            let deficit = 1.0 - bucket.tokens;
            let wait_secs = (deficit * refill_secs).ceil() as u64;
            Err(wait_secs)
        }
    }
}

// ---------------------------------------------------------------------------
// FCM wake throttle: at most one FCM push per subdomain within a cooldown.
// ---------------------------------------------------------------------------

/// Prevents duplicate FCM wakes when a device was already woken recently.
///
/// Each subdomain records the `Instant` of its last FCM wake. A new wake is
/// only allowed after the cooldown expires.
pub struct FcmWakeThrottle {
    cooldown: Duration,
    last_wake: DashMap<String, Instant>,
}

impl FcmWakeThrottle {
    pub fn new(cooldown: Duration) -> Self {
        Self {
            cooldown,
            last_wake: DashMap::new(),
        }
    }

    /// Number of entries currently held (for tests / metrics).
    pub fn len(&self) -> usize {
        self.last_wake.len()
    }

    /// Returns `true` if the underlying map is empty.
    pub fn is_empty(&self) -> bool {
        self.last_wake.is_empty()
    }

    /// Drop entries whose cooldown has expired twice over.
    ///
    /// An entry older than the cooldown window is indistinguishable from a
    /// never-seen subdomain (both return `true` on next call), so evicting
    /// expired entries does not change the throttle's decisions. We use 2×
    /// the cooldown as a safety margin.
    pub fn sweep_expired(&self) {
        self.sweep_expired_at(Instant::now());
    }

    /// Testable version that accepts a specific time instant.
    pub fn sweep_expired_at(&self, now: Instant) {
        let ttl = self.cooldown.saturating_mul(2);
        self.last_wake
            .retain(|_, last| now.duration_since(*last) < ttl);
    }

    /// Returns `true` if an FCM wake should be sent (cooldown expired or first wake).
    /// Returns `false` if a wake was already sent within the cooldown window.
    pub fn should_wake(&self, subdomain: &str) -> bool {
        self.should_wake_at(subdomain, Instant::now())
    }

    /// Clear the throttle entry for `subdomain`.
    ///
    /// Called when the wake's job is done (the device's WS session has
    /// successfully registered). The previous wake is no longer in flight, so
    /// a fresh disconnect+reconnect cycle within the cooldown window deserves
    /// a new FCM push rather than being silently throttled. Without this,
    /// `should_wake` keeps returning `false` until `cooldown` elapses, even
    /// though the wake it was protecting against has already resolved (#423).
    ///
    /// Removing an entry that doesn't exist is a no-op.
    pub fn clear(&self, subdomain: &str) {
        self.last_wake.remove(subdomain);
    }

    /// Testable version that accepts a specific time instant.
    pub fn should_wake_at(&self, subdomain: &str, now: Instant) -> bool {
        let mut entry = self
            .last_wake
            .entry(subdomain.to_string())
            .or_insert_with(|| now - self.cooldown - Duration::from_secs(1));

        let last = *entry.value();
        if now.duration_since(last) >= self.cooldown {
            *entry.value_mut() = now;
            true
        } else {
            false
        }
    }
}

// ---------------------------------------------------------------------------
// Per-(IP, subdomain) connection rate limiter for passthrough.
// ---------------------------------------------------------------------------

/// Rejects scanners that open many passthrough connections to the same device
/// from a single source IP within a short window.
///
/// Uses a simple sliding-window counter: if the count exceeds `max_connections`
/// within `window`, the connection is rejected.
pub struct ConnectionRateLimiter {
    max_connections: u32,
    window: Duration,
    /// Key: (source_ip, subdomain) -> (count, window_start)
    counters: DashMap<(IpAddr, String), (u32, Instant)>,
}

impl ConnectionRateLimiter {
    pub fn new(max_connections: u32, window: Duration) -> Self {
        Self {
            max_connections,
            window,
            counters: DashMap::new(),
        }
    }

    /// Number of entries currently held (for tests / metrics).
    pub fn len(&self) -> usize {
        self.counters.len()
    }

    /// Returns `true` if the underlying map is empty.
    pub fn is_empty(&self) -> bool {
        self.counters.is_empty()
    }

    /// Drop entries whose sliding window has fully expired.
    ///
    /// Once `window_start` is older than the window itself, the next call to
    /// `allow_at` resets the counter — so the entry's state is equivalent to
    /// a never-seen `(ip, subdomain)`. We use 2× the window as a safety
    /// margin before evicting.
    pub fn sweep_expired(&self) {
        self.sweep_expired_at(Instant::now());
    }

    /// Testable version that accepts a specific time instant.
    pub fn sweep_expired_at(&self, now: Instant) {
        let ttl = self.window.saturating_mul(2);
        self.counters
            .retain(|_, (_, window_start)| now.duration_since(*window_start) < ttl);
    }

    /// Returns `true` if the connection is allowed, `false` if rate-limited.
    pub fn allow(&self, ip: IpAddr, subdomain: &str) -> bool {
        self.allow_at(ip, subdomain, Instant::now())
    }

    /// Testable version that accepts a specific time instant.
    pub fn allow_at(&self, ip: IpAddr, subdomain: &str, now: Instant) -> bool {
        let key = (ip, subdomain.to_string());
        let mut entry = self.counters.entry(key).or_insert_with(|| (0, now));
        let (count, window_start) = entry.value_mut();

        // Reset the window if it has expired
        if now.duration_since(*window_start) >= self.window {
            *count = 0;
            *window_start = now;
        }

        *count += 1;
        *count <= self.max_connections
    }
}

// ---------------------------------------------------------------------------
// Background sweep loop.
// ---------------------------------------------------------------------------

/// Periodically evicts expired entries from every rate-limiter DashMap so
/// attacker-varied keys (source IP / SNI label) cannot grow the maps without
/// bound. Runs until the shutdown signal fires.
pub async fn run_rate_limit_sweep_loop(
    interval: Duration,
    app_state: Arc<crate::api::AppState>,
    fcm_wake_throttle: Arc<FcmWakeThrottle>,
    conn_rate_limiter: Arc<ConnectionRateLimiter>,
    shutdown: crate::shutdown::ShutdownController,
) {
    tracing::info!(
        interval_secs = interval.as_secs(),
        "Rate-limit sweep loop started"
    );

    loop {
        tokio::select! {
            _ = tokio::time::sleep(interval) => {
                app_state.rate_limiter.sweep_expired();
                app_state.request_subdomain_rate_limiter.sweep_expired();
                fcm_wake_throttle.sweep_expired();
                conn_rate_limiter.sweep_expired();
                tracing::debug!(
                    rate_limiter = app_state.rate_limiter.len(),
                    request_subdomain_rate_limiter = app_state.request_subdomain_rate_limiter.len(),
                    fcm_wake_throttle = fcm_wake_throttle.len(),
                    conn_rate_limiter = conn_rate_limiter.len(),
                    "Rate-limit sweep completed"
                );
            }
            _ = shutdown.wait_for_shutdown() => {
                tracing::info!("Rate-limit sweep loop shutting down");
                return;
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn allows_up_to_max_burst() {
        let limiter = RateLimiter::new(RateLimitConfig {
            max_tokens: 3,
            refill_interval: Duration::from_secs(10),
        });
        let now = Instant::now();
        assert!(limiter.try_acquire_at("test", now).is_ok());
        assert!(limiter.try_acquire_at("test", now).is_ok());
        assert!(limiter.try_acquire_at("test", now).is_ok());
        assert!(limiter.try_acquire_at("test", now).is_err());
    }

    #[test]
    fn refills_over_time() {
        let limiter = RateLimiter::new(RateLimitConfig {
            max_tokens: 2,
            refill_interval: Duration::from_secs(10),
        });
        let now = Instant::now();
        // Drain the bucket
        assert!(limiter.try_acquire_at("test", now).is_ok());
        assert!(limiter.try_acquire_at("test", now).is_ok());
        assert!(limiter.try_acquire_at("test", now).is_err());

        // 10 seconds later, should have 1 token
        let later = now + Duration::from_secs(10);
        assert!(limiter.try_acquire_at("test", later).is_ok());
        assert!(limiter.try_acquire_at("test", later).is_err());
    }

    #[test]
    fn separate_subdomains_have_separate_buckets() {
        let limiter = RateLimiter::new(RateLimitConfig {
            max_tokens: 1,
            refill_interval: Duration::from_secs(10),
        });
        let now = Instant::now();
        assert!(limiter.try_acquire_at("a", now).is_ok());
        assert!(limiter.try_acquire_at("b", now).is_ok());
        assert!(limiter.try_acquire_at("a", now).is_err());
        assert!(limiter.try_acquire_at("b", now).is_err());
    }

    #[test]
    fn returns_retry_after_seconds() {
        let limiter = RateLimiter::new(RateLimitConfig {
            max_tokens: 1,
            refill_interval: Duration::from_secs(10),
        });
        let now = Instant::now();
        limiter.try_acquire_at("test", now).unwrap();
        let err = limiter.try_acquire_at("test", now).unwrap_err();
        assert_eq!(err, 10);
    }

    // --- FcmWakeThrottle tests ---

    #[test]
    fn fcm_throttle_allows_first_wake() {
        let throttle = FcmWakeThrottle::new(Duration::from_secs(30));
        let now = Instant::now();
        assert!(throttle.should_wake_at("dev1", now));
    }

    #[test]
    fn fcm_throttle_blocks_duplicate_within_cooldown() {
        let throttle = FcmWakeThrottle::new(Duration::from_secs(30));
        let now = Instant::now();
        assert!(throttle.should_wake_at("dev1", now));
        assert!(!throttle.should_wake_at("dev1", now + Duration::from_secs(10)));
        assert!(!throttle.should_wake_at("dev1", now + Duration::from_secs(29)));
    }

    #[test]
    fn fcm_throttle_allows_after_cooldown() {
        let throttle = FcmWakeThrottle::new(Duration::from_secs(30));
        let now = Instant::now();
        assert!(throttle.should_wake_at("dev1", now));
        assert!(throttle.should_wake_at("dev1", now + Duration::from_secs(30)));
    }

    #[test]
    fn fcm_throttle_clear_allows_immediate_rewake_within_cooldown() {
        // Regression for #423: once the device's WS session registers we
        // clear the throttle entry, so the next disconnect+reconnect cycle
        // inside the cooldown window still triggers a fresh FCM wake.
        let throttle = FcmWakeThrottle::new(Duration::from_secs(30));
        let now = Instant::now();
        assert!(throttle.should_wake_at("dev1", now));
        // Without clear: still throttled 5s later.
        assert!(!throttle.should_wake_at("dev1", now + Duration::from_secs(5)));
        throttle.clear("dev1");
        // After clear: a wake at the same instant is allowed again.
        assert!(throttle.should_wake_at("dev1", now + Duration::from_secs(5)));
    }

    #[test]
    fn fcm_throttle_clear_unknown_subdomain_is_noop() {
        let throttle = FcmWakeThrottle::new(Duration::from_secs(30));
        // Clearing a subdomain that was never inserted must not panic and
        // must leave the throttle behaviour unchanged for that subdomain.
        throttle.clear("never-seen");
        assert_eq!(throttle.len(), 0);
        // Subsequent should_wake on the same key still returns true on first call.
        assert!(throttle.should_wake_at("never-seen", Instant::now()));
    }

    #[test]
    fn fcm_throttle_separate_subdomains() {
        let throttle = FcmWakeThrottle::new(Duration::from_secs(30));
        let now = Instant::now();
        assert!(throttle.should_wake_at("dev1", now));
        assert!(throttle.should_wake_at("dev2", now));
        assert!(!throttle.should_wake_at("dev1", now));
    }

    // --- ConnectionRateLimiter tests ---

    #[test]
    fn conn_limiter_allows_up_to_max() {
        let limiter = ConnectionRateLimiter::new(3, Duration::from_secs(60));
        let ip: IpAddr = "1.2.3.4".parse().unwrap();
        let now = Instant::now();
        assert!(limiter.allow_at(ip, "dev1", now));
        assert!(limiter.allow_at(ip, "dev1", now));
        assert!(limiter.allow_at(ip, "dev1", now));
        assert!(!limiter.allow_at(ip, "dev1", now));
    }

    #[test]
    fn conn_limiter_resets_after_window() {
        let limiter = ConnectionRateLimiter::new(2, Duration::from_secs(60));
        let ip: IpAddr = "1.2.3.4".parse().unwrap();
        let now = Instant::now();
        assert!(limiter.allow_at(ip, "dev1", now));
        assert!(limiter.allow_at(ip, "dev1", now));
        assert!(!limiter.allow_at(ip, "dev1", now));
        // After the window expires, counter resets
        assert!(limiter.allow_at(ip, "dev1", now + Duration::from_secs(60)));
    }

    #[test]
    fn conn_limiter_separate_ips() {
        let limiter = ConnectionRateLimiter::new(1, Duration::from_secs(60));
        let ip1: IpAddr = "1.2.3.4".parse().unwrap();
        let ip2: IpAddr = "5.6.7.8".parse().unwrap();
        let now = Instant::now();
        assert!(limiter.allow_at(ip1, "dev1", now));
        assert!(limiter.allow_at(ip2, "dev1", now));
        assert!(!limiter.allow_at(ip1, "dev1", now));
        assert!(!limiter.allow_at(ip2, "dev1", now));
    }

    #[test]
    fn conn_limiter_separate_subdomains() {
        let limiter = ConnectionRateLimiter::new(1, Duration::from_secs(60));
        let ip: IpAddr = "1.2.3.4".parse().unwrap();
        let now = Instant::now();
        assert!(limiter.allow_at(ip, "dev1", now));
        assert!(limiter.allow_at(ip, "dev2", now));
        assert!(!limiter.allow_at(ip, "dev1", now));
    }

    // --- sweep_expired tests ---

    #[test]
    fn rate_limiter_sweep_removes_expired_entries() {
        let limiter = RateLimiter::new(RateLimitConfig {
            max_tokens: 2,
            refill_interval: Duration::from_secs(10),
        });
        let now = Instant::now();
        limiter.try_acquire_at("a", now).unwrap();
        limiter.try_acquire_at("b", now).unwrap();
        assert_eq!(limiter.len(), 2);

        // Before TTL (2 * 2 * 10s = 40s): nothing evicted.
        limiter.sweep_expired_at(now + Duration::from_secs(39));
        assert_eq!(limiter.len(), 2);

        // After TTL: both entries evicted.
        limiter.sweep_expired_at(now + Duration::from_secs(41));
        assert_eq!(limiter.len(), 0);
    }

    #[test]
    fn rate_limiter_sweep_keeps_recent_entries() {
        let limiter = RateLimiter::new(RateLimitConfig {
            max_tokens: 2,
            refill_interval: Duration::from_secs(10),
        });
        let now = Instant::now();
        limiter.try_acquire_at("old", now).unwrap();
        // "new" touched right before the sweep
        limiter
            .try_acquire_at("new", now + Duration::from_secs(40))
            .unwrap();
        assert_eq!(limiter.len(), 2);

        // 41s after "old" was seen (past 40s TTL), but "new" is only 1s old.
        limiter.sweep_expired_at(now + Duration::from_secs(41));
        assert_eq!(limiter.len(), 1);
        assert!(limiter.buckets.contains_key("new"));
    }

    #[test]
    fn fcm_throttle_sweep_removes_expired_entries() {
        let throttle = FcmWakeThrottle::new(Duration::from_secs(30));
        let now = Instant::now();
        throttle.should_wake_at("a", now);
        throttle.should_wake_at("b", now);
        assert_eq!(throttle.len(), 2);

        // Before TTL (2 * 30s = 60s): nothing evicted.
        throttle.sweep_expired_at(now + Duration::from_secs(59));
        assert_eq!(throttle.len(), 2);

        // After TTL: both evicted.
        throttle.sweep_expired_at(now + Duration::from_secs(61));
        assert_eq!(throttle.len(), 0);
    }

    #[test]
    fn conn_limiter_sweep_removes_expired_entries() {
        let limiter = ConnectionRateLimiter::new(5, Duration::from_secs(60));
        let ip1: IpAddr = "1.2.3.4".parse().unwrap();
        let ip2: IpAddr = "5.6.7.8".parse().unwrap();
        let now = Instant::now();
        assert!(limiter.allow_at(ip1, "dev1", now));
        assert!(limiter.allow_at(ip2, "dev2", now));
        assert_eq!(limiter.len(), 2);

        // Before TTL (2 * 60s = 120s): nothing evicted.
        limiter.sweep_expired_at(now + Duration::from_secs(119));
        assert_eq!(limiter.len(), 2);

        // After TTL: both entries evicted.
        limiter.sweep_expired_at(now + Duration::from_secs(121));
        assert_eq!(limiter.len(), 0);
    }

    #[test]
    fn conn_limiter_sweep_keeps_active_entries() {
        let limiter = ConnectionRateLimiter::new(5, Duration::from_secs(60));
        let ip1: IpAddr = "1.2.3.4".parse().unwrap();
        let ip2: IpAddr = "5.6.7.8".parse().unwrap();
        let t0 = Instant::now();
        assert!(limiter.allow_at(ip1, "dev1", t0));
        // ip2/dev2 touched 100s later — still fresh.
        assert!(limiter.allow_at(ip2, "dev2", t0 + Duration::from_secs(100)));

        // Sweep at 121s: ip1/dev1 is 121s old (past 120s TTL); ip2/dev2 is
        // only 21s old.
        limiter.sweep_expired_at(t0 + Duration::from_secs(121));
        assert_eq!(limiter.len(), 1);
        assert!(limiter.counters.contains_key(&(ip2, "dev2".to_string())));
    }

    #[test]
    fn sweep_is_idempotent_and_safe_on_empty() {
        let limiter = ConnectionRateLimiter::new(5, Duration::from_secs(60));
        let now = Instant::now();
        limiter.sweep_expired_at(now);
        limiter.sweep_expired_at(now + Duration::from_secs(3600));
        assert_eq!(limiter.len(), 0);
    }
}
