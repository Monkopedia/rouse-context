//! Startup CAA (Certification Authority Authorization) verification.
//!
//! Background: RFC 8659 defines the CAA DNS record. When present, CAs MUST
//! refuse to issue a certificate for a domain unless their own identifier
//! appears in one of the `issue` records. An absent CAA record means any CA
//! is permitted (RFC 8659 §3).
//!
//! The relay issues device certificates via ACME at runtime. If the
//! configured ACME provider's identifier is not in the base domain's CAA
//! record set, issuance will silently fail at renewal time — 60-90 days
//! after deploy, typically long after anyone would look for a
//! misconfiguration. See issue #322 for the incident this guards against.
//!
//! This module performs a best-effort DNS CAA lookup at relay startup and
//! emits a high-visibility ERROR log when the configured provider is
//! missing. It intentionally does NOT fail the startup by default: a
//! transient DNS glitch at boot is recoverable, but a crash-loop is not.
//! Operators who want strict enforcement can set
//! `acme.fail_on_caa_mismatch = true` in `relay.toml`.
//!
//! # What we check
//!
//! - Look up CAA records on `base_domain` via the system's public resolver.
//! - If the lookup returns `NoRecordsFound`, treat it as "all CAs allowed"
//!   (per RFC 8659) and pass.
//! - If any other DNS error occurs (SERVFAIL, timeout, ...), log a WARN and
//!   pass — we don't want to brick the relay on a transient DNS issue.
//! - If records exist, verify at least one `issue` record names the
//!   configured ACME provider's CAA identifier. Otherwise, log ERROR.
//!
//! # What we do NOT check
//!
//! - `issuewild`: device subdomains are issued per-device, not as wildcards,
//!   so `issue` is what matters. (The apex wildcard cert is also a plain
//!   `issue`-controlled issuance for `*.{base}.{tld}`, which CAA treats as
//!   `issuewild` — but the real protection for us is the `issue` record
//!   governing per-device hostnames.)
//! - Iodef / contact records: informational only.
//! - Cross-checking against actually-issued certificates: that would be a
//!   post-issuance audit, not a startup check.
//!
//! # References
//!
//! - RFC 8659 — DNS Certification Authority Authorization (CAA) Resource
//!   Record
//! - Issue #322 — the original retro

use async_trait::async_trait;
use hickory_resolver::config::{ResolverConfig, ResolverOpts};
use hickory_resolver::error::{ResolveError, ResolveErrorKind};
use hickory_resolver::proto::rr::RecordType;
use hickory_resolver::TokioAsyncResolver;
use tracing::{error, info, warn};

use crate::acme::{GOOGLE_TRUST_SERVICES_DIRECTORY, LETS_ENCRYPT_DIRECTORY};

/// CAA `issue` identifier for Google Trust Services (GTS). Matches the
/// value CAs use in CAA checks per the CA's published documentation.
pub const GTS_CAA_IDENTIFIER: &str = "pki.goog";

/// CAA `issue` identifier for Let's Encrypt.
pub const LE_CAA_IDENTIFIER: &str = "letsencrypt.org";

/// Outcome of a startup CAA check.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum CaaCheckResult {
    /// The configured ACME provider is authorised by at least one `issue`
    /// CAA record.
    Authorised,
    /// No CAA records at all exist for the base domain. Per RFC 8659 this is
    /// permissive — any CA may issue.
    NoCaaRecords,
    /// CAA records exist but none authorise the configured ACME provider.
    /// The [`String`] is the expected provider identifier; the [`Vec<String>`]
    /// is what was found so operators can diagnose the mismatch from logs.
    Mismatch {
        expected: String,
        found: Vec<String>,
    },
    /// The DNS lookup failed (SERVFAIL, timeout, network error, ...). Treated
    /// as a non-fatal skip — the relay still starts.
    LookupFailed(String),
}

impl CaaCheckResult {
    /// True when the check definitively passed (authorised or no records).
    pub fn is_ok(&self) -> bool {
        matches!(self, Self::Authorised | Self::NoCaaRecords)
    }

    /// True when the check definitively failed (a mismatch — lookup failure
    /// is explicitly NOT a failure here; see module docs).
    pub fn is_mismatch(&self) -> bool {
        matches!(self, Self::Mismatch { .. })
    }
}

/// Abstracts the DNS CAA lookup so the check can be unit-tested without
/// hitting real DNS. Returns `Ok(Vec<...>)` with parsed `issue` identifiers
/// (lowercase, trimmed) when records exist, `Ok(Vec::new())` for a definite
/// "no CAA record" response, and `Err` for any transport/resolver error.
#[async_trait]
pub trait CaaLookup: Send + Sync {
    /// Look up CAA records for `domain`. Implementations MUST treat
    /// "NoRecordsFound" or NXDOMAIN as `Ok(Vec::new())` so the caller can
    /// distinguish "no records" (permissive per RFC 8659) from "lookup
    /// failed" (unknown state).
    async fn lookup_issue_identifiers(&self, domain: &str) -> Result<Vec<String>, String>;
}

/// Real [`CaaLookup`] backed by a public recursive resolver (Google DNS).
///
/// Google is chosen for the same reason [`crate::cloudflare_dns`] uses it
/// for the NS-apex lookup: it's globally reachable, doesn't depend on the
/// VPS's configured resolver (which may be a broken systemd-resolved), and
/// is a fine source of truth for publicly-published CAA records.
pub struct PublicResolverCaaLookup;

#[async_trait]
impl CaaLookup for PublicResolverCaaLookup {
    async fn lookup_issue_identifiers(&self, domain: &str) -> Result<Vec<String>, String> {
        let resolver = TokioAsyncResolver::tokio(ResolverConfig::google(), ResolverOpts::default());
        match resolver.lookup(domain, RecordType::CAA).await {
            Ok(lookup) => {
                let mut ids = Vec::new();
                for record in lookup.record_iter() {
                    if let Some(caa) = record.data().and_then(|rd| rd.as_caa()) {
                        if caa.tag().is_issue() {
                            if let hickory_resolver::proto::rr::rdata::caa::Value::Issuer(
                                Some(name),
                                _,
                            ) = caa.value()
                            {
                                // Strip the trailing dot hickory appends and
                                // lowercase for case-insensitive compare.
                                let id = name.to_ascii().trim_end_matches('.').to_ascii_lowercase();
                                if !id.is_empty() {
                                    ids.push(id);
                                }
                            }
                        }
                    }
                }
                Ok(ids)
            }
            Err(e) => match resolve_error_to_class(&e) {
                ResolveOutcome::NoRecords => Ok(Vec::new()),
                ResolveOutcome::Other => Err(e.to_string()),
            },
        }
    }
}

enum ResolveOutcome {
    NoRecords,
    Other,
}

/// Classify a [`ResolveError`]: a "no records" response (NXDOMAIN or
/// NoRecordsFound) must map to `Ok(empty)` upstream, everything else to
/// `Err`.
fn resolve_error_to_class(e: &ResolveError) -> ResolveOutcome {
    match e.kind() {
        ResolveErrorKind::NoRecordsFound { .. } => ResolveOutcome::NoRecords,
        _ => ResolveOutcome::Other,
    }
}

/// Map an ACME directory URL to its expected CAA `issue` identifier.
///
/// Returns `None` for unknown providers — in that case the check is
/// skipped with an INFO log rather than guessed (a wrong identifier would
/// produce false-positive ERRORs that train operators to ignore them).
pub fn caa_identifier_for_directory(directory_url: &str) -> Option<&'static str> {
    if directory_url.contains("pki.goog") {
        Some(GTS_CAA_IDENTIFIER)
    } else if directory_url.contains("letsencrypt.org") {
        Some(LE_CAA_IDENTIFIER)
    } else {
        None
    }
}

/// Resolve the effective ACME directory URL from config, replicating the
/// same fallback the ACME client uses (empty config = GTS default).
pub fn effective_directory_url(configured: &str) -> &str {
    if configured.is_empty() {
        GOOGLE_TRUST_SERVICES_DIRECTORY
    } else if configured == "letsencrypt" {
        // Historical alias, kept for safety; no-op if never seen in config.
        LETS_ENCRYPT_DIRECTORY
    } else {
        configured
    }
}

/// Run the CAA verification check and log the outcome.
///
/// This is the main entry point called from `main.rs` at startup. It is
/// intentionally infallible — all paths either pass or emit a log and
/// return a result for optional strict-mode enforcement by the caller.
pub async fn verify_caa<L: CaaLookup>(
    lookup: &L,
    base_domain: &str,
    directory_url: &str,
) -> CaaCheckResult {
    let expected = match caa_identifier_for_directory(directory_url) {
        Some(id) => id,
        None => {
            info!(
                directory_url = %directory_url,
                "CAA check skipped: unknown ACME provider, no expected CAA identifier"
            );
            // Treat unknown-provider as "no records" style pass. We can't
            // make a meaningful assertion without knowing the identifier.
            return CaaCheckResult::Authorised;
        }
    };

    match lookup.lookup_issue_identifiers(base_domain).await {
        Ok(ids) if ids.is_empty() => {
            info!(
                base_domain = %base_domain,
                expected_ca = %expected,
                "CAA check passed: no CAA records (any CA is authorised per RFC 8659)"
            );
            CaaCheckResult::NoCaaRecords
        }
        Ok(ids) => {
            // Case-insensitive match per RFC 8659 §4.2. We normalise here
            // (instead of only at the lookup site) so that [`CaaLookup`]
            // implementations — including test fakes — don't have to.
            let expected_lc = expected.to_ascii_lowercase();
            if ids.iter().any(|id| id.to_ascii_lowercase() == expected_lc) {
                info!(
                    base_domain = %base_domain,
                    expected_ca = %expected,
                    found = ?ids,
                    "CAA check passed: configured ACME provider is authorised"
                );
                CaaCheckResult::Authorised
            } else {
                // The *reason* this log is so loud: when it fires, cert
                // renewal at +60-90 days WILL fail silently. The operator
                // needs to see this in `systemctl status` and journalctl
                // immediately so they can add the missing CAA record.
                error!(
                    base_domain = %base_domain,
                    expected_ca = %expected,
                    found = ?ids,
                    "CAA MISMATCH: configured ACME provider is NOT in the CAA issue allowlist. \
                     Cert renewal will fail. Add a `CAA 0 issue \"{}\"` record on {} in your DNS provider. \
                     See docs/self-hosting.md.",
                    expected,
                    base_domain
                );
                CaaCheckResult::Mismatch {
                    expected: expected.to_string(),
                    found: ids,
                }
            }
        }
        Err(e) => {
            warn!(
                base_domain = %base_domain,
                expected_ca = %expected,
                error = %e,
                "CAA check skipped: DNS lookup failed (relay still starting)"
            );
            CaaCheckResult::LookupFailed(e)
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::Mutex;

    /// In-memory [`CaaLookup`] for tests. Configure with the records (or
    /// error) to return, inspect captured queries.
    struct FakeCaaLookup {
        /// Either `Ok(records)` where `records` is the issuer identifiers
        /// each CAA `issue` entry names, or `Err(message)` to simulate a
        /// transport/resolver error (e.g. SERVFAIL, timeout).
        result: Mutex<Result<Vec<String>, String>>,
        /// Every domain passed to [`lookup_issue_identifiers`].
        queries: Mutex<Vec<String>>,
    }

    impl FakeCaaLookup {
        fn with_records(records: Vec<&str>) -> Self {
            Self {
                result: Mutex::new(Ok(records.into_iter().map(String::from).collect())),
                queries: Mutex::new(Vec::new()),
            }
        }

        fn no_records() -> Self {
            Self {
                result: Mutex::new(Ok(Vec::new())),
                queries: Mutex::new(Vec::new()),
            }
        }

        fn lookup_fails(msg: &str) -> Self {
            Self {
                result: Mutex::new(Err(msg.to_string())),
                queries: Mutex::new(Vec::new()),
            }
        }
    }

    #[async_trait]
    impl CaaLookup for FakeCaaLookup {
        async fn lookup_issue_identifiers(&self, domain: &str) -> Result<Vec<String>, String> {
            self.queries.lock().unwrap().push(domain.to_string());
            self.result.lock().unwrap().clone()
        }
    }

    #[tokio::test]
    async fn authorised_when_configured_provider_in_caa_list() {
        let fake = FakeCaaLookup::with_records(vec!["pki.goog", "letsencrypt.org"]);
        let result = verify_caa(&fake, "rousecontext.com", GOOGLE_TRUST_SERVICES_DIRECTORY).await;
        assert_eq!(result, CaaCheckResult::Authorised);
        assert!(result.is_ok());
        assert!(!result.is_mismatch());
    }

    #[tokio::test]
    async fn authorised_with_case_insensitive_match() {
        // CAA matching is case-insensitive by RFC 8659 §4.2.
        let fake = FakeCaaLookup::with_records(vec!["PKI.Goog"]);
        let result = verify_caa(&fake, "rousecontext.com", GOOGLE_TRUST_SERVICES_DIRECTORY).await;
        assert_eq!(result, CaaCheckResult::Authorised);
    }

    #[tokio::test]
    async fn mismatch_when_configured_provider_not_in_caa_list() {
        // This is the exact #322 scenario: CAA only lists LE but relay is
        // configured to use GTS.
        let fake = FakeCaaLookup::with_records(vec!["letsencrypt.org"]);
        let result = verify_caa(&fake, "rousecontext.com", GOOGLE_TRUST_SERVICES_DIRECTORY).await;
        match result {
            CaaCheckResult::Mismatch { expected, found } => {
                assert_eq!(expected, "pki.goog");
                assert_eq!(found, vec!["letsencrypt.org".to_string()]);
            }
            other => panic!("expected Mismatch, got {other:?}"),
        }
    }

    #[tokio::test]
    async fn no_caa_records_is_permissive() {
        // RFC 8659 §3: absence of CAA means any CA is allowed.
        let fake = FakeCaaLookup::no_records();
        let result = verify_caa(&fake, "rousecontext.com", LETS_ENCRYPT_DIRECTORY).await;
        assert_eq!(result, CaaCheckResult::NoCaaRecords);
        assert!(result.is_ok());
    }

    #[tokio::test]
    async fn dns_lookup_failure_is_non_fatal() {
        // We must not brick the relay on transient DNS glitches. A
        // SERVFAIL or network error maps to LookupFailed, which the caller
        // treats as "skip, log, carry on".
        let fake = FakeCaaLookup::lookup_fails("SERVFAIL");
        let result = verify_caa(&fake, "rousecontext.com", GOOGLE_TRUST_SERVICES_DIRECTORY).await;
        match result {
            CaaCheckResult::LookupFailed(ref msg) => {
                assert!(msg.contains("SERVFAIL"), "got: {msg}");
            }
            other => panic!("expected LookupFailed, got {other:?}"),
        }
        // Not a mismatch — caller won't exit even in strict mode.
        assert!(!result.is_mismatch());
        assert!(!result.is_ok());
    }

    #[tokio::test]
    async fn unknown_provider_skips_check() {
        // If the directory URL doesn't match any CA we know the CAA id for,
        // we can't make a meaningful assertion. Log INFO and pass.
        let fake = FakeCaaLookup::with_records(vec!["only-acme.example"]);
        let result = verify_caa(&fake, "rousecontext.com", "https://acme.example/directory").await;
        assert_eq!(result, CaaCheckResult::Authorised);
        // And we didn't even ask the resolver — no expected id to compare.
        assert!(fake.queries.lock().unwrap().is_empty());
    }

    #[tokio::test]
    async fn check_queries_base_domain_exactly() {
        let fake = FakeCaaLookup::with_records(vec!["pki.goog"]);
        let _ = verify_caa(&fake, "rousecontext.com", GOOGLE_TRUST_SERVICES_DIRECTORY).await;
        assert_eq!(
            fake.queries.lock().unwrap().as_slice(),
            &["rousecontext.com".to_string()]
        );
    }

    #[test]
    fn caa_identifier_for_known_gts_directory() {
        assert_eq!(
            caa_identifier_for_directory(GOOGLE_TRUST_SERVICES_DIRECTORY),
            Some("pki.goog")
        );
    }

    #[test]
    fn caa_identifier_for_known_le_directory() {
        assert_eq!(
            caa_identifier_for_directory(LETS_ENCRYPT_DIRECTORY),
            Some("letsencrypt.org")
        );
    }

    #[test]
    fn caa_identifier_for_unknown_directory_is_none() {
        assert_eq!(
            caa_identifier_for_directory("https://acme.example/directory"),
            None
        );
    }

    #[test]
    fn effective_directory_url_defaults_to_gts_when_empty() {
        assert_eq!(effective_directory_url(""), GOOGLE_TRUST_SERVICES_DIRECTORY);
    }

    #[test]
    fn effective_directory_url_passes_through_configured_value() {
        assert_eq!(
            effective_directory_url(LETS_ENCRYPT_DIRECTORY),
            LETS_ENCRYPT_DIRECTORY
        );
    }
}
