//! Firestore client abstraction for device records and pending certs.
//!
//! Uses a trait so tests can substitute a mock implementation without
//! hitting the real Firestore REST API.

use async_trait::async_trait;
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
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

/// Which push transport the relay uses to wake a device.
///
/// `google`-flavor devices use FCM (the wake target is
/// [`DeviceRecord::fcm_token`]); `foss`-flavor devices use UnifiedPush (the
/// target is [`DeviceRecord::push_endpoint`], an HTTP POST URL). Defaults to
/// [`PushKind::Fcm`] so Firestore documents written before this field existed
/// keep waking via FCM. See issue #463.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum PushKind {
    #[default]
    Fcm,
    UnifiedPush,
}

impl PushKind {
    /// Wire string used in Firestore documents and the inbound WS control
    /// message (`"fcm"` / `"unifiedpush"`).
    pub fn as_wire(&self) -> &'static str {
        match self {
            PushKind::Fcm => "fcm",
            PushKind::UnifiedPush => "unifiedpush",
        }
    }

    /// Parse the wire string. Returns `None` for unrecognised values.
    pub fn from_wire(s: &str) -> Option<PushKind> {
        match s {
            "fcm" => Some(PushKind::Fcm),
            "unifiedpush" => Some(PushKind::UnifiedPush),
            _ => None,
        }
    }
}

/// A device record stored in `devices/{subdomain}`.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DeviceRecord {
    pub fcm_token: String,
    /// Firebase UID — the Firestore foreign key for `google`-flavor devices.
    /// Empty for keypair (`foss`-flavor) devices, which are keyed on
    /// [`Self::key_thumbprint`] instead. See issue #462.
    pub firebase_uid: String,
    /// Stable identity for keypair (`foss`-flavor) devices:
    /// `base64(SHA-256(public-key SPKI DER))`. `None` for legacy/`google`
    /// devices, which are keyed on [`Self::firebase_uid`]. See issue #462.
    #[serde(default)]
    pub key_thumbprint: Option<String>,
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
    /// Which push transport wakes this device (FCM vs UnifiedPush). Defaults to
    /// [`PushKind::Fcm`] for legacy/`google` records. See issue #463.
    #[serde(default)]
    pub push_kind: PushKind,
    /// UnifiedPush endpoint URL the relay POSTs wake/renew to (`foss` flavor).
    /// Empty for FCM devices, which use [`Self::fcm_token`]. See issue #463.
    #[serde(default)]
    pub push_endpoint: String,
    /// Flat list of secret strings used by the SNI fast path for membership
    /// checks. Each entry is a valid first-label for the SNI hostname
    /// (e.g. "brave-health"). Authoritative for SNI routing; derived from
    /// [`Self::integration_secrets`] on writes that populate both.
    #[serde(default)]
    pub valid_secrets: Vec<String>,
    /// Mapping of integration id → its generated secret. Lets the relay
    /// implement merge-missing semantics on rotate-secret so unrelated
    /// integrations keep their existing secrets (and their in-flight MCP
    /// sessions) when a new integration is added.
    ///
    /// Defaults to an empty map for legacy Firestore documents written before
    /// this field existed.
    #[serde(default)]
    pub integration_secrets: HashMap<String, String>,
    /// Last-known secret for each integration that has been DROPPED from
    /// [`Self::integration_secrets`] (e.g. disabled on-device). Replace-
    /// wholesale rotation removes the live secret, but the value is retained
    /// here so that when the integration is RE-ENABLED the relay can mint a
    /// fresh secret guaranteed not to collide with the one it had before. This
    /// closes #519: a leaked-then-disabled per-integration URL must stay dead,
    /// and ~1/N re-enables would otherwise redraw the identical secret.
    ///
    /// Keyed by integration id; only holds entries that are currently dropped.
    /// An entry is cleared once that integration is re-minted. Defaults to an
    /// empty map for legacy Firestore documents written before this field
    /// existed.
    #[serde(default)]
    pub retired_secrets: HashMap<String, String>,
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

    /// Find the subdomain registered to a public-key thumbprint (if any).
    /// The keypair-auth (`foss`) analogue of [`Self::find_device_by_uid`].
    /// See issue #462.
    async fn find_device_by_thumbprint(
        &self,
        key_thumbprint: &str,
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
