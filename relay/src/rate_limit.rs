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

    /// Returns `true` if an FCM wake should be sent (cooldown expired or first wake).
    /// Returns `false` if a wake was already sent within the cooldown window.
    pub fn should_wake(&self, subdomain: &str) -> bool {
        self.should_wake_at(subdomain, Instant::now())
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
}
