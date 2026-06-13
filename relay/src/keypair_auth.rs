//! Keypair-based device authentication (FOSS flavor, issue #462).
//!
//! The `google` flavor authenticates `/register` and the expired-cert `/renew`
//! path with a Firebase ID token. The `foss` flavor has no Firebase; instead it
//! proves possession of its hardware-backed ECDSA P-256 device key by signing a
//! short-lived, replay-bounded **proof token**.
//!
//! ## What is signed
//!
//! The device signs the canonical byte string produced by [`canonical_message`]:
//!
//! ```text
//! rouse-context-keypair-auth:v1\n<purpose>\n<timestamp_secs>\n<nonce>
//! ```
//!
//! - `purpose` is [`PURPOSE_REGISTER`] (`"register"`) or [`PURPOSE_RENEW`]
//!   (`"renew"`). Binding the purpose stops a register proof being replayed as a
//!   renew proof and vice-versa.
//! - `timestamp_secs` is the device's current Unix time (seconds) as decimal
//!   ASCII. The relay rejects a proof whose timestamp is more than
//!   [`REPLAY_WINDOW_SECS`] away from its own clock (in either direction), which
//!   bounds the replay window.
//! - `nonce` is an opaque client-supplied random string (base64 of 16 random
//!   bytes on the device). It makes each proof unique and is echoed back in the
//!   signed bytes so a relay operator can correlate/deduplicate if desired.
//!
//! The signature is a DER-encoded ECDSA-P256 signature over SHA-256 of those
//! bytes (`SHA256withECDSA`), base64-encoded for transport — identical in
//! mechanism to the CSR signature the relay already verifies on every `/renew`.
//!
//! ## Identity
//!
//! A keypair device's stable identity is the **thumbprint** of its public key:
//! base64(SHA-256(SPKI DER)) — see [`thumbprint`]. It plays the role the Firebase
//! UID plays for `google` devices (the Firestore foreign key). A fresh keypair is
//! a fresh identity, matching Firebase-anonymous reinstall semantics: we do not
//! re-link identity across installs.
//!
//! ## Why a bounded timestamp rather than a server nonce
//!
//! `/register` and the expired `/renew` path are stateless, and the relay is
//! memory-constrained. A server-issued single-use nonce would need persistent
//! storage and an extra round-trip. A bounded client timestamp gives a small,
//! fixed replay window with no server state. Crucially the proof is never the
//! *only* gate that mints a certificate: on `/renew` the CSR signature over the
//! freshly-generated CSR is *always* checked too, so a replayed proof alone
//! cannot issue a cert for an attacker-controlled key.

use base64::engine::general_purpose::STANDARD as BASE64;
use base64::Engine;
use p256::ecdsa::signature::Verifier;
use p256::ecdsa::{Signature, VerifyingKey};
use p256::pkcs8::DecodePublicKey;
use sha2::{Digest, Sha256};

/// Proof purpose: initial device registration (`POST /register`).
pub const PURPOSE_REGISTER: &str = "register";

/// Proof purpose: expired-certificate renewal (`POST /renew`, Path B).
pub const PURPOSE_RENEW: &str = "renew";

/// Domain-separation prefix baked into every signed proof. Bumping the version
/// suffix invalidates all older proofs.
pub const PROOF_DOMAIN: &str = "rouse-context-keypair-auth:v1";

/// Maximum allowed skew between the proof's client timestamp and the relay
/// clock, in seconds. A proof outside `[now - WINDOW, now + WINDOW]` is stale
/// and rejected. 5 minutes tolerates ordinary client/relay clock drift while
/// keeping the replay window small.
pub const REPLAY_WINDOW_SECS: i64 = 300;

/// Errors returned when verifying a keypair proof token.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum KeypairAuthError {
    /// The supplied/stored public key could not be decoded as SPKI DER.
    InvalidPublicKey(String),
    /// The signature was not valid base64 or not a valid DER ECDSA signature.
    MalformedSignature(String),
    /// The proof timestamp is outside the accepted replay window.
    StaleTimestamp { skew_secs: i64 },
    /// An unknown / unsupported purpose string was supplied.
    UnknownPurpose(String),
    /// The signature did not verify against the public key.
    SignatureMismatch,
}

impl std::fmt::Display for KeypairAuthError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            KeypairAuthError::InvalidPublicKey(e) => write!(f, "invalid public key: {e}"),
            KeypairAuthError::MalformedSignature(e) => write!(f, "malformed signature: {e}"),
            KeypairAuthError::StaleTimestamp { skew_secs } => {
                write!(f, "stale proof timestamp (skew {skew_secs}s)")
            }
            KeypairAuthError::UnknownPurpose(p) => write!(f, "unknown proof purpose: {p}"),
            KeypairAuthError::SignatureMismatch => write!(f, "signature mismatch"),
        }
    }
}

impl std::error::Error for KeypairAuthError {}

/// Derive the stable device identity thumbprint from a public key.
///
/// The thumbprint is `base64(SHA-256(spki_der))`. The input MUST be the DER
/// encoding of the `SubjectPublicKeyInfo` (the same bytes embedded in a CSR and
/// stored as `DeviceRecord::public_key` after base64-decoding).
pub fn thumbprint(spki_der: &[u8]) -> String {
    let digest = Sha256::digest(spki_der);
    BASE64.encode(digest)
}

/// Build the canonical byte string a device signs for a proof token. The exact
/// byte layout is mirrored by the Android client (`KeypairAuth.kt`); changing it
/// on one side without the other breaks verification.
pub fn canonical_message(purpose: &str, timestamp_secs: i64, nonce: &str) -> Vec<u8> {
    format!("{PROOF_DOMAIN}\n{purpose}\n{timestamp_secs}\n{nonce}").into_bytes()
}

/// Verify a keypair proof token against `public_key_der` (raw SPKI DER bytes).
///
/// Checks, in order: purpose is recognised, the timestamp is within
/// [`REPLAY_WINDOW_SECS`] of `now_secs`, the supplied public key parses, the
/// signature decodes, and finally that the signature verifies over
/// [`canonical_message`]. Returns the verified key's [`thumbprint`] on success.
pub fn verify_proof(
    public_key_der: &[u8],
    purpose: &str,
    timestamp_secs: i64,
    nonce: &str,
    signature_b64: &str,
    now_secs: i64,
) -> Result<String, KeypairAuthError> {
    if purpose != PURPOSE_REGISTER && purpose != PURPOSE_RENEW {
        return Err(KeypairAuthError::UnknownPurpose(purpose.to_string()));
    }

    let skew = now_secs - timestamp_secs;
    if skew.abs() > REPLAY_WINDOW_SECS {
        return Err(KeypairAuthError::StaleTimestamp { skew_secs: skew });
    }

    let verifying_key = VerifyingKey::from_public_key_der(public_key_der)
        .map_err(|e| KeypairAuthError::InvalidPublicKey(e.to_string()))?;

    let sig_bytes = BASE64
        .decode(signature_b64)
        .map_err(|e| KeypairAuthError::MalformedSignature(e.to_string()))?;
    let signature = Signature::from_der(&sig_bytes)
        .map_err(|e| KeypairAuthError::MalformedSignature(e.to_string()))?;

    let message = canonical_message(purpose, timestamp_secs, nonce);
    verifying_key
        .verify(&message, &signature)
        .map_err(|_| KeypairAuthError::SignatureMismatch)?;

    Ok(thumbprint(public_key_der))
}

#[cfg(test)]
mod tests {
    use super::*;
    use p256::ecdsa::{signature::Signer, Signature as EcdsaSig, SigningKey};
    use p256::pkcs8::EncodePublicKey;

    struct Device {
        signing_key: SigningKey,
        spki_der: Vec<u8>,
    }

    fn new_device() -> Device {
        let signing_key = SigningKey::random(&mut rand::thread_rng());
        let spki_der = signing_key
            .verifying_key()
            .to_public_key_der()
            .unwrap()
            .as_bytes()
            .to_vec();
        Device {
            signing_key,
            spki_der,
        }
    }

    fn sign(device: &Device, purpose: &str, ts: i64, nonce: &str) -> String {
        let msg = canonical_message(purpose, ts, nonce);
        let sig: EcdsaSig = device.signing_key.sign(&msg);
        BASE64.encode(sig.to_der().as_bytes())
    }

    #[test]
    fn verify_success_returns_thumbprint() {
        let d = new_device();
        let now = 1_000_000;
        let sig = sign(&d, PURPOSE_REGISTER, now, "nonce-abc");
        let tp = verify_proof(&d.spki_der, PURPOSE_REGISTER, now, "nonce-abc", &sig, now)
            .expect("proof should verify");
        assert_eq!(tp, thumbprint(&d.spki_der));
    }

    #[test]
    fn thumbprint_is_stable_and_key_specific() {
        let d1 = new_device();
        let d2 = new_device();
        assert_eq!(thumbprint(&d1.spki_der), thumbprint(&d1.spki_der));
        assert_ne!(thumbprint(&d1.spki_der), thumbprint(&d2.spki_der));
    }

    #[test]
    fn bad_signature_is_rejected() {
        let d = new_device();
        let now = 1_000_000;
        // Sign for a different nonce than we verify against.
        let sig = sign(&d, PURPOSE_REGISTER, now, "nonce-signed");
        let err =
            verify_proof(&d.spki_der, PURPOSE_REGISTER, now, "nonce-other", &sig, now).unwrap_err();
        assert_eq!(err, KeypairAuthError::SignatureMismatch);
    }

    #[test]
    fn signature_from_other_key_is_rejected() {
        let signer = new_device();
        let claimed = new_device();
        let now = 1_000_000;
        let sig = sign(&signer, PURPOSE_REGISTER, now, "n");
        // Verify the signer's signature against a different (claimed) key.
        let err =
            verify_proof(&claimed.spki_der, PURPOSE_REGISTER, now, "n", &sig, now).unwrap_err();
        assert_eq!(err, KeypairAuthError::SignatureMismatch);
    }

    #[test]
    fn stale_future_timestamp_is_rejected() {
        let d = new_device();
        let ts = 1_000_000;
        let sig = sign(&d, PURPOSE_REGISTER, ts, "n");
        let now = ts - (REPLAY_WINDOW_SECS + 1); // proof is from the future
        let err = verify_proof(&d.spki_der, PURPOSE_REGISTER, ts, "n", &sig, now).unwrap_err();
        assert!(matches!(err, KeypairAuthError::StaleTimestamp { .. }));
    }

    #[test]
    fn stale_past_timestamp_is_rejected() {
        let d = new_device();
        let ts = 1_000_000;
        let sig = sign(&d, PURPOSE_REGISTER, ts, "n");
        let now = ts + (REPLAY_WINDOW_SECS + 1); // proof is too old
        let err = verify_proof(&d.spki_der, PURPOSE_REGISTER, ts, "n", &sig, now).unwrap_err();
        assert!(matches!(err, KeypairAuthError::StaleTimestamp { .. }));
    }

    #[test]
    fn timestamp_at_window_edge_is_accepted() {
        let d = new_device();
        let ts = 1_000_000;
        let sig = sign(&d, PURPOSE_RENEW, ts, "n");
        let now = ts + REPLAY_WINDOW_SECS; // exactly at the edge
        assert!(verify_proof(&d.spki_der, PURPOSE_RENEW, ts, "n", &sig, now).is_ok());
    }

    #[test]
    fn purpose_mismatch_is_rejected() {
        let d = new_device();
        let now = 1_000_000;
        // Signed for register, verified as renew: canonical bytes differ.
        let sig = sign(&d, PURPOSE_REGISTER, now, "n");
        let err = verify_proof(&d.spki_der, PURPOSE_RENEW, now, "n", &sig, now).unwrap_err();
        assert_eq!(err, KeypairAuthError::SignatureMismatch);
    }

    #[test]
    fn unknown_purpose_is_rejected() {
        let d = new_device();
        let now = 1_000_000;
        let sig = sign(&d, PURPOSE_REGISTER, now, "n");
        let err = verify_proof(&d.spki_der, "delete", now, "n", &sig, now).unwrap_err();
        assert!(matches!(err, KeypairAuthError::UnknownPurpose(_)));
    }

    #[test]
    fn malformed_signature_is_rejected() {
        let d = new_device();
        let now = 1_000_000;
        let err = verify_proof(
            &d.spki_der,
            PURPOSE_REGISTER,
            now,
            "n",
            "!!!not-base64!!!",
            now,
        )
        .unwrap_err();
        assert!(matches!(err, KeypairAuthError::MalformedSignature(_)));
    }

    #[test]
    fn invalid_public_key_is_rejected() {
        let d = new_device();
        let now = 1_000_000;
        let sig = sign(&d, PURPOSE_REGISTER, now, "n");
        let err =
            verify_proof(b"not-a-spki-der", PURPOSE_REGISTER, now, "n", &sig, now).unwrap_err();
        assert!(matches!(err, KeypairAuthError::InvalidPublicKey(_)));
    }
}
