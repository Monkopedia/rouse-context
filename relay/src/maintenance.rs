//! Daily maintenance job.
//!
//! Runs once per day (configurable interval for testing). Four tasks:
//!
//! 1. **Cert expiry nudge**: Find devices with certs expiring within 7 days
//!    that haven't been nudged yet. Send FCM `type: "renew"` to each.
//! 2. **Subdomain reclamation**: Delete device records (and DNS) where the cert
//!    has been expired for more than 180 days with no renewal attempts.
//! 3. **Stale device sweep**: Delete device records (and DNS) where
//!    `registered_at` is older than the configured threshold (default 180 days).
//! 4. **Pending cert queue**: If ACME rate limit has reset, process queued
//!    cert requests and notify devices.
//!
//! Each task is independently fallible — a failure in one does not block the others.

use crate::acme::AcmeClient;
use crate::dns::DnsClient;
use crate::fcm::{renew_payload, FcmClient};
use crate::firestore::{DeviceRecord, FirestoreClient};
use crate::shutdown::ShutdownController;
use std::sync::Arc;
use std::time::{Duration, SystemTime};
use tracing::{info, warn};

/// Configuration for the maintenance job.
#[derive(Debug, Clone)]
pub struct MaintenanceConfig {
    /// How often to run the maintenance loop.
    pub interval: Duration,
    /// Cert expiry warning threshold (nudge if expiring within this window).
    pub cert_expiry_nudge_days: u64,
    /// Days after cert expiry before reclaiming the subdomain.
    pub subdomain_reclaim_days: u64,
    /// Days since `registered_at` before a device is considered stale and swept.
    pub stale_device_sweep_days: u64,
    /// Relay hostname for FCM payloads.
    pub relay_hostname: String,
}

impl Default for MaintenanceConfig {
    fn default() -> Self {
        Self {
            interval: Duration::from_secs(86400), // 24 hours
            cert_expiry_nudge_days: 7,
            subdomain_reclaim_days: 180,
            stale_device_sweep_days: 180,
            relay_hostname: "relay.rousecontext.com".to_string(),
        }
    }
}

/// Result of a single maintenance run.
#[derive(Debug, Default)]
pub struct MaintenanceReport {
    pub nudges_sent: u32,
    pub nudge_errors: u32,
    pub pending_certs_processed: u32,
    pub pending_cert_errors: u32,
    pub subdomains_reclaimed: u32,
    pub reclaim_errors: u32,
    pub stale_devices_swept: u32,
    pub stale_device_sweep_errors: u32,
    /// Number of expired subdomain reservations deleted this pass.
    pub reservations_expired: u32,
    pub reservation_sweep_errors: u32,
}

/// Check if a device's cert is expiring soon and hasn't been nudged.
pub fn needs_renewal_nudge(
    record: &DeviceRecord,
    now: SystemTime,
    nudge_threshold: Duration,
) -> bool {
    // Already nudged
    if record.renewal_nudge_sent.is_some() {
        return false;
    }
    // Check if cert expires within the threshold
    match record.cert_expires.duration_since(now) {
        Ok(remaining) => remaining <= nudge_threshold,
        Err(_) => false, // Already expired, don't nudge (reclamation handles this)
    }
}

/// Check if a device's cert has been expired long enough for subdomain reclamation.
pub fn is_reclaimable(record: &DeviceRecord, now: SystemTime, reclaim_threshold: Duration) -> bool {
    match now.duration_since(record.cert_expires) {
        Ok(expired_for) => expired_for >= reclaim_threshold,
        Err(_) => false, // Not yet expired
    }
}

/// Check if a device is stale based on `registered_at` age.
///
/// A device is stale if it was registered more than `stale_threshold` ago.
/// This catches devices that registered long ago and never renewed their cert
/// or otherwise checked in.
pub fn is_stale_device(record: &DeviceRecord, now: SystemTime, stale_threshold: Duration) -> bool {
    match now.duration_since(record.registered_at) {
        Ok(age) => age >= stale_threshold,
        Err(_) => false, // registered_at is in the future (clock skew)
    }
}

/// Run the maintenance loop. This is intended to be spawned as a long-running
/// tokio task. It runs on the configured interval until shutdown.
pub async fn run_maintenance_loop(
    config: MaintenanceConfig,
    firestore: Arc<dyn FirestoreClient>,
    fcm: Arc<dyn FcmClient>,
    acme: Arc<dyn AcmeClient>,
    dns: Arc<dyn DnsClient>,
    shutdown: ShutdownController,
) {
    info!(
        interval_secs = config.interval.as_secs(),
        "Maintenance loop started"
    );

    loop {
        tokio::select! {
            _ = tokio::time::sleep(config.interval) => {
                let report = run_maintenance_once(&config, &firestore, &fcm, &acme, &dns).await;
                info!(?report, "Maintenance run completed");
            }
            _ = shutdown.wait_for_shutdown() => {
                info!("Maintenance loop shutting down");
                return;
            }
        }
    }
}

/// Execute a single maintenance pass. Called by the loop or directly in tests.
pub async fn run_maintenance_once(
    config: &MaintenanceConfig,
    firestore: &Arc<dyn FirestoreClient>,
    fcm: &Arc<dyn FcmClient>,
    acme: &Arc<dyn AcmeClient>,
    dns: &Arc<dyn DnsClient>,
) -> MaintenanceReport {
    let mut report = MaintenanceReport::default();
    let now = SystemTime::now();
    let nudge_threshold = Duration::from_secs(config.cert_expiry_nudge_days * 86400);
    let reclaim_threshold = Duration::from_secs(config.subdomain_reclaim_days * 86400);
    let stale_threshold = Duration::from_secs(config.stale_device_sweep_days * 86400);

    // 1. List devices and process nudges + reclamation + stale sweep
    match firestore.list_devices().await {
        Ok(devices) => {
            for (subdomain, record) in &devices {
                // Cert expiry nudge
                if needs_renewal_nudge(record, now, nudge_threshold) {
                    let payload = renew_payload();
                    match fcm
                        .send_data_message(&record.fcm_token, &payload, false)
                        .await
                    {
                        Ok(()) => {
                            // Mark the device as nudged so we don't re-send
                            let mut updated = record.clone();
                            updated.renewal_nudge_sent = Some(now);
                            if let Err(e) = firestore.put_device(subdomain, &updated).await {
                                warn!(subdomain, error = %e, "Failed to update nudge timestamp");
                            }
                            report.nudges_sent += 1;
                        }
                        Err(e) => {
                            warn!(subdomain, error = %e, "Failed to send renewal nudge");
                            report.nudge_errors += 1;
                        }
                    }
                }

                // Subdomain reclamation (cert expired for too long)
                if is_reclaimable(record, now, reclaim_threshold) {
                    if let Err(e) = dns.delete_subdomain_records(subdomain).await {
                        warn!(subdomain, error = %e, "Failed to delete DNS records during reclamation");
                    }
                    match firestore.delete_device(subdomain).await {
                        Ok(()) => {
                            info!(subdomain, "Reclaimed expired subdomain");
                            report.subdomains_reclaimed += 1;
                        }
                        Err(e) => {
                            warn!(subdomain, error = %e, "Failed to reclaim subdomain");
                            report.reclaim_errors += 1;
                        }
                    }
                    continue;
                }

                // Stale device sweep (registered_at too old)
                if is_stale_device(record, now, stale_threshold) {
                    if let Err(e) = dns.delete_subdomain_records(subdomain).await {
                        warn!(subdomain, error = %e, "Failed to delete DNS records for stale device");
                        report.stale_device_sweep_errors += 1;
                        continue;
                    }
                    match firestore.delete_device(subdomain).await {
                        Ok(()) => {
                            info!(subdomain, "Swept stale device (registered_at too old)");
                            report.stale_devices_swept += 1;
                        }
                        Err(e) => {
                            warn!(subdomain, error = %e, "Failed to delete stale device record");
                            report.stale_device_sweep_errors += 1;
                        }
                    }
                }
            }
        }
        Err(e) => {
            warn!(error = %e, "Failed to list devices for maintenance");
        }
    }

    // 2. Process pending certs if ACME rate limit has reset
    match firestore.list_pending_certs().await {
        Ok(pending) => {
            for (subdomain, cert) in &pending {
                // Only process if retry_after has passed
                if now.duration_since(cert.retry_after).is_err() {
                    // retry_after is still in the future
                    continue;
                }

                match acme.issue_certificate(subdomain, None).await {
                    Ok(bundle) => {
                        // Notify device with cert_ready FCM
                        let payload = crate::fcm::FcmData {
                            message_type: "cert_ready".to_string(),
                        };
                        if let Err(e) = fcm.send_data_message(&cert.fcm_token, &payload, true).await
                        {
                            warn!(subdomain, error = %e, "Failed to notify device of cert");
                        }
                        // Clean up the pending record
                        let _ = firestore.delete_pending_cert(subdomain).await;
                        info!(
                            subdomain,
                            cert_len = bundle.cert_pem.len(),
                            "Issued pending cert"
                        );
                        report.pending_certs_processed += 1;
                    }
                    Err(crate::acme::AcmeError::RateLimited { retry_after_secs }) => {
                        info!(
                            subdomain,
                            retry_after_secs, "ACME still rate limited, stopping cert processing"
                        );
                        // Stop processing further pending certs this cycle
                        break;
                    }
                    Err(e) => {
                        warn!(subdomain, error = %e, "Failed to issue pending cert");
                        report.pending_cert_errors += 1;
                    }
                }
            }
        }
        Err(e) => {
            warn!(error = %e, "Failed to list pending certs for maintenance");
        }
    }

    // 3. Sweep expired subdomain reservations (created by /request-subdomain)
    match firestore.list_reservations().await {
        Ok(reservations) => {
            for (subdomain, reservation) in &reservations {
                if now.duration_since(reservation.expires_at).is_err() {
                    // Still within TTL
                    continue;
                }
                match firestore.delete_reservation(subdomain).await {
                    Ok(()) => {
                        info!(subdomain, "Expired subdomain reservation released");
                        report.reservations_expired += 1;
                    }
                    Err(e) => {
                        warn!(subdomain, error = %e, "Failed to delete expired reservation");
                        report.reservation_sweep_errors += 1;
                    }
                }
            }
        }
        Err(e) => {
            warn!(error = %e, "Failed to list reservations for maintenance");
        }
    }

    info!(?report, "Maintenance pass completed");

    report
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::acme::{AcmeClient, AcmeError, CertificateBundle};
    use crate::dns::{DnsClient, DnsError};
    use crate::fcm::{FcmClient, FcmData, FcmError};
    use crate::firestore::{
        DeviceRecord, FirestoreClient, FirestoreError, PendingCert, SubdomainReservation,
    };
    use std::sync::Mutex;
    use std::time::{Duration, SystemTime};

    // ── Test helpers ──────────────────────────────────────────────────

    fn make_record(registered_days_ago: u64, cert_expires_days_from_now: i64) -> DeviceRecord {
        let now = SystemTime::now();
        let registered_at = now - Duration::from_secs(registered_days_ago * 86400);
        let cert_expires = if cert_expires_days_from_now >= 0 {
            now + Duration::from_secs(cert_expires_days_from_now as u64 * 86400)
        } else {
            now - Duration::from_secs((-cert_expires_days_from_now) as u64 * 86400)
        };
        DeviceRecord {
            fcm_token: "tok".to_string(),
            firebase_uid: "uid".to_string(),
            public_key: "key".to_string(),
            cert_expires,
            registered_at,
            last_rotation: None,
            renewal_nudge_sent: None,
            secret_prefix: None,
            valid_secrets: Vec::new(),
            integration_secrets: std::collections::HashMap::new(),
        }
    }

    struct MockFirestore {
        devices: Mutex<Vec<(String, DeviceRecord)>>,
        deleted_devices: Mutex<Vec<String>>,
        reservations: Mutex<Vec<(String, SubdomainReservation)>>,
        deleted_reservations: Mutex<Vec<String>>,
    }

    impl MockFirestore {
        fn new(devices: Vec<(String, DeviceRecord)>) -> Self {
            Self {
                devices: Mutex::new(devices),
                deleted_devices: Mutex::new(Vec::new()),
                reservations: Mutex::new(Vec::new()),
                deleted_reservations: Mutex::new(Vec::new()),
            }
        }

        fn with_reservations(mut self, reservations: Vec<(String, SubdomainReservation)>) -> Self {
            self.reservations = Mutex::new(reservations);
            self
        }
    }

    #[async_trait::async_trait]
    impl FirestoreClient for MockFirestore {
        async fn get_device(&self, subdomain: &str) -> Result<DeviceRecord, FirestoreError> {
            let devices = self.devices.lock().unwrap();
            devices
                .iter()
                .find(|(s, _)| s == subdomain)
                .map(|(_, r)| r.clone())
                .ok_or_else(|| FirestoreError::NotFound(subdomain.to_string()))
        }
        async fn find_device_by_uid(
            &self,
            _uid: &str,
        ) -> Result<Option<(String, DeviceRecord)>, FirestoreError> {
            Ok(None)
        }
        async fn put_device(
            &self,
            _subdomain: &str,
            _record: &DeviceRecord,
        ) -> Result<(), FirestoreError> {
            Ok(())
        }
        async fn delete_device(&self, subdomain: &str) -> Result<(), FirestoreError> {
            self.deleted_devices
                .lock()
                .unwrap()
                .push(subdomain.to_string());
            Ok(())
        }
        async fn put_pending_cert(&self, _s: &str, _p: &PendingCert) -> Result<(), FirestoreError> {
            Ok(())
        }
        async fn get_pending_cert(&self, s: &str) -> Result<PendingCert, FirestoreError> {
            Err(FirestoreError::NotFound(s.to_string()))
        }
        async fn delete_pending_cert(&self, _s: &str) -> Result<(), FirestoreError> {
            Ok(())
        }
        async fn list_devices(&self) -> Result<Vec<(String, DeviceRecord)>, FirestoreError> {
            Ok(self.devices.lock().unwrap().clone())
        }
        async fn list_pending_certs(&self) -> Result<Vec<(String, PendingCert)>, FirestoreError> {
            Ok(Vec::new())
        }
        async fn put_reservation(
            &self,
            subdomain: &str,
            reservation: &SubdomainReservation,
        ) -> Result<(), FirestoreError> {
            self.reservations
                .lock()
                .unwrap()
                .push((subdomain.to_string(), reservation.clone()));
            Ok(())
        }
        async fn get_reservation(
            &self,
            subdomain: &str,
        ) -> Result<SubdomainReservation, FirestoreError> {
            let reservations = self.reservations.lock().unwrap();
            reservations
                .iter()
                .find(|(s, _)| s == subdomain)
                .map(|(_, r)| r.clone())
                .ok_or_else(|| FirestoreError::NotFound(subdomain.to_string()))
        }
        async fn find_reservation_by_uid(
            &self,
            _uid: &str,
        ) -> Result<Option<(String, SubdomainReservation)>, FirestoreError> {
            Ok(None)
        }
        async fn delete_reservation(&self, subdomain: &str) -> Result<(), FirestoreError> {
            self.deleted_reservations
                .lock()
                .unwrap()
                .push(subdomain.to_string());
            self.reservations
                .lock()
                .unwrap()
                .retain(|(s, _)| s != subdomain);
            Ok(())
        }
        async fn list_reservations(
            &self,
        ) -> Result<Vec<(String, SubdomainReservation)>, FirestoreError> {
            Ok(self.reservations.lock().unwrap().clone())
        }
    }

    struct MockDns {
        deleted_subdomains: Mutex<Vec<String>>,
        should_fail: bool,
    }

    impl MockDns {
        fn new() -> Self {
            Self {
                deleted_subdomains: Mutex::new(Vec::new()),
                should_fail: false,
            }
        }

        fn failing() -> Self {
            Self {
                deleted_subdomains: Mutex::new(Vec::new()),
                should_fail: true,
            }
        }
    }

    #[async_trait::async_trait]
    impl DnsClient for MockDns {
        async fn delete_subdomain_records(&self, subdomain: &str) -> Result<(), DnsError> {
            if self.should_fail {
                return Err(DnsError::Http("mock DNS failure".to_string()));
            }
            self.deleted_subdomains
                .lock()
                .unwrap()
                .push(subdomain.to_string());
            Ok(())
        }
    }

    struct StubFcm;
    #[async_trait::async_trait]
    impl FcmClient for StubFcm {
        async fn send_data_message(
            &self,
            _t: &str,
            _d: &FcmData,
            _h: bool,
        ) -> Result<(), FcmError> {
            Ok(())
        }
    }

    struct StubAcme;
    #[async_trait::async_trait]
    impl AcmeClient for StubAcme {
        async fn issue_certificate(
            &self,
            _s: &str,
            _csr_der: Option<&[u8]>,
        ) -> Result<CertificateBundle, AcmeError> {
            Ok(CertificateBundle {
                cert_pem: String::new(),
                private_key_pem: None,
            })
        }
    }

    fn test_config(stale_days: u64) -> MaintenanceConfig {
        MaintenanceConfig {
            interval: Duration::from_secs(1),
            cert_expiry_nudge_days: 7,
            subdomain_reclaim_days: 180,
            stale_device_sweep_days: stale_days,
            relay_hostname: "relay.test".to_string(),
        }
    }

    // ── Unit tests for is_stale_device ────────────────────────────────

    #[test]
    fn stale_device_detected_when_registered_at_exceeds_threshold() {
        // Registered 200 days ago, threshold is 180 days
        let record = make_record(200, 30);
        let now = SystemTime::now();
        let threshold = Duration::from_secs(180 * 86400);
        assert!(is_stale_device(&record, now, threshold));
    }

    #[test]
    fn fresh_device_not_stale() {
        // Registered 10 days ago, threshold is 180 days
        let record = make_record(10, 30);
        let now = SystemTime::now();
        let threshold = Duration::from_secs(180 * 86400);
        assert!(!is_stale_device(&record, now, threshold));
    }

    #[test]
    fn device_exactly_at_threshold_is_stale() {
        // Registered exactly 180 days ago
        let record = make_record(180, 30);
        let now = SystemTime::now();
        let threshold = Duration::from_secs(180 * 86400);
        assert!(is_stale_device(&record, now, threshold));
    }

    #[test]
    fn device_with_future_registered_at_is_not_stale() {
        let now = SystemTime::now();
        let future_time = now + Duration::from_secs(86400);
        let record = DeviceRecord {
            fcm_token: "tok".to_string(),
            firebase_uid: "uid".to_string(),
            public_key: "key".to_string(),
            cert_expires: now + Duration::from_secs(86400 * 90),
            registered_at: future_time,
            last_rotation: None,
            renewal_nudge_sent: None,
            secret_prefix: None,
            valid_secrets: Vec::new(),
            integration_secrets: std::collections::HashMap::new(),
        };
        let threshold = Duration::from_secs(180 * 86400);
        assert!(!is_stale_device(&record, now, threshold));
    }

    // ── Integration tests for run_maintenance_once ────────────────────

    #[tokio::test]
    async fn stale_device_is_swept_with_dns_and_firestore_deletion() {
        // Device registered 200 days ago, cert still valid (not reclaimable)
        let devices = vec![("stale-sub".to_string(), make_record(200, 30))];
        let firestore = Arc::new(MockFirestore::new(devices));
        let dns = Arc::new(MockDns::new());
        let fcm: Arc<dyn FcmClient> = Arc::new(StubFcm);
        let acme: Arc<dyn AcmeClient> = Arc::new(StubAcme);
        let config = test_config(180);

        let report = run_maintenance_once(
            &config,
            &(firestore.clone() as Arc<dyn FirestoreClient>),
            &fcm,
            &acme,
            &(dns.clone() as Arc<dyn DnsClient>),
        )
        .await;

        assert_eq!(report.stale_devices_swept, 1);
        assert_eq!(report.stale_device_sweep_errors, 0);
        assert_eq!(
            dns.deleted_subdomains.lock().unwrap().as_slice(),
            &["stale-sub"]
        );
        assert_eq!(
            firestore.deleted_devices.lock().unwrap().as_slice(),
            &["stale-sub"]
        );
    }

    #[tokio::test]
    async fn fresh_device_is_not_swept() {
        // Device registered 10 days ago
        let devices = vec![("fresh-sub".to_string(), make_record(10, 30))];
        let firestore = Arc::new(MockFirestore::new(devices));
        let dns = Arc::new(MockDns::new());
        let fcm: Arc<dyn FcmClient> = Arc::new(StubFcm);
        let acme: Arc<dyn AcmeClient> = Arc::new(StubAcme);
        let config = test_config(180);

        let report = run_maintenance_once(
            &config,
            &(firestore.clone() as Arc<dyn FirestoreClient>),
            &fcm,
            &acme,
            &(dns.clone() as Arc<dyn DnsClient>),
        )
        .await;

        assert_eq!(report.stale_devices_swept, 0);
        assert!(dns.deleted_subdomains.lock().unwrap().is_empty());
        assert!(firestore.deleted_devices.lock().unwrap().is_empty());
    }

    #[tokio::test]
    async fn dns_failure_prevents_firestore_deletion_and_counts_error() {
        let devices = vec![("stale-sub".to_string(), make_record(200, 30))];
        let firestore = Arc::new(MockFirestore::new(devices));
        let dns = Arc::new(MockDns::failing());
        let fcm: Arc<dyn FcmClient> = Arc::new(StubFcm);
        let acme: Arc<dyn AcmeClient> = Arc::new(StubAcme);
        let config = test_config(180);

        let report = run_maintenance_once(
            &config,
            &(firestore.clone() as Arc<dyn FirestoreClient>),
            &fcm,
            &acme,
            &(dns.clone() as Arc<dyn DnsClient>),
        )
        .await;

        assert_eq!(report.stale_devices_swept, 0);
        assert_eq!(report.stale_device_sweep_errors, 1);
        // Firestore record should NOT be deleted if DNS cleanup failed
        assert!(firestore.deleted_devices.lock().unwrap().is_empty());
    }

    #[tokio::test]
    async fn reclaimable_device_also_gets_dns_deleted() {
        // Device with cert expired 200 days ago (reclaimable) AND registered 300 days ago (stale).
        // Reclamation should fire first and prevent double-processing via `continue`.
        let devices = vec![("old-sub".to_string(), make_record(300, -200))];
        let firestore = Arc::new(MockFirestore::new(devices));
        let dns = Arc::new(MockDns::new());
        let fcm: Arc<dyn FcmClient> = Arc::new(StubFcm);
        let acme: Arc<dyn AcmeClient> = Arc::new(StubAcme);
        let config = test_config(180);

        let report = run_maintenance_once(
            &config,
            &(firestore.clone() as Arc<dyn FirestoreClient>),
            &fcm,
            &acme,
            &(dns.clone() as Arc<dyn DnsClient>),
        )
        .await;

        // Should be reclaimed, not swept (reclamation takes priority)
        assert_eq!(report.subdomains_reclaimed, 1);
        assert_eq!(report.stale_devices_swept, 0);
        // DNS should still be deleted as part of reclamation
        assert_eq!(
            dns.deleted_subdomains.lock().unwrap().as_slice(),
            &["old-sub"]
        );
    }

    #[tokio::test]
    async fn mixed_fresh_and_stale_devices() {
        let devices = vec![
            ("fresh-one".to_string(), make_record(10, 80)),
            ("stale-one".to_string(), make_record(200, 30)),
            ("fresh-two".to_string(), make_record(90, 60)),
            ("stale-two".to_string(), make_record(365, 30)),
        ];
        let firestore = Arc::new(MockFirestore::new(devices));
        let dns = Arc::new(MockDns::new());
        let fcm: Arc<dyn FcmClient> = Arc::new(StubFcm);
        let acme: Arc<dyn AcmeClient> = Arc::new(StubAcme);
        let config = test_config(180);

        let report = run_maintenance_once(
            &config,
            &(firestore.clone() as Arc<dyn FirestoreClient>),
            &fcm,
            &acme,
            &(dns.clone() as Arc<dyn DnsClient>),
        )
        .await;

        assert_eq!(report.stale_devices_swept, 2);
        let deleted = firestore.deleted_devices.lock().unwrap();
        assert!(deleted.contains(&"stale-one".to_string()));
        assert!(deleted.contains(&"stale-two".to_string()));
        assert!(!deleted.contains(&"fresh-one".to_string()));
        assert!(!deleted.contains(&"fresh-two".to_string()));
    }

    #[tokio::test]
    async fn expired_reservation_is_swept() {
        let now = SystemTime::now();
        let past = now - Duration::from_secs(60);
        let future = now + Duration::from_secs(600);
        let expired = SubdomainReservation {
            fqdn: "ember.rousecontext.com".to_string(),
            firebase_uid: "uid-1".to_string(),
            expires_at: past,
            base_domain: "rousecontext.com".to_string(),
            created_at: now - Duration::from_secs(3600),
        };
        let fresh = SubdomainReservation {
            fqdn: "sable.rousecontext.com".to_string(),
            firebase_uid: "uid-2".to_string(),
            expires_at: future,
            base_domain: "rousecontext.com".to_string(),
            created_at: now,
        };
        let firestore = Arc::new(MockFirestore::new(Vec::new()).with_reservations(vec![
            ("ember".to_string(), expired),
            ("sable".to_string(), fresh),
        ]));
        let dns = Arc::new(MockDns::new());
        let fcm: Arc<dyn FcmClient> = Arc::new(StubFcm);
        let acme: Arc<dyn AcmeClient> = Arc::new(StubAcme);
        let config = test_config(180);

        let report = run_maintenance_once(
            &config,
            &(firestore.clone() as Arc<dyn FirestoreClient>),
            &fcm,
            &acme,
            &(dns.clone() as Arc<dyn DnsClient>),
        )
        .await;

        assert_eq!(report.reservations_expired, 1);
        assert_eq!(report.reservation_sweep_errors, 0);
        let deleted = firestore.deleted_reservations.lock().unwrap();
        assert_eq!(deleted.as_slice(), &["ember"]);
    }

    #[tokio::test]
    async fn configurable_threshold_is_respected() {
        // Registered 30 days ago, threshold set to 20 days (should be swept)
        let devices = vec![("recent-sub".to_string(), make_record(30, 60))];
        let firestore = Arc::new(MockFirestore::new(devices));
        let dns = Arc::new(MockDns::new());
        let fcm: Arc<dyn FcmClient> = Arc::new(StubFcm);
        let acme: Arc<dyn AcmeClient> = Arc::new(StubAcme);
        let config = test_config(20); // 20 day threshold

        let report = run_maintenance_once(
            &config,
            &(firestore.clone() as Arc<dyn FirestoreClient>),
            &fcm,
            &acme,
            &(dns.clone() as Arc<dyn DnsClient>),
        )
        .await;

        assert_eq!(report.stale_devices_swept, 1);
    }
}
