//! ACME client abstraction for DNS-01 certificate issuance.
//!
//! Uses a trait so tests can substitute a mock. The real implementation
//! performs DNS-01 challenges via Cloudflare API and fetches certs from
//! Let's Encrypt.
//!
//! This module is split into several files:
//! - [`types`] — RFC 8555 wire types (Directory, Order, Authorization, Challenge).
//! - [`jws`] — transport/signing layer (JWS signing, nonce handling, `acme_post`).
//! - [`account`] — ACME account lifecycle (key load/create, `newAccount`).
//! - [`protocol`] — protocol flow (`newOrder`, polling, challenge response,
//!   finalize, cert download).
//! - [`client`] — [`RealAcmeClient`] struct and [`AcmeClient::issue_certificate`]
//!   orchestration.

use async_trait::async_trait;
use thiserror::Error;

mod account;
mod client;
mod jws;
mod protocol;
mod types;

pub use account::ExternalAccountBinding;
pub use client::RealAcmeClient;

/// Google Trust Services (GTS) production ACME directory URL.
///
/// GTS is the relay's default ACME provider (switched from LE in #213). It
/// requires External Account Binding on `newAccount`; see
/// [`ExternalAccountBinding`] and the `acme.external_account_binding_*_env`
/// config fields.
pub const GOOGLE_TRUST_SERVICES_DIRECTORY: &str = "https://dv.acme-v02.api.pki.goog/directory";

/// Let's Encrypt production directory URL. Kept as a well-known constant
/// so operators can switch back by setting `acme.directory_url` in config;
/// not the default anymore (see #213).
pub const LETS_ENCRYPT_DIRECTORY: &str = "https://acme-v02.api.letsencrypt.org/directory";

/// Let's Encrypt staging directory URL (for testing).
pub const LETS_ENCRYPT_STAGING_DIRECTORY: &str =
    "https://acme-staging-v02.api.letsencrypt.org/directory";

#[derive(Debug, Error)]
pub enum AcmeError {
    #[error("rate limited: retry after {retry_after_secs} seconds")]
    RateLimited { retry_after_secs: u64 },
    #[error("challenge failed: {0}")]
    ChallengeFailed(String),
    #[error("http error: {0}")]
    Http(String),
}

/// A certificate bundle containing the PEM cert chain and optionally the private key.
///
/// When the relay generates the keypair (legacy mode), `private_key_pem` contains
/// the key. When the device provides a CSR with its own public key, `private_key_pem`
/// is `None` because the device already holds the private key.
#[derive(Debug, Clone)]
pub struct CertificateBundle {
    /// PEM-encoded certificate chain (leaf + intermediates).
    pub cert_pem: String,
    /// PEM-encoded PKCS#8 private key for the certificate, if the relay generated it.
    pub private_key_pem: Option<String>,
}

#[async_trait]
pub trait AcmeClient: Send + Sync {
    /// Issue a certificate for the given subdomain via DNS-01 challenge.
    ///
    /// If `csr_der` is `Some`, the provided CSR (containing the device's public key)
    /// is submitted to the ACME server for finalization. The CSR must have the correct
    /// FQDN as its subject. No private key is returned in this case.
    ///
    /// If `csr_der` is `None`, the relay generates a fresh keypair and CSR internally,
    /// and returns the private key in the bundle.
    async fn issue_certificate(
        &self,
        subdomain: &str,
        csr_der: Option<&[u8]>,
    ) -> Result<CertificateBundle, AcmeError>;
}
