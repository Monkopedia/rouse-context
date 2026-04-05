//! ACME client abstraction for DNS-01 certificate issuance.
//!
//! Uses a trait so tests can substitute a mock. The real implementation
//! performs DNS-01 challenges via Cloudflare API and fetches certs from
//! Let's Encrypt.

use async_trait::async_trait;
use thiserror::Error;

#[derive(Debug, Error)]
pub enum AcmeError {
    #[error("rate limited: retry after {retry_after_secs} seconds")]
    RateLimited { retry_after_secs: u64 },
    #[error("challenge failed: {0}")]
    ChallengeFailed(String),
    #[error("http error: {0}")]
    Http(String),
}

#[async_trait]
pub trait AcmeClient: Send + Sync {
    /// Issue a certificate for the given subdomain via DNS-01 challenge.
    ///
    /// `csr_der` is the raw DER-encoded CSR bytes.
    /// Returns the PEM certificate chain (leaf + intermediates) as a string,
    /// then base64-encoded for the API response.
    async fn issue_certificate(&self, subdomain: &str, csr_der: &[u8])
        -> Result<String, AcmeError>;
}
