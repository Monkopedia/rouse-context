//! Integration tests for run_maintenance_once with wired Firestore list operations.
//!
//! Covers:
//! - Devices needing nudge get FCM renew messages
//! - Reclaimable devices get deleted
//! - Pending certs processed when retry_after has passed
//! - Rate-limited ACME stops pending cert processing

mod test_helpers;

use rouse_relay::firestore::{DeviceRecord, PendingCert};
use rouse_relay::maintenance::{run_maintenance_once, MaintenanceConfig};
use std::sync::Arc;
use std::time::{Duration, SystemTime};
use test_helpers::{MockAcme, MockDns, MockFcm, MockFirestore};

fn make_device(cert_expires: SystemTime) -> DeviceRecord {
    DeviceRecord {
        fcm_token: "test-fcm-token".to_string(),
        firebase_uid: "uid-1".to_string(),
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

fn make_pending_cert(retry_after: SystemTime) -> PendingCert {
    use base64::Engine;
    PendingCert {
        fcm_token: "pending-fcm-token".to_string(),
        csr: base64::engine::general_purpose::STANDARD.encode(b"fake-csr-bytes"),
        blocked_at: SystemTime::now() - Duration::from_secs(86400),
        retry_after,
    }
}

fn test_config() -> MaintenanceConfig {
    MaintenanceConfig {
        interval: Duration::from_secs(1),
        cert_expiry_nudge_days: 7,
        subdomain_reclaim_days: 180,
        stale_device_sweep_days: 180,
        relay_hostname: "test.rousecontext.com".to_string(),
    }
}

fn stub_dns() -> Arc<dyn rouse_relay::dns::DnsClient> {
    Arc::new(MockDns::new())
}

#[tokio::test]
async fn nudge_sent_for_expiring_device() {
    let now = SystemTime::now();
    let expires_in_3_days = now + Duration::from_secs(3 * 86400);
    let device = make_device(expires_in_3_days);

    let firestore = Arc::new(MockFirestore::new().with_device("abc123", device));
    let fcm = Arc::new(MockFcm::new());
    let acme = Arc::new(MockAcme::new("fake-cert"));

    let report = run_maintenance_once(
        &test_config(),
        &(firestore.clone() as Arc<dyn rouse_relay::firestore::FirestoreClient>),
        &(fcm.clone() as Arc<dyn rouse_relay::fcm::FcmClient>),
        &(acme as Arc<dyn rouse_relay::acme::AcmeClient>),
        &stub_dns(),
    )
    .await;

    assert_eq!(report.nudges_sent, 1);
    assert_eq!(report.nudge_errors, 0);

    // Verify FCM was sent with "renew" type
    let sent = fcm.sent.lock().unwrap();
    assert_eq!(sent.len(), 1);
    assert_eq!(sent[0].0, "test-fcm-token");
    assert_eq!(sent[0].1.message_type, "renew");

    // Verify nudge timestamp was persisted
    let updated = firestore.devices.lock().unwrap();
    let record = updated.get("abc123").unwrap();
    assert!(record.renewal_nudge_sent.is_some());
}

#[tokio::test]
async fn no_nudge_for_healthy_device() {
    let now = SystemTime::now();
    let expires_in_30_days = now + Duration::from_secs(30 * 86400);
    let device = make_device(expires_in_30_days);

    let firestore = Arc::new(MockFirestore::new().with_device("healthy", device));
    let fcm = Arc::new(MockFcm::new());
    let acme = Arc::new(MockAcme::new("fake-cert"));

    let report = run_maintenance_once(
        &test_config(),
        &(firestore as Arc<dyn rouse_relay::firestore::FirestoreClient>),
        &(fcm.clone() as Arc<dyn rouse_relay::fcm::FcmClient>),
        &(acme as Arc<dyn rouse_relay::acme::AcmeClient>),
        &stub_dns(),
    )
    .await;

    assert_eq!(report.nudges_sent, 0);
    assert!(fcm.sent.lock().unwrap().is_empty());
}

#[tokio::test]
async fn reclaimable_device_gets_deleted() {
    let now = SystemTime::now();
    let expired_200_days_ago = now - Duration::from_secs(200 * 86400);
    let device = make_device(expired_200_days_ago);

    let firestore = Arc::new(MockFirestore::new().with_device("old-device", device));
    let fcm = Arc::new(MockFcm::new());
    let acme = Arc::new(MockAcme::new("fake-cert"));

    let report = run_maintenance_once(
        &test_config(),
        &(firestore.clone() as Arc<dyn rouse_relay::firestore::FirestoreClient>),
        &(fcm as Arc<dyn rouse_relay::fcm::FcmClient>),
        &(acme as Arc<dyn rouse_relay::acme::AcmeClient>),
        &stub_dns(),
    )
    .await;

    assert_eq!(report.subdomains_reclaimed, 1);
    assert_eq!(report.reclaim_errors, 0);

    // Verify device was deleted
    let devices = firestore.devices.lock().unwrap();
    assert!(!devices.contains_key("old-device"));
}

#[tokio::test]
async fn pending_cert_processed_when_retry_after_passed() {
    let now = SystemTime::now();
    let retry_in_past = now - Duration::from_secs(3600);
    let pending = make_pending_cert(retry_in_past);

    let firestore = Arc::new(MockFirestore::new());
    firestore
        .pending_certs
        .lock()
        .unwrap()
        .insert("pending-sub".to_string(), pending);

    let fcm = Arc::new(MockFcm::new());
    let acme = Arc::new(MockAcme::new("issued-cert-pem"));

    let report = run_maintenance_once(
        &test_config(),
        &(firestore.clone() as Arc<dyn rouse_relay::firestore::FirestoreClient>),
        &(fcm.clone() as Arc<dyn rouse_relay::fcm::FcmClient>),
        &(acme as Arc<dyn rouse_relay::acme::AcmeClient>),
        &stub_dns(),
    )
    .await;

    assert_eq!(report.pending_certs_processed, 1);
    assert_eq!(report.pending_cert_errors, 0);

    // Verify cert_ready FCM was sent
    let sent = fcm.sent.lock().unwrap();
    assert_eq!(sent.len(), 1);
    assert_eq!(sent[0].1.message_type, "cert_ready");
    assert!(sent[0].2); // high priority

    // Verify pending cert was cleaned up
    assert!(firestore.pending_certs.lock().unwrap().is_empty());
}

#[tokio::test]
async fn pending_cert_skipped_when_retry_after_in_future() {
    let now = SystemTime::now();
    let retry_in_future = now + Duration::from_secs(3600);
    let pending = make_pending_cert(retry_in_future);

    let firestore = Arc::new(MockFirestore::new());
    firestore
        .pending_certs
        .lock()
        .unwrap()
        .insert("future-sub".to_string(), pending);

    let fcm = Arc::new(MockFcm::new());
    let acme = Arc::new(MockAcme::new("cert"));

    let report = run_maintenance_once(
        &test_config(),
        &(firestore.clone() as Arc<dyn rouse_relay::firestore::FirestoreClient>),
        &(fcm.clone() as Arc<dyn rouse_relay::fcm::FcmClient>),
        &(acme as Arc<dyn rouse_relay::acme::AcmeClient>),
        &stub_dns(),
    )
    .await;

    assert_eq!(report.pending_certs_processed, 0);

    // Pending cert should still be there
    assert_eq!(firestore.pending_certs.lock().unwrap().len(), 1);
    assert!(fcm.sent.lock().unwrap().is_empty());
}

#[tokio::test]
async fn rate_limited_acme_stops_pending_cert_processing() {
    let now = SystemTime::now();
    let retry_in_past = now - Duration::from_secs(3600);

    let firestore = Arc::new(MockFirestore::new());
    // Add two pending certs
    {
        let mut certs = firestore.pending_certs.lock().unwrap();
        certs.insert("sub-a".to_string(), make_pending_cert(retry_in_past));
        certs.insert("sub-b".to_string(), make_pending_cert(retry_in_past));
    }

    let fcm = Arc::new(MockFcm::new());
    let acme = Arc::new(MockAcme::rate_limited());

    let report = run_maintenance_once(
        &test_config(),
        &(firestore as Arc<dyn rouse_relay::firestore::FirestoreClient>),
        &(fcm.clone() as Arc<dyn rouse_relay::fcm::FcmClient>),
        &(acme as Arc<dyn rouse_relay::acme::AcmeClient>),
        &stub_dns(),
    )
    .await;

    // Rate limited means 0 processed, and it should stop trying after first rate limit
    assert_eq!(report.pending_certs_processed, 0);
    assert!(fcm.sent.lock().unwrap().is_empty());
}

#[tokio::test]
async fn fcm_failure_counted_as_nudge_error() {
    let now = SystemTime::now();
    let expires_in_3_days = now + Duration::from_secs(3 * 86400);
    let device = make_device(expires_in_3_days);

    let firestore = Arc::new(MockFirestore::new().with_device("fail-device", device));
    let fcm = Arc::new(MockFcm::failing());
    let acme = Arc::new(MockAcme::new("cert"));

    let report = run_maintenance_once(
        &test_config(),
        &(firestore as Arc<dyn rouse_relay::firestore::FirestoreClient>),
        &(fcm as Arc<dyn rouse_relay::fcm::FcmClient>),
        &(acme as Arc<dyn rouse_relay::acme::AcmeClient>),
        &stub_dns(),
    )
    .await;

    assert_eq!(report.nudges_sent, 0);
    assert_eq!(report.nudge_errors, 1);
}

#[tokio::test]
async fn mixed_devices_processed_correctly() {
    let now = SystemTime::now();

    // Device needing nudge (cert expiring in 3 days)
    let nudge_device = make_device(now + Duration::from_secs(3 * 86400));

    // Healthy device (cert expiring in 30 days)
    let healthy_device = make_device(now + Duration::from_secs(30 * 86400));

    // Reclaimable device (expired 200 days ago)
    let old_device = make_device(now - Duration::from_secs(200 * 86400));

    let firestore = Arc::new(
        MockFirestore::new()
            .with_device("nudge-me", nudge_device)
            .with_device("im-fine", healthy_device)
            .with_device("reclaim-me", old_device),
    );
    let fcm = Arc::new(MockFcm::new());
    let acme = Arc::new(MockAcme::new("cert"));

    let report = run_maintenance_once(
        &test_config(),
        &(firestore.clone() as Arc<dyn rouse_relay::firestore::FirestoreClient>),
        &(fcm.clone() as Arc<dyn rouse_relay::fcm::FcmClient>),
        &(acme as Arc<dyn rouse_relay::acme::AcmeClient>),
        &stub_dns(),
    )
    .await;

    assert_eq!(report.nudges_sent, 1);
    assert_eq!(report.subdomains_reclaimed, 1);

    // Verify correct device was deleted
    let devices = firestore.devices.lock().unwrap();
    assert!(!devices.contains_key("reclaim-me"));
    assert!(devices.contains_key("im-fine"));
    assert!(devices.contains_key("nudge-me"));

    // Verify nudge was sent to the right device
    let sent = fcm.sent.lock().unwrap();
    assert_eq!(sent.len(), 1);
    assert_eq!(sent[0].1.message_type, "renew");
}
