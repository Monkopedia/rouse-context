//! DNS-01 challenge provider abstraction.
//!
//! The ACME protocol itself is DNS-provider-agnostic: all it cares about is
//! that a specific TXT record appears at a specific name and is visible to
//! the ACME server's resolver. Concrete providers (Cloudflare, Route 53,
//! deSEC, ...) implement [`DnsChallengeProvider`] and plug into
//! [`crate::acme::RealAcmeClient`].
//!
//! This trait is provider-agnostic on purpose — it lives at the top of the
//! crate instead of inside `acme/` so a future second DNS provider (or a
//! non-ACME use of the same DNS API) can implement it without dragging the
//! `acme` module into its dependency graph.

use async_trait::async_trait;
use thiserror::Error;

/// Opaque handle to a published TXT record, returned by
/// [`DnsChallengeProvider::publish_txt`] and passed back to
/// [`DnsChallengeProvider::delete_txt`] for teardown.
///
/// The inner string is provider-specific (e.g. the Cloudflare record ID);
/// callers should treat it as an opaque identifier.
#[derive(Clone, Debug)]
pub struct TxtHandle(pub String);

#[derive(Debug, Error)]
pub enum DnsError {
    /// HTTP transport or API error.
    #[error("HTTP: {0}")]
    Http(String),
    /// The DNS provider rejected the request (4xx/5xx with body).
    #[error("provider error: {0}")]
    Provider(String),
    /// Waiting for DNS resolvers to see the record exceeded the configured timeout.
    #[error("timeout waiting for DNS propagation")]
    PropagationTimeout,
}

/// Publish and tear down DNS-01 challenge TXT records.
///
/// Implementations MUST be `Send + Sync` so that they can be shared across
/// requests via `Arc<dyn DnsChallengeProvider>`.
#[async_trait]
pub trait DnsChallengeProvider: Send + Sync {
    /// Publish a TXT record at `name` with value `value`.
    ///
    /// Returns an opaque handle that can later be passed to
    /// [`Self::delete_txt`] to remove the record.
    async fn publish_txt(&self, name: &str, value: &str) -> Result<TxtHandle, DnsError>;

    /// Block until a DNS resolver sees the record, or until a provider-
    /// defined timeout elapses. Returns [`DnsError::PropagationTimeout`]
    /// on timeout.
    async fn wait_for_propagation(&self, name: &str, value: &str) -> Result<(), DnsError>;

    /// Delete a previously-published record. Best-effort: implementations
    /// should log errors but not propagate them, because cleanup failures
    /// must not mask the underlying issue-certificate result.
    async fn delete_txt(&self, handle: &TxtHandle);

    /// Remove any stale records left behind at `name` before publishing new
    /// ones. Some providers (Cloudflare) refuse duplicate `(name, content)`
    /// TXT records and others accumulate orphaned records from previous
    /// failed attempts; this method gives the caller a single hook to
    /// clean the slate.
    async fn cleanup_stale(&self, name: &str) -> Result<(), DnsError>;
}
