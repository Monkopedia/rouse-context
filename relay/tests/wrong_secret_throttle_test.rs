//! Per-(client IP, subdomain) throttle on invalid integration secrets (#811).
//!
//! After `max_failures` invalid-secret attempts inside the window, every
//! further attempt from that IP on that subdomain is closed -- including one
//! that carries a correct secret, so the block reveals nothing about which
//! guesses were right -- and none of them may trigger a device wake.

mod test_helpers;

use async_trait::async_trait;
use rouse_relay::firestore::{
    DeviceRecord, FirestoreClient, FirestoreError, PendingCert, SubdomainReservation,
};
use rouse_relay::passthrough::{
    resolve_device_stream_for_client, PassthroughContext, PassthroughError, SessionRegistry,
};
use rouse_relay::rate_limit::{FcmWakeThrottle, WrongSecretThrottle};
use rouse_relay::state::RelayState;
use std::net::IpAddr;
use std::sync::Arc;
use std::time::{Duration, Instant, SystemTime};
use test_helpers::{MockFcm, MockFirestore, MockUnifiedPush};

const SUB: &str = "cool-penguin";
const GOOD: &str = "brave-falcon";

fn ip(s: &str) -> IpAddr {
    s.parse().unwrap()
}

fn device_record() -> rouse_relay::firestore::DeviceRecord {
    rouse_relay::firestore::DeviceRecord {
        fcm_token: "fcm-token".to_string(),
        firebase_uid: "uid".to_string(),
        key_thumbprint: None,
        public_key: "key".to_string(),
        key_binding: rouse_relay::firestore::KeyBinding::Bound,
        cert_expires: SystemTime::now() + Duration::from_secs(86400),
        registered_at: SystemTime::now(),
        last_seen: None,
        last_rotation: None,
        renewal_nudge_sent: None,
        secret_prefix: None,
        push_kind: Default::default(),
        push_endpoint: String::new(),
        valid_secrets: vec![GOOD.to_string()],
        integration_secrets: std::collections::HashMap::new(),
        retired_secrets: std::collections::HashMap::new(),
        stale_secrets: Vec::new(),
    }
}

/// Context for an offline device: a correct secret reaches the wake path,
/// sends a push, and then times out quickly.
fn offline_ctx(fcm: Arc<MockFcm>, subdomains: &[&str]) -> PassthroughContext {
    let mut firestore = MockFirestore::new();
    for s in subdomains {
        firestore = firestore.with_device(s, device_record());
    }
    PassthroughContext {
        relay_state: Arc::new(RelayState::new()),
        session_registry: Arc::new(SessionRegistry::new()),
        firestore: Arc::new(firestore),
        fcm,
        unifiedpush: Arc::new(MockUnifiedPush::new()),
        relay_hostname: "relay.rousecontext.com".to_string(),
        fcm_wakeup_timeout: Duration::from_millis(50),
        // Zero cooldown: every attempt that reaches the wake path sends a push,
        // so the push count tells us exactly which attempts got through.
        fcm_wake_throttle: Arc::new(FcmWakeThrottle::new(Duration::ZERO)),
    }
}

fn sni(secret: &str, sub: &str) -> String {
    format!("{secret}.{sub}.rousecontext.com")
}

async fn attempt(
    ctx: &PassthroughContext,
    throttle: &WrongSecretThrottle,
    client: IpAddr,
    sub: &str,
    secret: &str,
) -> Result<(), PassthroughError> {
    resolve_device_stream_for_client(ctx, throttle, client, sub, &sni(secret, sub), secret)
        .await
        .map(|_| ())
}

async fn five_wrong(
    ctx: &PassthroughContext,
    throttle: &WrongSecretThrottle,
    client: IpAddr,
    sub: &str,
) {
    for i in 0..5 {
        let r = attempt(ctx, throttle, client, sub, &format!("wrong-guess{i}")).await;
        assert!(
            matches!(r, Err(PassthroughError::InvalidSecret)),
            "attempt {i} should be a plain invalid-secret reject, got {r:?}"
        );
    }
}

fn pushes(fcm: &MockFcm) -> usize {
    fcm.sent.lock().unwrap().len()
}

#[tokio::test]
async fn correct_secret_after_five_wrong_is_closed_without_wake() {
    let fcm = Arc::new(MockFcm::new());
    let ctx = offline_ctx(fcm.clone(), &[SUB]);
    let throttle = WrongSecretThrottle::new(5, Duration::from_secs(3600), 1000);
    let client = ip("203.0.113.7");

    five_wrong(&ctx, &throttle, client, SUB).await;
    assert_eq!(pushes(&fcm), 0, "wrong secrets never wake");

    let r = attempt(&ctx, &throttle, client, SUB, GOOD).await;
    assert!(
        matches!(r, Err(PassthroughError::SecretThrottled)),
        "6th attempt with the correct secret must be closed, got {r:?}"
    );
    assert_eq!(
        pushes(&fcm),
        0,
        "a blocked attempt must never wake the device"
    );
}

#[tokio::test]
async fn fewer_than_threshold_failures_do_not_block() {
    let fcm = Arc::new(MockFcm::new());
    let ctx = offline_ctx(fcm.clone(), &[SUB]);
    let throttle = WrongSecretThrottle::new(5, Duration::from_secs(3600), 1000);
    let client = ip("203.0.113.7");

    for i in 0..4 {
        let _ = attempt(&ctx, &throttle, client, SUB, &format!("wrong{i}")).await;
    }
    let r = attempt(&ctx, &throttle, client, SUB, GOOD).await;
    assert!(
        matches!(r, Err(PassthroughError::WakeupTimeout)),
        "4 failures is under the threshold; correct secret reaches the wake path, got {r:?}"
    );
    assert_eq!(pushes(&fcm), 1);
}

#[tokio::test]
async fn window_expiry_restores_access() {
    let fcm = Arc::new(MockFcm::new());
    let ctx = offline_ctx(fcm.clone(), &[SUB]);
    let throttle = WrongSecretThrottle::new(5, Duration::from_millis(300), 1000);
    let client = ip("203.0.113.7");

    five_wrong(&ctx, &throttle, client, SUB).await;
    assert!(matches!(
        attempt(&ctx, &throttle, client, SUB, GOOD).await,
        Err(PassthroughError::SecretThrottled)
    ));

    tokio::time::sleep(Duration::from_millis(400)).await;

    let r = attempt(&ctx, &throttle, client, SUB, GOOD).await;
    assert!(
        matches!(r, Err(PassthroughError::WakeupTimeout)),
        "after the window the correct secret reaches the wake path again, got {r:?}"
    );
    assert_eq!(pushes(&fcm), 1);
}

#[tokio::test]
async fn different_ip_is_unaffected() {
    let fcm = Arc::new(MockFcm::new());
    let ctx = offline_ctx(fcm.clone(), &[SUB]);
    let throttle = WrongSecretThrottle::new(5, Duration::from_secs(3600), 1000);

    five_wrong(&ctx, &throttle, ip("203.0.113.7"), SUB).await;

    let r = attempt(&ctx, &throttle, ip("198.51.100.9"), SUB, GOOD).await;
    assert!(
        matches!(r, Err(PassthroughError::WakeupTimeout)),
        "another IP on the same subdomain is not blocked, got {r:?}"
    );
    assert_eq!(pushes(&fcm), 1);
}

#[tokio::test]
async fn different_subdomain_is_unaffected() {
    let fcm = Arc::new(MockFcm::new());
    let ctx = offline_ctx(fcm.clone(), &[SUB, "other-device"]);
    let throttle = WrongSecretThrottle::new(5, Duration::from_secs(3600), 1000);
    let client = ip("203.0.113.7");

    five_wrong(&ctx, &throttle, client, SUB).await;

    let r = attempt(&ctx, &throttle, client, "other-device", GOOD).await;
    assert!(
        matches!(r, Err(PassthroughError::WakeupTimeout)),
        "the same IP on another subdomain is not blocked, got {r:?}"
    );
    assert_eq!(pushes(&fcm), 1);
}

#[tokio::test]
async fn correct_secrets_do_not_count_as_failures() {
    let fcm = Arc::new(MockFcm::new());
    let ctx = offline_ctx(fcm.clone(), &[SUB]);
    let throttle = WrongSecretThrottle::new(5, Duration::from_secs(3600), 1000);
    let client = ip("203.0.113.7");

    for _ in 0..8 {
        let r = attempt(&ctx, &throttle, client, SUB, GOOD).await;
        assert!(matches!(r, Err(PassthroughError::WakeupTimeout)));
    }
    assert_eq!(pushes(&fcm), 8);
    assert!(
        throttle.is_empty(),
        "correct-secret traffic leaves no entry behind"
    );
}

// --- Throttle-level tests (injected clock) ---

#[test]
fn throttle_blocks_after_threshold_until_window_ends() {
    let t = WrongSecretThrottle::new(5, Duration::from_secs(3600), 1000);
    let a = ip("203.0.113.7");
    let t0 = Instant::now();
    for i in 0..5 {
        assert!(
            !t.is_blocked_at(a, SUB, t0),
            "not blocked before failure {i}"
        );
        t.try_admit_at(a, SUB, &format!("g{i}"), t0 + Duration::from_secs(i))
            .unwrap()
            .failed();
    }
    assert!(t.is_blocked_at(a, SUB, t0 + Duration::from_secs(10)));
    assert!(t.is_blocked_at(a, SUB, t0 + Duration::from_secs(3599)));
    assert!(!t.is_blocked_at(a, SUB, t0 + Duration::from_secs(3600)));
}

#[test]
fn state_stays_bounded_under_many_distinct_keys() {
    let cap = 64;
    let t = WrongSecretThrottle::new(5, Duration::from_secs(3600), cap);
    let t0 = Instant::now();
    for i in 0..10_000u32 {
        let addr = IpAddr::from(std::net::Ipv4Addr::from(0x0a00_0000 + i));
        if let Some(a) = t.try_admit_at(addr, &format!("sub{}", i % 7), "g", t0) {
            a.failed();
        }
    }
    assert!(t.len() <= cap, "len {} exceeds cap {cap}", t.len());
}

#[test]
fn expired_entries_are_swept_and_make_room() {
    let cap = 8;
    let t = WrongSecretThrottle::new(5, Duration::from_secs(60), cap);
    let t0 = Instant::now();
    for i in 0..cap as u32 {
        t.try_admit_at(IpAddr::from(std::net::Ipv4Addr::from(i)), SUB, "g", t0)
            .unwrap()
            .failed();
    }
    assert_eq!(t.len(), cap);

    t.sweep_expired_at(t0 + Duration::from_secs(59));
    assert_eq!(t.len(), cap, "nothing evicted inside the window");
    t.sweep_expired_at(t0 + Duration::from_secs(61));
    assert_eq!(t.len(), 0, "all entries evicted after the window");

    // A full table of expired entries must not stop new failures being tracked.
    for i in 0..cap as u32 {
        t.try_admit_at(IpAddr::from(std::net::Ipv4Addr::from(i)), SUB, "g", t0)
            .unwrap()
            .failed();
    }
    let fresh = ip("203.0.113.7");
    let later = t0 + Duration::from_secs(120);
    for i in 0..5 {
        t.try_admit_at(fresh, SUB, &format!("g{i}"), later)
            .unwrap()
            .failed();
    }
    assert!(t.len() <= cap);
    assert!(
        t.is_blocked_at(fresh, SUB, later),
        "expired entries are reclaimed so a new offender is still tracked"
    );
}

// --- Concurrency: the budget must hold across the awaited secret lookup ---

/// Firestore whose `get_device` takes 30 ms, so concurrent attempts overlap
/// the secret lookup (cold cache, or legacy `secret_prefix` devices, which are
/// never cached).
struct Slow(MockFirestore);
#[async_trait]
impl FirestoreClient for Slow {
    async fn get_device(&self, s: &str) -> Result<DeviceRecord, FirestoreError> {
        tokio::time::sleep(Duration::from_millis(30)).await;
        self.0.get_device(s).await
    }
    async fn find_device_by_uid(
        &self,
        a: &str,
    ) -> Result<Option<(String, DeviceRecord)>, FirestoreError> {
        self.0.find_device_by_uid(a).await
    }
    async fn find_device_by_thumbprint(
        &self,
        a: &str,
    ) -> Result<Option<(String, DeviceRecord)>, FirestoreError> {
        self.0.find_device_by_thumbprint(a).await
    }
    async fn create_device(&self, s: &str, r: &DeviceRecord) -> Result<(), FirestoreError> {
        self.0.create_device(s, r).await
    }
    async fn put_device(&self, s: &str, r: &DeviceRecord) -> Result<(), FirestoreError> {
        self.0.put_device(s, r).await
    }
    async fn set_device_last_seen(&self, s: &str, t: SystemTime) -> Result<(), FirestoreError> {
        self.0.set_device_last_seen(s, t).await
    }
    async fn delete_device(&self, s: &str) -> Result<(), FirestoreError> {
        self.0.delete_device(s).await
    }
    async fn put_pending_cert(&self, s: &str, p: &PendingCert) -> Result<(), FirestoreError> {
        self.0.put_pending_cert(s, p).await
    }
    async fn get_pending_cert(&self, s: &str) -> Result<PendingCert, FirestoreError> {
        self.0.get_pending_cert(s).await
    }
    async fn delete_pending_cert(&self, s: &str) -> Result<(), FirestoreError> {
        self.0.delete_pending_cert(s).await
    }
    async fn list_devices(&self) -> Result<Vec<(String, DeviceRecord)>, FirestoreError> {
        self.0.list_devices().await
    }
    async fn list_pending_certs(&self) -> Result<Vec<(String, PendingCert)>, FirestoreError> {
        self.0.list_pending_certs().await
    }
    async fn put_reservation(
        &self,
        s: &str,
        r: &SubdomainReservation,
    ) -> Result<(), FirestoreError> {
        self.0.put_reservation(s, r).await
    }
    async fn get_reservation(&self, s: &str) -> Result<SubdomainReservation, FirestoreError> {
        self.0.get_reservation(s).await
    }
    async fn find_reservation_by_uid(
        &self,
        a: &str,
    ) -> Result<Option<(String, SubdomainReservation)>, FirestoreError> {
        self.0.find_reservation_by_uid(a).await
    }
    async fn delete_reservation(&self, s: &str) -> Result<(), FirestoreError> {
        self.0.delete_reservation(s).await
    }
    async fn list_reservations(
        &self,
    ) -> Result<Vec<(String, SubdomainReservation)>, FirestoreError> {
        self.0.list_reservations().await
    }
}

fn slow_ctx(fcm: Arc<MockFcm>, record: DeviceRecord) -> PassthroughContext {
    let mut ctx = offline_ctx(fcm, &[]);
    ctx.firestore = Arc::new(Slow(MockFirestore::new().with_device(SUB, record)));
    ctx
}

fn tally(rs: &[Result<(), PassthroughError>]) -> (usize, usize) {
    let invalid = rs
        .iter()
        .filter(|r| matches!(r, Err(PassthroughError::InvalidSecret)))
        .count();
    let throttled = rs
        .iter()
        .filter(|r| matches!(r, Err(PassthroughError::SecretThrottled)))
        .count();
    (invalid, throttled)
}

#[tokio::test]
async fn concurrent_burst_on_cold_cache_evaluates_at_most_max_guesses() {
    let fcm = Arc::new(MockFcm::new());
    let ctx = slow_ctx(fcm.clone(), device_record());
    let throttle = WrongSecretThrottle::new(5, Duration::from_secs(3600), 1000);
    let client = ip("203.0.113.7");

    // 199 distinct wrong guesses plus the correct secret, all in flight at once.
    let futs: Vec<_> = (0..200)
        .map(|i| {
            let secret = if i == 199 {
                GOOD.to_string()
            } else {
                format!("wrong{i}")
            };
            let (ctx, throttle) = (&ctx, &throttle);
            async move { attempt(ctx, throttle, client, SUB, &secret).await }
        })
        .collect();
    let rs = futures_util::future::join_all(futs).await;
    let (invalid, throttled) = tally(&rs);

    assert!(invalid <= 5, "{invalid} guesses evaluated; budget is 5");
    assert_eq!(invalid + throttled, 200, "every other attempt is refused");
    assert_eq!(
        pushes(&fcm),
        0,
        "the correct secret came after the budget; no wake"
    );
    assert!(throttle.is_blocked(client, SUB));
}

#[tokio::test]
async fn concurrent_burst_on_legacy_secret_prefix_evaluates_at_most_max_guesses() {
    let fcm = Arc::new(MockFcm::new());
    let mut record = device_record();
    record.valid_secrets.clear();
    record.secret_prefix = Some(GOOD.to_string());
    let ctx = slow_ctx(fcm.clone(), record);
    let throttle = WrongSecretThrottle::new(5, Duration::from_secs(3600), 1000);
    let client = ip("203.0.113.7");

    // One sequential failure first, then a concurrent burst.
    let first = attempt(&ctx, &throttle, client, SUB, "warm").await;
    assert!(matches!(first, Err(PassthroughError::InvalidSecret)));

    let futs: Vec<_> = (0..100)
        .map(|i| {
            let (ctx, throttle) = (&ctx, &throttle);
            async move { attempt(ctx, throttle, client, SUB, &format!("w{i}")).await }
        })
        .collect();
    let rs = futures_util::future::join_all(futs).await;
    let (invalid, throttled) = tally(&rs);

    assert!(
        invalid <= 4,
        "{invalid} more guesses evaluated; only 4 remained"
    );
    assert_eq!(invalid + throttled, 100);
    assert!(throttle.is_blocked(client, SUB));
}

#[tokio::test]
async fn concurrent_connections_with_the_correct_secret_are_not_refused() {
    // A real client opens many connections at once with the same URL. On a
    // cold cache they all overlap the lookup; they must share one slot, not
    // exhaust the budget.
    let fcm = Arc::new(MockFcm::new());
    let ctx = slow_ctx(fcm.clone(), device_record());
    let throttle = WrongSecretThrottle::new(5, Duration::from_secs(3600), 1000);
    let client = ip("203.0.113.7");

    // Four earlier failures leave a single slot.
    for i in 0..4 {
        let _ = attempt(&ctx, &throttle, client, SUB, &format!("wrong{i}")).await;
    }
    // Fresh relay state: the secrets cache is cold again (as after a deploy),
    // while the throttle keeps its four failures.
    let ctx = slow_ctx(fcm.clone(), device_record());

    let futs: Vec<_> = (0..20)
        .map(|_| {
            let (ctx, throttle) = (&ctx, &throttle);
            async move { attempt(ctx, throttle, client, SUB, GOOD).await }
        })
        .collect();
    let rs = futures_util::future::join_all(futs).await;
    for r in &rs {
        assert!(
            matches!(r, Err(PassthroughError::WakeupTimeout)),
            "correct-secret connection should reach the wake path, got {r:?}"
        );
    }
    assert!(
        !throttle.is_blocked(client, SUB),
        "correct secrets never count"
    );
}

// --- Former (stale) secrets do not count toward the throttle ---
//
// A stale client still using a URL whose secret was replaced (e.g. an old
// connector after disable/re-enable) must not lock out the working URL from
// the same egress IP. Such attempts are still closed like any other invalid
// secret. End-to-end coverage through /rotate-secret is in
// stale_secret_throttle_test.rs.

const RETIRED: &str = "stale-health";

fn record_with_retired() -> DeviceRecord {
    let mut r = device_record();
    r.stale_secrets = vec![RETIRED.to_string()];
    r
}

fn retired_ctx(fcm: Arc<MockFcm>) -> PassthroughContext {
    let mut ctx = offline_ctx(fcm, &[]);
    ctx.firestore = Arc::new(MockFirestore::new().with_device(SUB, record_with_retired()));
    ctx
}

#[tokio::test]
async fn retired_secret_attempts_do_not_block_the_correct_secret() {
    let fcm = Arc::new(MockFcm::new());
    let ctx = retired_ctx(fcm.clone());
    let throttle = WrongSecretThrottle::new(5, Duration::from_secs(3600), 1000);
    let client = ip("203.0.113.7");

    // First attempt misses the cache (Firestore path); the rest hit it.
    for i in 0..20 {
        let r = attempt(&ctx, &throttle, client, SUB, RETIRED).await;
        assert!(
            matches!(r, Err(PassthroughError::InvalidSecret)),
            "retired attempt {i} is closed like any invalid secret, got {r:?}"
        );
    }
    assert_eq!(pushes(&fcm), 0, "retired secrets never wake");
    assert!(!throttle.is_blocked(client, SUB));

    let r = attempt(&ctx, &throttle, client, SUB, GOOD).await;
    assert!(
        matches!(r, Err(PassthroughError::WakeupTimeout)),
        "correct secret is admitted after retired attempts, got {r:?}"
    );
    assert_eq!(pushes(&fcm), 1);
}

#[tokio::test]
async fn retired_secret_set_by_cache_writers_is_honoured() {
    // The rotate/register write path fills the cache; the retired set must
    // travel with it so a warm cache does not count retired attempts.
    let fcm = Arc::new(MockFcm::new());
    let ctx = retired_ctx(fcm.clone());
    ctx.relay_state
        .set_secrets_cache(SUB, vec![GOOD.to_string()], vec![RETIRED.to_string()]);
    let throttle = WrongSecretThrottle::new(5, Duration::from_secs(3600), 1000);
    let client = ip("203.0.113.7");
    for _ in 0..10 {
        let r = attempt(&ctx, &throttle, client, SUB, RETIRED).await;
        assert!(matches!(r, Err(PassthroughError::InvalidSecret)));
    }
    assert!(!throttle.is_blocked(client, SUB));
}

#[tokio::test]
async fn retired_attempts_mixed_with_four_guesses_do_not_block_but_a_fifth_guess_does() {
    let fcm = Arc::new(MockFcm::new());
    let ctx = retired_ctx(fcm.clone());
    let throttle = WrongSecretThrottle::new(5, Duration::from_secs(3600), 1000);
    let client = ip("203.0.113.7");

    for i in 0..4 {
        let _ = attempt(&ctx, &throttle, client, SUB, RETIRED).await;
        let r = attempt(&ctx, &throttle, client, SUB, &format!("guess{i}")).await;
        assert!(matches!(r, Err(PassthroughError::InvalidSecret)));
        for _ in 0..3 {
            let _ = attempt(&ctx, &throttle, client, SUB, RETIRED).await;
        }
    }
    assert!(
        !throttle.is_blocked(client, SUB),
        "4 guesses plus retired attempts"
    );

    let r = attempt(&ctx, &throttle, client, SUB, "guess4").await;
    assert!(matches!(r, Err(PassthroughError::InvalidSecret)));
    assert!(
        throttle.is_blocked(client, SUB),
        "the 5th random guess blocks"
    );
    assert!(matches!(
        attempt(&ctx, &throttle, client, SUB, GOOD).await,
        Err(PassthroughError::SecretThrottled)
    ));
    assert!(matches!(
        attempt(&ctx, &throttle, client, SUB, RETIRED).await,
        Err(PassthroughError::SecretThrottled)
    ));
}

#[tokio::test]
async fn concurrent_burst_with_retired_labels_evaluates_at_most_max_random_guesses() {
    let fcm = Arc::new(MockFcm::new());
    let ctx = slow_ctx(fcm.clone(), record_with_retired());
    let throttle = WrongSecretThrottle::new(5, Duration::from_secs(3600), 1000);
    let client = ip("203.0.113.7");

    // Interleave retired attempts with distinct random guesses, all at once.
    let futs: Vec<_> = (0..200)
        .map(|i| {
            let secret = if i % 2 == 0 {
                RETIRED.to_string()
            } else {
                format!("wrong{i}")
            };
            let (ctx, throttle) = (&ctx, &throttle);
            async move {
                (
                    i % 2 == 1,
                    attempt(ctx, throttle, client, SUB, &secret).await,
                )
            }
        })
        .collect();
    let rs = futures_util::future::join_all(futs).await;
    let random_evaluated = rs
        .iter()
        .filter(|(random, r)| *random && matches!(r, Err(PassthroughError::InvalidSecret)))
        .count();
    assert!(
        random_evaluated <= 5,
        "{random_evaluated} random guesses evaluated; budget is 5"
    );
    assert_eq!(pushes(&fcm), 0);

    // Afterwards the budget still caps sequential random guesses at 5 total.
    let mut more = 0;
    for i in 0..10 {
        if matches!(
            attempt(&ctx, &throttle, client, SUB, &format!("later{i}")).await,
            Err(PassthroughError::InvalidSecret)
        ) {
            more += 1;
        }
    }
    assert!(
        random_evaluated + more <= 5,
        "{random_evaluated} + {more} > 5"
    );
    assert!(throttle.is_blocked(client, SUB));
}

#[test]
fn shared_slot_failure_is_counted_even_if_the_last_sharer_is_dropped() {
    // Two connections share a slot for the same wrong secret. One settles as
    // failed; the other is dropped unsettled (cancelled / lookup error). The
    // evaluated wrong guess must still count.
    let t = WrongSecretThrottle::new(5, Duration::from_secs(3600), 1000);
    let a = ip("203.0.113.7");
    let t0 = Instant::now();
    let mut blocked_after = None;
    for i in 0..20 {
        let secret = format!("guess{i}");
        let (Some(first), Some(second)) = (
            t.try_admit_at(a, SUB, &secret, t0),
            t.try_admit_at(a, SUB, &secret, t0),
        ) else {
            break;
        };
        first.failed();
        drop(second);
        if t.is_blocked_at(a, SUB, t0) {
            blocked_after = Some(i + 1);
            break;
        }
    }
    assert_eq!(blocked_after, Some(5), "each shared failure counts once");
}
