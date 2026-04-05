//! Daily maintenance job.
//!
//! Runs once per day (configurable interval for testing). Three tasks:
//!
//! 1. **Cert expiry nudge**: Find devices with certs expiring within 7 days
//!    that haven't been nudged yet. Send FCM `type: "renew"` to each.
//! 2. **Pending cert queue**: If ACME rate limit has reset, process queued
//!    cert requests and notify devices.
//! 3. **Subdomain reclamation**: Delete device records where the cert has been
//!    expired for more than 180 days with no renewal attempts.
//!
//! Each task is independently fallible — a failure in one does not block the others.

use crate::acme::AcmeClient;
use crate::fcm::FcmClient;
use crate::firestore::{DeviceRecord, FirestoreClient};
use crate::shutdown::ShutdownController;
use std::sync::Arc;
use std::time::{Duration, SystemTime};
use tracing::info;

/// Configuration for the maintenance job.
#[derive(Debug, Clone)]
pub struct MaintenanceConfig {
    /// How often to run the maintenance loop.
    pub interval: Duration,
    /// Cert expiry warning threshold (nudge if expiring within this window).
    pub cert_expiry_nudge_days: u64,
    /// Days after cert expiry before reclaiming the subdomain.
    pub subdomain_reclaim_days: u64,
    /// Relay hostname for FCM payloads.
    pub relay_hostname: String,
}

impl Default for MaintenanceConfig {
    fn default() -> Self {
        Self {
            interval: Duration::from_secs(86400), // 24 hours
            cert_expiry_nudge_days: 7,
            subdomain_reclaim_days: 180,
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

/// Run the maintenance loop. This is intended to be spawned as a long-running
/// tokio task. It runs on the configured interval until shutdown.
pub async fn run_maintenance_loop(
    config: MaintenanceConfig,
    firestore: Arc<dyn FirestoreClient>,
    fcm: Arc<dyn FcmClient>,
    acme: Arc<dyn AcmeClient>,
    shutdown: ShutdownController,
) {
    info!(
        interval_secs = config.interval.as_secs(),
        "Maintenance loop started"
    );

    loop {
        tokio::select! {
            _ = tokio::time::sleep(config.interval) => {
                let report = run_maintenance_once(&config, &firestore, &fcm, &acme).await;
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
    _config: &MaintenanceConfig,
    _firestore: &Arc<dyn FirestoreClient>,
    _fcm: &Arc<dyn FcmClient>,
    _acme: &Arc<dyn AcmeClient>,
) -> MaintenanceReport {
    let report = MaintenanceReport::default();

    // Note: Full implementation requires Firestore list/query operations
    // which are not yet in the FirestoreClient trait. The maintenance logic
    // (nudge check, reclaim check) is implemented and tested via unit tests
    // on the predicate functions above. The loop infrastructure and shutdown
    // integration are wired and tested.
    //
    // When Firestore list operations are added, this function will:
    // 1. List all devices, filter by needs_renewal_nudge(), send FCM renew
    // 2. List pending_certs, check ACME quota, issue certs
    // 3. List all devices, filter by is_reclaimable(), delete records

    info!("Maintenance pass completed (list operations pending Firestore trait extension)");

    report
}
