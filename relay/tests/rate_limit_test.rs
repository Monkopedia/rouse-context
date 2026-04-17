use rouse_relay::rate_limit::{
    ConnectionRateLimiter, FcmWakeThrottle, RateLimitConfig, RateLimiter,
};
use std::net::IpAddr;
use std::time::{Duration, Instant};

#[test]
fn wake_throttle_allows_first_wake_immediately() {
    let throttle = FcmWakeThrottle::new(Duration::from_secs(10));
    let now = Instant::now();
    assert!(
        throttle.should_wake_at("dev1", now),
        "First wake should be allowed immediately"
    );
}

#[test]
fn wake_throttle_blocks_second_wake_within_10s() {
    let throttle = FcmWakeThrottle::new(Duration::from_secs(10));
    let now = Instant::now();

    assert!(throttle.should_wake_at("dev1", now));

    // 1 second later — should be blocked
    assert!(
        !throttle.should_wake_at("dev1", now + Duration::from_secs(1)),
        "Wake within 1s should be blocked"
    );

    // 5 seconds later — still blocked
    assert!(
        !throttle.should_wake_at("dev1", now + Duration::from_secs(5)),
        "Wake within 5s should be blocked"
    );

    // 9 seconds later — still blocked
    assert!(
        !throttle.should_wake_at("dev1", now + Duration::from_secs(9)),
        "Wake within 9s should be blocked"
    );
}

#[test]
fn wake_throttle_allows_wake_after_10s() {
    let throttle = FcmWakeThrottle::new(Duration::from_secs(10));
    let now = Instant::now();

    assert!(throttle.should_wake_at("dev1", now));
    assert!(
        throttle.should_wake_at("dev1", now + Duration::from_secs(10)),
        "Wake at exactly 10s should be allowed"
    );
}

#[test]
fn wake_throttle_allows_wake_well_after_cooldown() {
    let throttle = FcmWakeThrottle::new(Duration::from_secs(10));
    let now = Instant::now();

    assert!(throttle.should_wake_at("dev1", now));
    assert!(
        throttle.should_wake_at("dev1", now + Duration::from_secs(60)),
        "Wake at 60s should be allowed"
    );
}

#[test]
fn wake_throttle_multiple_subdomains_are_independent() {
    let throttle = FcmWakeThrottle::new(Duration::from_secs(10));
    let now = Instant::now();

    // Wake both subdomains
    assert!(throttle.should_wake_at("dev1", now));
    assert!(throttle.should_wake_at("dev2", now));

    // Both should be throttled within the cooldown
    assert!(
        !throttle.should_wake_at("dev1", now + Duration::from_secs(5)),
        "dev1 should still be throttled"
    );
    assert!(
        !throttle.should_wake_at("dev2", now + Duration::from_secs(5)),
        "dev2 should still be throttled"
    );

    // dev1 cooldown expires, dev2 remains throttled (different timing)
    let dev1_expired = now + Duration::from_secs(10);
    assert!(
        throttle.should_wake_at("dev1", dev1_expired),
        "dev1 cooldown should have expired"
    );
    assert!(
        throttle.should_wake_at("dev2", dev1_expired),
        "dev2 cooldown should have expired at same time"
    );

    // Now dev1 was just woken at dev1_expired, dev2 also just woken
    // dev1 blocked again, dev2 blocked again
    assert!(
        !throttle.should_wake_at("dev1", dev1_expired + Duration::from_secs(5)),
        "dev1 should be throttled again after re-wake"
    );
    assert!(
        !throttle.should_wake_at("dev2", dev1_expired + Duration::from_secs(5)),
        "dev2 should be throttled again after re-wake"
    );
}

// ── Sweep / unbounded-growth regression tests (issue #203) ─────────────

#[test]
fn fcm_throttle_sweep_caps_unbounded_growth_from_varying_keys() {
    // Simulate the DoS: attacker walks through many subdomain labels.
    let throttle = FcmWakeThrottle::new(Duration::from_secs(10));
    let t0 = Instant::now();
    for i in 0..1000 {
        throttle.should_wake_at(&format!("attacker-{i}"), t0);
    }
    assert_eq!(throttle.len(), 1000);

    // Advance past TTL (2 × cooldown = 20s) and sweep.
    throttle.sweep_expired_at(t0 + Duration::from_secs(21));
    assert_eq!(
        throttle.len(),
        0,
        "all stale throttle entries must be evicted"
    );
}

#[test]
fn conn_limiter_sweep_caps_unbounded_growth_from_varying_keys() {
    // Simulate the DoS: many (ip, subdomain) combinations from a scan.
    let limiter = ConnectionRateLimiter::new(5, Duration::from_secs(60));
    let t0 = Instant::now();
    for i in 0..500 {
        let ip: IpAddr = format!("10.0.{}.{}", (i / 256) % 256, i % 256)
            .parse()
            .unwrap();
        let sub = format!("scan-{i}");
        limiter.allow_at(ip, &sub, t0);
    }
    assert_eq!(limiter.len(), 500);

    // Advance past TTL (2 × window = 120s) and sweep.
    limiter.sweep_expired_at(t0 + Duration::from_secs(121));
    assert_eq!(
        limiter.len(),
        0,
        "all stale conn-limiter entries must be evicted"
    );
}

#[test]
fn rate_limiter_sweep_caps_unbounded_growth_from_varying_keys() {
    // /request-subdomain style: attacker drives many distinct keys.
    let limiter = RateLimiter::new(RateLimitConfig {
        max_tokens: 3,
        refill_interval: Duration::from_secs(10),
    });
    let t0 = Instant::now();
    for i in 0..500 {
        limiter.try_acquire_at(&format!("key-{i}"), t0).unwrap();
    }
    assert_eq!(limiter.len(), 500);

    // TTL is 2 × max_tokens × refill_interval = 60s.
    limiter.sweep_expired_at(t0 + Duration::from_secs(61));
    assert_eq!(limiter.len(), 0, "all stale buckets must be evicted");
}

#[test]
fn sweep_preserves_hot_path_decisions() {
    // Semantic invariant: sweeping never changes what the limiter allows.
    // An entry that has aged past its window is equivalent (from the
    // limiter's perspective) to a never-seen entry.
    let limiter = ConnectionRateLimiter::new(2, Duration::from_secs(60));
    let ip: IpAddr = "1.2.3.4".parse().unwrap();
    let t0 = Instant::now();

    // Saturate the limit, then advance past the window and sweep.
    assert!(limiter.allow_at(ip, "dev1", t0));
    assert!(limiter.allow_at(ip, "dev1", t0));
    assert!(!limiter.allow_at(ip, "dev1", t0));

    let later = t0 + Duration::from_secs(200);
    limiter.sweep_expired_at(later);
    assert_eq!(limiter.len(), 0);

    // Post-sweep behaviour matches a fresh limiter.
    assert!(limiter.allow_at(ip, "dev1", later));
    assert!(limiter.allow_at(ip, "dev1", later));
    assert!(!limiter.allow_at(ip, "dev1", later));
}

#[test]
fn wake_throttle_successive_wakes_reset_cooldown() {
    let throttle = FcmWakeThrottle::new(Duration::from_secs(10));
    let now = Instant::now();

    // First wake at t=0
    assert!(throttle.should_wake_at("dev1", now));

    // Second wake at t=10 (cooldown just expired)
    assert!(throttle.should_wake_at("dev1", now + Duration::from_secs(10)));

    // Blocked at t=15 (only 5s after second wake)
    assert!(
        !throttle.should_wake_at("dev1", now + Duration::from_secs(15)),
        "Should be blocked because cooldown restarted at t=10"
    );

    // Allowed at t=20 (10s after second wake)
    assert!(
        throttle.should_wake_at("dev1", now + Duration::from_secs(20)),
        "Should be allowed 10s after second wake"
    );
}
