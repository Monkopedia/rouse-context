use rouse_relay::rate_limit::FcmWakeThrottle;
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
