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
    /// A create-only write found the document already present. Returned by
    /// [`FirestoreClient::create_device`]; the existing document is untouched.
    #[error("document already exists: {0}")]
    AlreadyExists(String),
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

/// Whether a device record has had its public key bound yet.
///
/// Only a record created by round-1 `/register` for a brand-new device starts
/// as [`KeyBinding::AwaitingFirstKey`]; round-2 `/register/certs` moves it to
/// [`KeyBinding::Bound`] and nothing moves it back. While awaiting, the first
/// `/register/certs` may bind a key without a signature. Once bound, every
/// later issuance must be signed by the bound key (or go through `/renew`).
///
/// The state is explicit rather than inferred from an empty `public_key`, so
/// that no write that happens to leave `public_key` empty can make a bound
/// record accept an unsigned key again. Stored documents written before this
/// field existed are read as bound when they carry a key (see
/// [`KeyBinding::infer_legacy`]).
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum KeyBinding {
    AwaitingFirstKey,
    Bound,
}

impl KeyBinding {
    /// Wire string used in Firestore documents.
    pub fn as_wire(&self) -> &'static str {
        match self {
            KeyBinding::AwaitingFirstKey => "awaiting_first_key",
            KeyBinding::Bound => "bound",
        }
    }

    /// Parse a stored value. A missing field is resolved by
    /// [`Self::infer_legacy`]; an unrecognised value reads as
    /// [`KeyBinding::Bound`] so it can only ever demand a signature.
    pub fn from_stored(value: Option<&str>, public_key: &str) -> KeyBinding {
        match value {
            Some("awaiting_first_key") => KeyBinding::AwaitingFirstKey,
            Some(_) => KeyBinding::Bound,
            None => Self::infer_legacy(public_key),
        }
    }

    /// State of a document written before this field existed: a stored key
    /// means bound; no key means the device never completed round 2.
    pub fn infer_legacy(public_key: &str) -> KeyBinding {
        if public_key.is_empty() {
            KeyBinding::AwaitingFirstKey
        } else {
            KeyBinding::Bound
        }
    }

    /// Serde default for the field: fail closed.
    fn serde_default() -> KeyBinding {
        KeyBinding::Bound
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
    /// Whether [`Self::public_key`] has been bound. Required on every
    /// construction so each writer states it explicitly.
    #[serde(default = "KeyBinding::serde_default")]
    pub key_binding: KeyBinding,
    pub cert_expires: SystemTime,
    pub registered_at: SystemTime,
    /// When the device last checked in (a `/ws` mux session or a `/renew`).
    /// Written only by [`FirestoreClient::set_device_last_seen`]. `None` for
    /// records that predate the field or have not checked in since; those
    /// fall back to [`Self::registered_at`] (see [`Self::last_active`]).
    #[serde(default)]
    pub last_seen: Option<SystemTime>,
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

impl DeviceRecord {
    /// True only for a record that has never had a key bound. Both the
    /// explicit state and the absence of a stored key must agree, so neither
    /// a stray `AwaitingFirstKey` nor a blanked `public_key` alone is enough
    /// to skip proof of possession.
    pub fn awaits_first_key(&self) -> bool {
        self.key_binding == KeyBinding::AwaitingFirstKey && self.public_key.is_empty()
    }

    /// The most recent sign of life: the later of [`Self::registered_at`] and
    /// [`Self::last_seen`].
    pub fn last_active(&self) -> SystemTime {
        self.last_seen
            .map_or(self.registered_at, |seen| seen.max(self.registered_at))
    }
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

    /// Create a device record only if no record exists for `subdomain`.
    ///
    /// The existence check and the write are a single atomic operation in the
    /// backing store, so of several concurrent creates for the same subdomain
    /// exactly one succeeds. All others get [`FirestoreError::AlreadyExists`]
    /// and the stored record is left unchanged.
    async fn create_device(
        &self,
        subdomain: &str,
        record: &DeviceRecord,
    ) -> Result<(), FirestoreError>;

    /// Create or update a device record.
    async fn put_device(
        &self,
        subdomain: &str,
        record: &DeviceRecord,
    ) -> Result<(), FirestoreError>;

    /// Set only the `last_seen` field of an existing device record, leaving
    /// every other field as stored. Never creates a record: a missing device
    /// yields [`FirestoreError::NotFound`].
    async fn set_device_last_seen(
        &self,
        subdomain: &str,
        last_seen: SystemTime,
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
