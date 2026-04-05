//! Firestore client abstraction for device records and pending certs.
//!
//! Uses a trait so tests can substitute a mock implementation without
//! hitting the real Firestore REST API.

use async_trait::async_trait;
use serde::{Deserialize, Serialize};
use std::time::SystemTime;
use thiserror::Error;

#[derive(Debug, Error)]
pub enum FirestoreError {
    #[error("document not found: {0}")]
    NotFound(String),
    #[error("http error: {0}")]
    Http(String),
    #[error("serialization error: {0}")]
    Serialization(String),
}

/// A device record stored in `devices/{subdomain}`.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DeviceRecord {
    pub fcm_token: String,
    pub firebase_uid: String,
    /// Base64-encoded DER SubjectPublicKeyInfo (ECDSA P-256).
    pub public_key: String,
    pub cert_expires: SystemTime,
    pub registered_at: SystemTime,
    /// When the Firebase UID last rotated subdomains. None if never.
    pub last_rotation: Option<SystemTime>,
    /// Set when a renewal nudge FCM is sent. Cleared on successful renewal.
    pub renewal_nudge_sent: Option<SystemTime>,
}

/// A pending certificate record stored in `pending_certs/{subdomain}`.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PendingCert {
    pub fcm_token: String,
    pub csr: String,
    pub blocked_at: SystemTime,
    pub retry_after: SystemTime,
}

#[async_trait]
pub trait FirestoreClient: Send + Sync {
    /// Get a device record by subdomain.
    async fn get_device(&self, subdomain: &str) -> Result<DeviceRecord, FirestoreError>;

    /// Find the subdomain registered to a Firebase UID (if any).
    async fn find_device_by_uid(
        &self,
        firebase_uid: &str,
    ) -> Result<Option<(String, DeviceRecord)>, FirestoreError>;

    /// Create or update a device record.
    async fn put_device(
        &self,
        subdomain: &str,
        record: &DeviceRecord,
    ) -> Result<(), FirestoreError>;

    /// Delete a device record.
    async fn delete_device(&self, subdomain: &str) -> Result<(), FirestoreError>;

    /// Store a pending cert record.
    async fn put_pending_cert(
        &self,
        subdomain: &str,
        pending: &PendingCert,
    ) -> Result<(), FirestoreError>;

    /// Get a pending cert record.
    async fn get_pending_cert(&self, subdomain: &str) -> Result<PendingCert, FirestoreError>;

    /// Delete a pending cert record.
    async fn delete_pending_cert(&self, subdomain: &str) -> Result<(), FirestoreError>;

    /// List all device records. Returns (subdomain, record) pairs.
    async fn list_devices(&self) -> Result<Vec<(String, DeviceRecord)>, FirestoreError>;

    /// List all pending cert records. Returns (subdomain, pending_cert) pairs.
    async fn list_pending_certs(&self) -> Result<Vec<(String, PendingCert)>, FirestoreError>;
}
