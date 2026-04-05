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
    config: &MaintenanceConfig,
    firestore: &Arc<dyn FirestoreClient>,
    fcm: &Arc<dyn FcmClient>,
    acme: &Arc<dyn AcmeClient>,
) -> MaintenanceReport {
    let mut report = MaintenanceReport::default();
    let now = SystemTime::now();
    let nudge_threshold = Duration::from_secs(config.cert_expiry_nudge_days * 86400);
    let reclaim_threshold = Duration::from_secs(config.subdomain_reclaim_days * 86400);

    // 1. List devices and process nudges + reclamation
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

                // Subdomain reclamation
                if is_reclaimable(record, now, reclaim_threshold) {
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

                let csr_bytes = match base64_decode(&cert.csr) {
                    Some(bytes) => bytes,
                    None => {
                        warn!(subdomain, "Invalid base64 CSR in pending cert, removing");
                        let _ = firestore.delete_pending_cert(subdomain).await;
                        report.pending_cert_errors += 1;
                        continue;
                    }
                };

                match acme.issue_certificate(subdomain, &csr_bytes).await {
                    Ok(cert_pem) => {
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
                        info!(subdomain, cert_len = cert_pem.len(), "Issued pending cert");
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

    info!(?report, "Maintenance pass completed");

    report
}

/// Decode a base64 string, returning None on failure.
fn base64_decode(input: &str) -> Option<Vec<u8>> {
    use base64::Engine;
    base64::engine::general_purpose::STANDARD.decode(input).ok()
}
