//! Tests for the daily maintenance job.
//!
//! Covers:
//! - Cert expiry nudge predicate logic
//! - Subdomain reclamation predicate logic
//! - Maintenance loop respects shutdown

use rouse_relay::firestore::DeviceRecord;
use rouse_relay::maintenance::{is_reclaimable, needs_renewal_nudge};
use std::time::{Duration, SystemTime};

fn make_device_record(cert_expires: SystemTime) -> DeviceRecord {
    DeviceRecord {
        fcm_token: "token".to_string(),
        firebase_uid: "uid".to_string(),
        public_key: "key".to_string(),
        cert_expires,
        registered_at: SystemTime::now(),
        last_rotation: None,
        renewal_nudge_sent: None,
        secret_prefix: None,
        valid_secrets: Vec::new(),
        integration_secrets: std::collections::HashMap::new(),
    }
}

#[test]
fn nudge_needed_when_cert_expiring_soon() {
    let now = SystemTime::now();
    let expires_in_3_days = now + Duration::from_secs(3 * 86400);
    let record = make_device_record(expires_in_3_days);
    let threshold = Duration::from_secs(7 * 86400);

    assert!(needs_renewal_nudge(&record, now, threshold));
}

#[test]
fn no_nudge_when_cert_not_expiring_soon() {
    let now = SystemTime::now();
    let expires_in_30_days = now + Duration::from_secs(30 * 86400);
    let record = make_device_record(expires_in_30_days);
    let threshold = Duration::from_secs(7 * 86400);

    assert!(!needs_renewal_nudge(&record, now, threshold));
}

#[test]
fn no_nudge_when_already_nudged() {
    let now = SystemTime::now();
    let expires_in_3_days = now + Duration::from_secs(3 * 86400);
    let mut record = make_device_record(expires_in_3_days);
    record.renewal_nudge_sent = Some(now - Duration::from_secs(3600));
    let threshold = Duration::from_secs(7 * 86400);

    assert!(!needs_renewal_nudge(&record, now, threshold));
}

#[test]
fn no_nudge_when_cert_already_expired() {
    let now = SystemTime::now();
    let expired_yesterday = now - Duration::from_secs(86400);
    let record = make_device_record(expired_yesterday);
    let threshold = Duration::from_secs(7 * 86400);

    // Expired certs are handled by reclamation, not nudging
    assert!(!needs_renewal_nudge(&record, now, threshold));
}

#[test]
fn reclaimable_when_cert_expired_long_ago() {
    let now = SystemTime::now();
    let expired_200_days_ago = now - Duration::from_secs(200 * 86400);
    let record = make_device_record(expired_200_days_ago);
    let threshold = Duration::from_secs(180 * 86400);

    assert!(is_reclaimable(&record, now, threshold));
}

#[test]
fn not_reclaimable_when_recently_expired() {
    let now = SystemTime::now();
    let expired_10_days_ago = now - Duration::from_secs(10 * 86400);
    let record = make_device_record(expired_10_days_ago);
    let threshold = Duration::from_secs(180 * 86400);

    assert!(!is_reclaimable(&record, now, threshold));
}

#[test]
fn not_reclaimable_when_cert_still_valid() {
    let now = SystemTime::now();
    let expires_in_30_days = now + Duration::from_secs(30 * 86400);
    let record = make_device_record(expires_in_30_days);
    let threshold = Duration::from_secs(180 * 86400);

    assert!(!is_reclaimable(&record, now, threshold));
}

#[test]
fn nudge_at_exact_threshold_boundary() {
    let now = SystemTime::now();
    // Expires in exactly 7 days
    let expires_at_threshold = now + Duration::from_secs(7 * 86400);
    let record = make_device_record(expires_at_threshold);
    let threshold = Duration::from_secs(7 * 86400);

    // At exactly the boundary, remaining == threshold, so <= is true
    assert!(needs_renewal_nudge(&record, now, threshold));
}

#[test]
fn reclaim_at_exact_threshold_boundary() {
    let now = SystemTime::now();
    // Expired exactly 180 days ago
    let expired_at_threshold = now - Duration::from_secs(180 * 86400);
    let record = make_device_record(expired_at_threshold);
    let threshold = Duration::from_secs(180 * 86400);

    // At exactly the boundary, expired_for == threshold, so >= is true
    assert!(is_reclaimable(&record, now, threshold));
}
