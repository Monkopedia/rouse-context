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
    /// Two-word secret prefix for bot rejection (e.g. "brave-falcon").
    /// Clients must connect to `{secret_prefix}.{subdomain}.rousecontext.com`.
    /// Deprecated: use `valid_secrets` for new registrations.
    pub secret_prefix: Option<String>,
    /// Client-provided secret strings for SNI validation.
    /// Each entry is a valid first-label for the SNI hostname (e.g. "brave-health").
    /// The relay does not know or care which integration a secret maps to.
    #[serde(default)]
    pub valid_secrets: Vec<String>,
}

/// A pending certificate record stored in `pending_certs/{subdomain}`.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PendingCert {
    pub fcm_token: String,
    pub csr: String,
    pub blocked_at: SystemTime,
    pub retry_after: SystemTime,
}

/// A short-lived subdomain reservation stored in `subdomain_reservations/{subdomain}`.
///
/// Created by `POST /request-subdomain` and consumed by the subsequent
/// `POST /register` + `POST /register/certs` flow. Expired reservations are
/// swept by the maintenance loop.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SubdomainReservation {
    /// The FQDN this reservation covers (base domain included).
    pub fqdn: String,
    /// Firebase UID that reserved the name.
    pub firebase_uid: String,
    /// Absolute expiry time after which the name returns to the pool.
    pub expires_at: SystemTime,
    /// Base domain under which the FQDN sits — used for the release valve.
    pub base_domain: String,
    /// When the reservation was created.
    pub created_at: SystemTime,
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

    /// Create or update a subdomain reservation.
    async fn put_reservation(
        &self,
        subdomain: &str,
        reservation: &SubdomainReservation,
    ) -> Result<(), FirestoreError>;

    /// Get a subdomain reservation by subdomain.
    async fn get_reservation(
        &self,
        subdomain: &str,
    ) -> Result<SubdomainReservation, FirestoreError>;

    /// Find a (non-expired) reservation for the given Firebase UID, if any.
    async fn find_reservation_by_uid(
        &self,
        firebase_uid: &str,
    ) -> Result<Option<(String, SubdomainReservation)>, FirestoreError>;

    /// Delete a subdomain reservation.
    async fn delete_reservation(&self, subdomain: &str) -> Result<(), FirestoreError>;

    /// List all subdomain reservations.
    async fn list_reservations(
        &self,
    ) -> Result<Vec<(String, SubdomainReservation)>, FirestoreError>;
}
