//! Per-subdomain token bucket rate limiter for `/wake`.
//!
//! In-memory state that resets on relay restart. Each subdomain gets its
//! own bucket: 6 tokens max, refilling at 1 token per 10 seconds.

use dashmap::DashMap;
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
}
