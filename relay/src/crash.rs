//! Crash-report ingestion for `foss`-flavor devices (issue #464).
//!
//! `foss` builds report crashes via ACRA's `HttpSender`, which POSTs a JSON
//! object keyed by ACRA `ReportField` names to `POST /crash`. Crashlytics has
//! no server-side ingestion API, so both flavors converge on GitHub issues:
//! the relay turns an ACRA report into a sanitized, deduped issue labeled
//! `crash`, mirroring the existing Crashlytics→issue automation.
//!
//! Pipeline (see [`CrashService::process`]):
//!   1. **sanitize** — allowlist a handful of non-identifying fields and redact
//!      emails / IPs / on-device paths / long tokens from the stack trace.
//!   2. **dedup** — fingerprint the stack trace; the same fingerprint appends a
//!      comment to its existing issue instead of opening a new one.
//!   3. **spike-cap** — cap reports-per-fingerprint per time window so one
//!      crash loop can't append forever; a global cap bounds *new* issues per
//!      window so fingerprint-varying abuse can't open unlimited issues.
//!   4. **file** — create/append a GitHub issue, if a token is configured.
//!
//! ## Security / abuse surface
//!
//! `/crash` accepts unauthenticated external POSTs and (indirectly) files
//! GitHub issues, so it is an abuse vector (cf. the #472 SSRF lesson). It is
//! protected WITHOUT requiring device auth — ACRA's `HttpSender` cannot present
//! the device mTLS client cert, and crashes must be reportable before a device
//! is even provisioned. Instead the endpoint layers:
//!   * a **per-IP token-bucket rate limit** (rejects raw flooding from one
//!     source with 429),
//!   * the **per-fingerprint spike-cap** (one crash loop ⇒ at most N
//!     touches/window), and
//!   * a **global new-issue cap** (the real backstop: even an attacker rotating
//!     source IPs and fingerprints cannot create more than N issues/window).
//!
//! All three are in-memory and reset on restart; the global cap means the
//! worst case after a restart is a bounded re-burst, not unbounded filing.

use crate::rate_limit::{RateLimitConfig, RateLimiter};
use async_trait::async_trait;
use serde_json::Value;
use sha2::{Digest, Sha256};
use std::collections::HashMap;
use std::sync::{Arc, Mutex, OnceLock};
use std::time::{Duration, Instant};
use thiserror::Error;
use tracing::{info, warn};

/// ACRA `ReportField` names retained in the issue. Dropping everything else is
/// the core of sanitization: identifying fields (`INSTALLATION_ID`,
/// `DEVICE_ID`, `USER_EMAIL`, `USER_IP`, `LOGCAT`, `SHARED_PREFERENCES`, …) are
/// never echoed into a public GitHub issue.
const ALLOWLISTED_FIELDS: &[(&str, &str)] = &[
    ("APP_VERSION_NAME", "App version"),
    ("APP_VERSION_CODE", "App version code"),
    ("ANDROID_VERSION", "Android version"),
    ("PHONE_MODEL", "Device model"),
    ("BRAND", "Device brand"),
    ("PACKAGE_NAME", "Package"),
];

/// Maximum stack-trace characters embedded in an issue body. Bounds memory and
/// issue size if a device sends a pathologically long trace.
const MAX_STACK_TRACE_CHARS: usize = 16_000;

// ---------------------------------------------------------------------------
// Sanitization
// ---------------------------------------------------------------------------

fn email_re() -> &'static regex::Regex {
    static RE: OnceLock<regex::Regex> = OnceLock::new();
    RE.get_or_init(|| {
        regex::Regex::new(r"[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}").unwrap()
    })
}

fn ipv4_re() -> &'static regex::Regex {
    static RE: OnceLock<regex::Regex> = OnceLock::new();
    RE.get_or_init(|| regex::Regex::new(r"\b\d{1,3}(?:\.\d{1,3}){3}\b").unwrap())
}

/// On-device storage paths whose later components can embed a user/profile
/// identifier. We keep the well-known prefix (useful signal) and redact the
/// trailing path so an account name in e.g. `/storage/emulated/0/Android/...`
/// never lands in a public issue.
fn user_path_re() -> &'static regex::Regex {
    static RE: OnceLock<regex::Regex> = OnceLock::new();
    RE.get_or_init(|| {
        regex::Regex::new(
            r"(/data/user/\d+|/data/data|/storage/emulated/\d+|/sdcard|/home/[^/\s]+|/Users/[^/\s]+)(/\S*)?",
        )
        .unwrap()
    })
}

/// Long opaque runs (>= 32 chars of token-ish characters with no separators)
/// look like bearer tokens / keys rather than code identifiers (which contain
/// dots). Redact them defensively.
fn long_token_re() -> &'static regex::Regex {
    static RE: OnceLock<regex::Regex> = OnceLock::new();
    RE.get_or_init(|| regex::Regex::new(r"[A-Za-z0-9_\-]{32,}").unwrap())
}

/// Redact anything resembling private user data from free text (stack traces).
/// The field allowlist already drops identifying ACRA fields; this scrubs the
/// one free-form field we DO keep.
pub fn sanitize_text(input: &str) -> String {
    let s = email_re().replace_all(input, "[redacted-email]");
    let s = ipv4_re().replace_all(&s, "[redacted-ip]");
    let s = user_path_re().replace_all(&s, "$1/[redacted-path]");
    let s = long_token_re().replace_all(&s, "[redacted-token]");
    s.into_owned()
}

// ---------------------------------------------------------------------------
// Fingerprinting
// ---------------------------------------------------------------------------

/// Compute a stable fingerprint for a stack trace.
///
/// Stability across app versions matters: we key on the exception class plus
/// the top frames with their *line numbers stripped* (`Foo.kt:42` → `Foo.kt`),
/// and drop the exception message (it often embeds variable data). Two reports
/// of the same logical crash fingerprint identically so they dedup onto one
/// issue.
pub fn fingerprint(stack_trace: &str) -> String {
    let mut normalized = String::new();
    let mut frames = 0usize;
    for (i, raw_line) in stack_trace.lines().enumerate() {
        let line = raw_line.trim();
        if line.is_empty() {
            continue;
        }
        if i == 0 {
            // Header: keep the exception class, drop the message after ':'.
            let class = line.split(':').next().unwrap_or(line).trim();
            normalized.push_str(class);
            normalized.push('\n');
            continue;
        }
        if let Some(frame) = line.strip_prefix("at ") {
            // Strip the "(File.kt:NN)" location so line-number churn doesn't
            // change the fingerprint; keep the qualified method.
            let method = frame.split('(').next().unwrap_or(frame).trim();
            normalized.push_str(method);
            normalized.push('\n');
            frames += 1;
            if frames >= 8 {
                break;
            }
        }
    }
    if normalized.is_empty() {
        normalized.push_str(stack_trace.trim());
    }
    let digest = Sha256::digest(normalized.as_bytes());
    hex_lower(&digest[..8])
}

fn hex_lower(bytes: &[u8]) -> String {
    let mut s = String::with_capacity(bytes.len() * 2);
    for b in bytes {
        s.push_str(&format!("{b:02x}"));
    }
    s
}

// ---------------------------------------------------------------------------
// Report → sanitized issue
// ---------------------------------------------------------------------------

/// A sanitized crash report ready to become a GitHub issue.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SanitizedReport {
    pub fingerprint: String,
    pub title: String,
    pub body: String,
}

fn json_str(raw: &Value, key: &str) -> Option<String> {
    match raw.get(key) {
        Some(Value::String(s)) if !s.is_empty() => Some(s.clone()),
        Some(Value::Number(n)) => Some(n.to_string()),
        _ => None,
    }
}

/// Build a sanitized report from an ACRA JSON payload, or `None` if it carries
/// no usable stack trace (an empty/garbage POST we refuse to file).
pub fn build_sanitized_report(raw: &Value) -> Option<SanitizedReport> {
    let stack_raw = json_str(raw, "STACK_TRACE")?;
    let mut stack = sanitize_text(stack_raw.trim());
    if stack.is_empty() {
        return None;
    }
    if stack.chars().count() > MAX_STACK_TRACE_CHARS {
        let truncated: String = stack.chars().take(MAX_STACK_TRACE_CHARS).collect();
        stack = format!("{truncated}\n… [truncated]");
    }

    let fp = fingerprint(&stack);
    let title = build_title(&stack, &fp);

    let mut body = String::new();
    body.push_str("Automated crash report from a `foss`-flavor build (ACRA → relay `/crash`).\n\n");
    for (field, label) in ALLOWLISTED_FIELDS {
        if let Some(val) = json_str(raw, field) {
            // Allowlisted fields are non-identifying, but sanitize defensively.
            body.push_str(&format!("- **{label}:** {}\n", sanitize_text(&val)));
        }
    }
    body.push_str("\n```\n");
    body.push_str(&stack);
    body.push_str("\n```\n\n");
    // Machine-readable marker so dedup can survive a relay restart via a GitHub
    // search if we ever want it (not required for the in-memory fast path).
    body.push_str(&format!("<!-- crash-fingerprint: {fp} -->\n"));

    Some(SanitizedReport {
        fingerprint: fp,
        title,
        body,
    })
}

fn build_title(stack: &str, fp: &str) -> String {
    let first = stack.lines().next().unwrap_or("").trim();
    let class = first.split(':').next().unwrap_or(first).trim();
    // Short, leading-package-stripped exception name for readability.
    let short = class.rsplit('.').next().unwrap_or(class);
    let short = if short.is_empty() { "Crash" } else { short };
    format!("[crash] {short} ({fp})")
}

// ---------------------------------------------------------------------------
// Dedup + spike-cap + global cap
// ---------------------------------------------------------------------------

/// Tunables for the dedup / capping logic.
#[derive(Debug, Clone)]
pub struct CrashLimitsConfig {
    /// Sliding window for the per-fingerprint spike-cap.
    pub per_fingerprint_window: Duration,
    /// Max reports (create + appends) accepted per fingerprint per window.
    pub per_fingerprint_max_reports: u32,
    /// Sliding window for the global new-issue cap.
    pub global_window: Duration,
    /// Max NEW issues created across all fingerprints per global window.
    pub global_max_new_issues: u32,
}

impl Default for CrashLimitsConfig {
    fn default() -> Self {
        Self {
            per_fingerprint_window: Duration::from_secs(3600),
            per_fingerprint_max_reports: 5,
            global_window: Duration::from_secs(3600),
            global_max_new_issues: 20,
        }
    }
}

/// What to do with a freshly-fingerprinted report.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum CrashDecision {
    /// First sighting (this window) — open a new issue.
    CreateIssue,
    /// Known fingerprint — append to its existing issue.
    AppendToIssue(u64),
    /// Spike-capped: too many reports for this fingerprint this window.
    DropSpikeCapped,
    /// Global cap reached: refuse to open another new issue this window.
    DropGlobalCapped,
}

struct FingerprintState {
    issue_number: Option<u64>,
    window_start: Instant,
    reports_in_window: u32,
    last_seen: Instant,
}

struct DedupInner {
    fingerprints: HashMap<String, FingerprintState>,
    global_window_start: Instant,
    global_new_issues: u32,
}

/// In-memory dedup / spike-cap / global-cap registry. Thread-safe; resets on
/// relay restart. Time is injectable (`*_at`) for deterministic tests.
pub struct CrashDedup {
    cfg: CrashLimitsConfig,
    inner: Mutex<DedupInner>,
}

impl CrashDedup {
    pub fn new(cfg: CrashLimitsConfig) -> Self {
        Self {
            cfg,
            inner: Mutex::new(DedupInner {
                fingerprints: HashMap::new(),
                global_window_start: Instant::now(),
                global_new_issues: 0,
            }),
        }
    }

    pub fn decide(&self, fp: &str) -> CrashDecision {
        self.decide_at(fp, Instant::now())
    }

    pub fn decide_at(&self, fp: &str, now: Instant) -> CrashDecision {
        let mut inner = self.inner.lock().unwrap();

        // Roll the global window.
        if now.duration_since(inner.global_window_start) >= self.cfg.global_window {
            inner.global_window_start = now;
            inner.global_new_issues = 0;
        }

        let entry = inner
            .fingerprints
            .entry(fp.to_string())
            .or_insert_with(|| FingerprintState {
                issue_number: None,
                window_start: now,
                reports_in_window: 0,
                last_seen: now,
            });
        entry.last_seen = now;

        // Roll the per-fingerprint window (keep the known issue number so a
        // recurring crash keeps appending to the same issue across windows).
        if now.duration_since(entry.window_start) >= self.cfg.per_fingerprint_window {
            entry.window_start = now;
            entry.reports_in_window = 0;
        }

        entry.reports_in_window += 1;
        if entry.reports_in_window > self.cfg.per_fingerprint_max_reports {
            return CrashDecision::DropSpikeCapped;
        }

        if let Some(number) = entry.issue_number {
            return CrashDecision::AppendToIssue(number);
        }

        // Needs a new issue — check the global backstop.
        if inner.global_new_issues >= self.cfg.global_max_new_issues {
            return CrashDecision::DropGlobalCapped;
        }
        inner.global_new_issues += 1;
        CrashDecision::CreateIssue
    }

    /// Record the issue number after a successful create, so subsequent reports
    /// for this fingerprint append instead of opening duplicates.
    pub fn record_issue_number(&self, fp: &str, number: u64) {
        let mut inner = self.inner.lock().unwrap();
        if let Some(entry) = inner.fingerprints.get_mut(fp) {
            entry.issue_number = Some(number);
        }
    }

    /// Evict fingerprint entries not seen within 2× the per-fingerprint window
    /// so the map can't grow without bound. Dropping an idle entry only loses
    /// dedup for a crash that hasn't recurred in a long time (it would open a
    /// fresh issue next time, which is fine).
    pub fn sweep_expired(&self) {
        self.sweep_expired_at(Instant::now());
    }

    pub fn sweep_expired_at(&self, now: Instant) {
        let ttl = self.cfg.per_fingerprint_window.saturating_mul(2);
        let mut inner = self.inner.lock().unwrap();
        inner
            .fingerprints
            .retain(|_, st| now.duration_since(st.last_seen) < ttl);
    }

    pub fn tracked_fingerprints(&self) -> usize {
        self.inner.lock().unwrap().fingerprints.len()
    }
}

// ---------------------------------------------------------------------------
// GitHub issue filing
// ---------------------------------------------------------------------------

#[derive(Debug, Error)]
pub enum GitHubError {
    #[error("http error: {0}")]
    Http(String),
    #[error("github api error: status {0}: {1}")]
    Api(u16, String),
}

/// Files / appends GitHub issues. Behind a trait so tests substitute a fake
/// and the relay never needs network access to exercise the pipeline.
#[async_trait]
pub trait GitHubIssueFiler: Send + Sync {
    async fn create_issue(
        &self,
        title: &str,
        body: &str,
        labels: &[&str],
    ) -> Result<u64, GitHubError>;
    async fn comment_issue(&self, number: u64, body: &str) -> Result<(), GitHubError>;
}

/// Real filer talking to the GitHub REST API.
pub struct RealGitHubIssueFiler {
    http: reqwest::Client,
    api_base: String,
    repo: String,
    token: String,
}

impl RealGitHubIssueFiler {
    pub fn new(api_base: String, repo: String, token: String) -> Self {
        Self {
            http: reqwest::Client::new(),
            api_base: api_base.trim_end_matches('/').to_string(),
            repo,
            token,
        }
    }
}

#[async_trait]
impl GitHubIssueFiler for RealGitHubIssueFiler {
    async fn create_issue(
        &self,
        title: &str,
        body: &str,
        labels: &[&str],
    ) -> Result<u64, GitHubError> {
        let url = format!("{}/repos/{}/issues", self.api_base, self.repo);
        let payload = serde_json::json!({
            "title": title,
            "body": body,
            "labels": labels,
        });
        let resp = self
            .http
            .post(&url)
            .header("Authorization", format!("Bearer {}", self.token))
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "rouse-relay")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .json(&payload)
            .send()
            .await
            .map_err(|e| GitHubError::Http(e.to_string()))?;
        let status = resp.status();
        let text = resp.text().await.unwrap_or_default();
        if !status.is_success() {
            return Err(GitHubError::Api(status.as_u16(), text));
        }
        let parsed: Value =
            serde_json::from_str(&text).map_err(|e| GitHubError::Http(e.to_string()))?;
        parsed
            .get("number")
            .and_then(|n| n.as_u64())
            .ok_or_else(|| GitHubError::Api(status.as_u16(), "missing issue number".to_string()))
    }

    async fn comment_issue(&self, number: u64, body: &str) -> Result<(), GitHubError> {
        let url = format!(
            "{}/repos/{}/issues/{}/comments",
            self.api_base, self.repo, number
        );
        let payload = serde_json::json!({ "body": body });
        let resp = self
            .http
            .post(&url)
            .header("Authorization", format!("Bearer {}", self.token))
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "rouse-relay")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .json(&payload)
            .send()
            .await
            .map_err(|e| GitHubError::Http(e.to_string()))?;
        let status = resp.status();
        if !status.is_success() {
            let text = resp.text().await.unwrap_or_default();
            return Err(GitHubError::Api(status.as_u16(), text));
        }
        Ok(())
    }
}

// ---------------------------------------------------------------------------
// CrashService — ties the pipeline together
// ---------------------------------------------------------------------------

/// Outcome of ingesting one crash report (returned for logging/tests).
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum CrashIngestOutcome {
    /// New GitHub issue opened.
    Filed(u64),
    /// Appended a comment to an existing issue.
    Appended(u64),
    /// Spike-capped duplicate (same fingerprint, over the per-window cap).
    DroppedDuplicate,
    /// Global new-issue cap reached this window.
    DroppedCapped,
    /// GitHub token not configured: sanitized + logged, nothing filed.
    NoSink,
    /// Report carried no usable stack trace.
    InvalidReport,
    /// GitHub API call failed (logged; the device is not told).
    FilingFailed,
}

/// Per-IP rate-limit defaults for `/crash`: ~10 accepted POSTs/min per source
/// IP, burst 20. A crash loop on one device is further bounded by the
/// spike-cap; this just stops raw flooding before any work is done.
fn default_ip_rate_config() -> RateLimitConfig {
    RateLimitConfig {
        max_tokens: 20,
        refill_interval: Duration::from_secs(6),
    }
}

/// Owns the crash pipeline: per-IP limiter, dedup/cap registry, and the
/// (optional) GitHub filer. Lives in [`crate::api::AppState`].
pub struct CrashService {
    filer: Option<Arc<dyn GitHubIssueFiler>>,
    ip_limiter: RateLimiter,
    dedup: CrashDedup,
}

impl CrashService {
    pub fn new(
        filer: Option<Arc<dyn GitHubIssueFiler>>,
        ip_rate: RateLimitConfig,
        limits: CrashLimitsConfig,
    ) -> Self {
        Self {
            filer,
            ip_limiter: RateLimiter::new(ip_rate),
            dedup: CrashDedup::new(limits),
        }
    }

    /// A service with no GitHub sink and default limits — used by tests and as
    /// the production default when no token/repo is configured.
    pub fn disabled() -> Self {
        Self::new(None, default_ip_rate_config(), CrashLimitsConfig::default())
    }

    /// Convenience constructor mirroring the production wiring.
    pub fn with_filer(filer: Option<Arc<dyn GitHubIssueFiler>>) -> Self {
        Self::new(
            filer,
            default_ip_rate_config(),
            CrashLimitsConfig::default(),
        )
    }

    /// Per-IP admission check. `Ok(())` if allowed, `Err(retry_after_secs)` if
    /// rate-limited. Call before doing any parsing work.
    pub fn allow_ip(&self, ip: &str) -> Result<(), u64> {
        self.ip_limiter.try_acquire(ip)
    }

    /// Whether GitHub filing is configured.
    pub fn has_sink(&self) -> bool {
        self.filer.is_some()
    }

    pub fn dedup(&self) -> &CrashDedup {
        &self.dedup
    }

    /// Evict expired per-IP limiter buckets and idle fingerprint entries so the
    /// in-memory maps can't grow without bound. Called from the shared
    /// rate-limit sweep loop.
    pub fn sweep_expired(&self) {
        self.ip_limiter.sweep_expired();
        self.dedup.sweep_expired();
    }

    /// Sanitize → dedup/cap → file. Returns the outcome for logging.
    pub async fn process(&self, raw: &Value) -> CrashIngestOutcome {
        let report = match build_sanitized_report(raw) {
            Some(r) => r,
            None => return CrashIngestOutcome::InvalidReport,
        };

        match self.dedup.decide(&report.fingerprint) {
            CrashDecision::DropSpikeCapped => {
                info!(fingerprint = %report.fingerprint, "crash report spike-capped");
                CrashIngestOutcome::DroppedDuplicate
            }
            CrashDecision::DropGlobalCapped => {
                warn!(fingerprint = %report.fingerprint, "crash global new-issue cap reached");
                CrashIngestOutcome::DroppedCapped
            }
            CrashDecision::CreateIssue => self.create(&report).await,
            CrashDecision::AppendToIssue(number) => self.append(&report, number).await,
        }
    }

    async fn create(&self, report: &SanitizedReport) -> CrashIngestOutcome {
        let Some(filer) = &self.filer else {
            info!(
                fingerprint = %report.fingerprint,
                title = %report.title,
                "crash report sanitized but GitHub filing not configured (no-op)"
            );
            return CrashIngestOutcome::NoSink;
        };
        match filer
            .create_issue(&report.title, &report.body, &["crash"])
            .await
        {
            Ok(number) => {
                self.dedup.record_issue_number(&report.fingerprint, number);
                info!(fingerprint = %report.fingerprint, issue = number, "filed crash issue");
                CrashIngestOutcome::Filed(number)
            }
            Err(e) => {
                warn!(fingerprint = %report.fingerprint, error = %e, "failed to file crash issue");
                CrashIngestOutcome::FilingFailed
            }
        }
    }

    async fn append(&self, report: &SanitizedReport, number: u64) -> CrashIngestOutcome {
        let Some(filer) = &self.filer else {
            return CrashIngestOutcome::NoSink;
        };
        let comment = format!("Recurrence of this crash.\n\n```\n{}\n```\n", report.body);
        match filer.comment_issue(number, &comment).await {
            Ok(()) => {
                info!(fingerprint = %report.fingerprint, issue = number, "appended crash recurrence");
                CrashIngestOutcome::Appended(number)
            }
            Err(e) => {
                warn!(fingerprint = %report.fingerprint, error = %e, "failed to append crash recurrence");
                CrashIngestOutcome::FilingFailed
            }
        }
    }
}

/// Build the production GitHub filer from config, or `None` when filing is not
/// configured (no `crash_repo` or the token env var is unset/empty). When
/// `None`, the endpoint still sanitizes + dedups + logs and returns 202.
pub fn build_filer_from_config(
    cfg: &crate::config::GitHubConfig,
) -> Option<Arc<dyn GitHubIssueFiler>> {
    if cfg.crash_repo.is_empty() {
        info!("[github] crash_repo not configured; /crash will sanitize+log only");
        return None;
    }
    match cfg.resolve_token() {
        Some(token) => {
            info!(repo = %cfg.crash_repo, "[github] crash issue filing enabled");
            Some(Arc::new(RealGitHubIssueFiler::new(
                cfg.api_base_url.clone(),
                cfg.crash_repo.clone(),
                token,
            )))
        }
        None => {
            warn!(
                env = %cfg.token_env,
                repo = %cfg.crash_repo,
                "[github] crash_repo set but token env var unset; /crash will sanitize+log only"
            );
            None
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const SAMPLE_STACK: &str = "java.lang.IllegalStateException: widget 42 not ready\n\tat com.rousecontext.app.Foo.bar(Foo.kt:42)\n\tat com.rousecontext.app.Baz.qux(Baz.kt:13)";

    #[test]
    fn sanitize_redacts_email_ip_path_token() {
        let input =
            "user alice@example.com from 192.168.1.7 at /storage/emulated/0/Android/data/secret \
             token ABCDEFGHIJKLMNOPQRSTUVWXYZ012345678";
        let out = sanitize_text(input);
        assert!(out.contains("[redacted-email]"), "{out}");
        assert!(out.contains("[redacted-ip]"), "{out}");
        assert!(out.contains("/storage/emulated/0/[redacted-path]"), "{out}");
        assert!(out.contains("[redacted-token]"), "{out}");
        assert!(!out.contains("alice@example.com"));
        assert!(!out.contains("192.168.1.7"));
        assert!(!out.contains("secret"));
    }

    #[test]
    fn sanitize_keeps_normal_stack_frames() {
        let out = sanitize_text(SAMPLE_STACK);
        assert!(out.contains("com.rousecontext.app.Foo.bar"));
        assert!(out.contains("IllegalStateException"));
    }

    #[test]
    fn fingerprint_is_stable_across_messages_and_line_numbers() {
        let a =
            "java.lang.IllegalStateException: widget 42 not ready\n\tat com.x.Foo.bar(Foo.kt:42)";
        let b =
            "java.lang.IllegalStateException: widget 99 not ready\n\tat com.x.Foo.bar(Foo.kt:77)";
        assert_eq!(fingerprint(a), fingerprint(b));
    }

    #[test]
    fn fingerprint_differs_for_different_crashes() {
        let a = "java.lang.IllegalStateException: x\n\tat com.x.Foo.bar(Foo.kt:42)";
        let b = "java.lang.NullPointerException: y\n\tat com.x.Other.baz(Other.kt:1)";
        assert_ne!(fingerprint(a), fingerprint(b));
    }

    #[test]
    fn build_sanitized_report_requires_stack_trace() {
        let raw = serde_json::json!({ "APP_VERSION_NAME": "1.0.3" });
        assert!(build_sanitized_report(&raw).is_none());
    }

    #[test]
    fn build_sanitized_report_drops_identifying_fields() {
        let raw = serde_json::json!({
            "STACK_TRACE": SAMPLE_STACK,
            "APP_VERSION_NAME": "1.0.3",
            "ANDROID_VERSION": "14",
            "INSTALLATION_ID": "11111111-2222-3333-4444-555555555555",
            "USER_EMAIL": "alice@example.com",
            "LOGCAT": "private logs here",
        });
        let report = build_sanitized_report(&raw).unwrap();
        assert!(report.body.contains("1.0.3"));
        assert!(report.body.contains("Android version"));
        assert!(!report.body.contains("11111111-2222"));
        assert!(!report.body.contains("alice@example.com"));
        assert!(!report.body.contains("private logs here"));
        assert!(report.title.starts_with("[crash] IllegalStateException ("));
    }

    #[test]
    fn dedup_first_creates_then_appends() {
        let dedup = CrashDedup::new(CrashLimitsConfig::default());
        let now = Instant::now();
        assert_eq!(dedup.decide_at("fp1", now), CrashDecision::CreateIssue);
        // Before the issue number is recorded, a second report still wants the
        // issue created (idempotent create intent) — but once recorded it
        // appends.
        dedup.record_issue_number("fp1", 7);
        assert_eq!(dedup.decide_at("fp1", now), CrashDecision::AppendToIssue(7));
    }

    #[test]
    fn dedup_spike_caps_per_fingerprint() {
        let cfg = CrashLimitsConfig {
            per_fingerprint_max_reports: 3,
            ..CrashLimitsConfig::default()
        };
        let dedup = CrashDedup::new(cfg);
        let now = Instant::now();
        assert_eq!(dedup.decide_at("fp", now), CrashDecision::CreateIssue);
        dedup.record_issue_number("fp", 1);
        assert_eq!(dedup.decide_at("fp", now), CrashDecision::AppendToIssue(1));
        assert_eq!(dedup.decide_at("fp", now), CrashDecision::AppendToIssue(1));
        // 4th report in the window is spike-capped.
        assert_eq!(dedup.decide_at("fp", now), CrashDecision::DropSpikeCapped);
    }

    #[test]
    fn dedup_resets_after_window() {
        let cfg = CrashLimitsConfig {
            per_fingerprint_max_reports: 1,
            per_fingerprint_window: Duration::from_secs(60),
            ..CrashLimitsConfig::default()
        };
        let dedup = CrashDedup::new(cfg);
        let now = Instant::now();
        assert_eq!(dedup.decide_at("fp", now), CrashDecision::CreateIssue);
        dedup.record_issue_number("fp", 5);
        assert_eq!(dedup.decide_at("fp", now), CrashDecision::DropSpikeCapped);
        // After the window, counting resets; the known issue number is kept so
        // it appends rather than opening a duplicate.
        let later = now + Duration::from_secs(61);
        assert_eq!(
            dedup.decide_at("fp", later),
            CrashDecision::AppendToIssue(5)
        );
    }

    #[test]
    fn dedup_global_cap_blocks_new_issues_across_fingerprints() {
        let cfg = CrashLimitsConfig {
            global_max_new_issues: 2,
            per_fingerprint_max_reports: 10,
            ..CrashLimitsConfig::default()
        };
        let dedup = CrashDedup::new(cfg);
        let now = Instant::now();
        assert_eq!(dedup.decide_at("a", now), CrashDecision::CreateIssue);
        assert_eq!(dedup.decide_at("b", now), CrashDecision::CreateIssue);
        // Third distinct fingerprint can't open a new issue this window.
        assert_eq!(dedup.decide_at("c", now), CrashDecision::DropGlobalCapped);
    }

    #[test]
    fn dedup_sweep_evicts_idle_fingerprints() {
        let cfg = CrashLimitsConfig {
            per_fingerprint_window: Duration::from_secs(60),
            ..CrashLimitsConfig::default()
        };
        let dedup = CrashDedup::new(cfg);
        let now = Instant::now();
        dedup.decide_at("fp", now);
        assert_eq!(dedup.tracked_fingerprints(), 1);
        dedup.sweep_expired_at(now + Duration::from_secs(59));
        assert_eq!(dedup.tracked_fingerprints(), 1);
        dedup.sweep_expired_at(now + Duration::from_secs(121));
        assert_eq!(dedup.tracked_fingerprints(), 0);
    }

    // --- CrashService orchestration via a fake filer ---

    struct FakeFiler {
        creates: Mutex<Vec<(String, String)>>,
        comments: Mutex<Vec<(u64, String)>>,
        next_number: Mutex<u64>,
        fail: bool,
    }

    impl FakeFiler {
        fn new() -> Self {
            Self {
                creates: Mutex::new(Vec::new()),
                comments: Mutex::new(Vec::new()),
                next_number: Mutex::new(100),
                fail: false,
            }
        }
        fn failing() -> Self {
            Self {
                fail: true,
                ..Self::new()
            }
        }
    }

    #[async_trait]
    impl GitHubIssueFiler for FakeFiler {
        async fn create_issue(
            &self,
            title: &str,
            body: &str,
            _labels: &[&str],
        ) -> Result<u64, GitHubError> {
            if self.fail {
                return Err(GitHubError::Http("boom".into()));
            }
            self.creates
                .lock()
                .unwrap()
                .push((title.to_string(), body.to_string()));
            let mut n = self.next_number.lock().unwrap();
            let number = *n;
            *n += 1;
            Ok(number)
        }
        async fn comment_issue(&self, number: u64, body: &str) -> Result<(), GitHubError> {
            if self.fail {
                return Err(GitHubError::Http("boom".into()));
            }
            self.comments
                .lock()
                .unwrap()
                .push((number, body.to_string()));
            Ok(())
        }
    }

    fn sample_report() -> Value {
        serde_json::json!({
            "STACK_TRACE": SAMPLE_STACK,
            "APP_VERSION_NAME": "1.0.3",
        })
    }

    #[tokio::test]
    async fn process_files_then_appends_same_fingerprint() {
        let filer = Arc::new(FakeFiler::new());
        let svc = CrashService::with_filer(Some(filer.clone()));
        let first = svc.process(&sample_report()).await;
        assert!(matches!(first, CrashIngestOutcome::Filed(_)));
        let second = svc.process(&sample_report()).await;
        assert!(matches!(second, CrashIngestOutcome::Appended(_)));
        assert_eq!(filer.creates.lock().unwrap().len(), 1);
        assert_eq!(filer.comments.lock().unwrap().len(), 1);
    }

    #[tokio::test]
    async fn process_no_sink_when_filer_absent() {
        let svc = CrashService::disabled();
        assert_eq!(
            svc.process(&sample_report()).await,
            CrashIngestOutcome::NoSink
        );
    }

    #[tokio::test]
    async fn process_invalid_report_without_stack() {
        let svc = CrashService::disabled();
        let raw = serde_json::json!({ "APP_VERSION_NAME": "1.0.3" });
        assert_eq!(svc.process(&raw).await, CrashIngestOutcome::InvalidReport);
    }

    #[tokio::test]
    async fn process_filing_failure_is_reported_not_panicked() {
        let svc = CrashService::with_filer(Some(Arc::new(FakeFiler::failing())));
        assert_eq!(
            svc.process(&sample_report()).await,
            CrashIngestOutcome::FilingFailed
        );
    }

    #[test]
    fn ip_rate_limit_rejects_after_burst() {
        let svc = CrashService::new(
            None,
            RateLimitConfig {
                max_tokens: 2,
                refill_interval: Duration::from_secs(10),
            },
            CrashLimitsConfig::default(),
        );
        assert!(svc.allow_ip("1.2.3.4").is_ok());
        assert!(svc.allow_ip("1.2.3.4").is_ok());
        assert!(svc.allow_ip("1.2.3.4").is_err());
        // A different IP has its own bucket.
        assert!(svc.allow_ip("5.6.7.8").is_ok());
    }
}
