//! Cloudflare implementation of [`DnsChallengeProvider`].
//!
//! Uses the Cloudflare v4 DNS API to publish, wait for, and delete TXT
//! records for ACME DNS-01 challenges.
//!
//! Propagation is checked by querying the zone's authoritative nameservers
//! directly (looked up at runtime, not hard-coded) rather than a public
//! recursive resolver. See issue #171: a recursive resolver can cache the
//! pre-publish NXDOMAIN for the zone's SOA negative-cache TTL (1800s on
//! Cloudflare by default), causing our 60s propagation poll to fail even
//! after the record has been published. Authoritative servers have no cache
//! and serve the truth as soon as Cloudflare's own edge has loaded the
//! record, which is also what Let's Encrypt's validator queries.
//!
//! If the NS lookup fails or yields no IPs (weird network conditions) we
//! fall back to Google's recursive resolver with a WARN log, so broken name
//! resolution doesn't brick cert provisioning.

use async_trait::async_trait;
use hickory_resolver::config::{NameServerConfigGroup, ResolverConfig, ResolverOpts};
use hickory_resolver::TokioAsyncResolver;
use std::net::IpAddr;
use std::sync::Arc;
use tracing::{debug, info, warn};

use crate::dns_challenge::{DnsChallengeProvider, DnsError, TxtHandle};

/// Resolves the authoritative nameserver IPs for a DNS zone.
///
/// This is split out behind a trait so that [`CloudflareDnsProvider`]'s
/// propagation loop can be unit-tested without touching the real DNS system:
/// tests inject a canned list of IPs (or an empty list to exercise the
/// fallback path).
#[async_trait]
pub trait NameServerResolver: Send + Sync {
    /// Return the IP addresses of the authoritative nameservers for
    /// `zone_apex` (e.g. `rousecontext.com`). Returning an empty `Vec` is
    /// allowed and causes [`CloudflareDnsProvider::wait_for_propagation`] to
    /// fall back to Google's recursive resolver.
    async fn resolve_auth_ns(&self, zone_apex: &str) -> Vec<IpAddr>;
}

/// Default [`NameServerResolver`] that walks the public DNS: resolve NS
/// records for the zone via Google, then resolve each NS hostname to IPs.
/// This one-shot lookup is fine to cache at the OS/resolver level — it's
/// the *per-poll* queries we have to route around the negative cache.
pub struct SystemNameServerResolver;

#[async_trait]
impl NameServerResolver for SystemNameServerResolver {
    async fn resolve_auth_ns(&self, zone_apex: &str) -> Vec<IpAddr> {
        let resolver = TokioAsyncResolver::tokio(ResolverConfig::google(), ResolverOpts::default());

        let ns_names: Vec<String> = match resolver.ns_lookup(zone_apex).await {
            Ok(lookup) => lookup.iter().map(|ns| ns.to_string()).collect(),
            Err(e) => {
                warn!(
                    zone_apex = %zone_apex,
                    error = %e,
                    "NS lookup for zone apex failed; will fall back to recursive resolver"
                );
                return Vec::new();
            }
        };

        if ns_names.is_empty() {
            warn!(
                zone_apex = %zone_apex,
                "NS lookup returned no names; will fall back to recursive resolver"
            );
            return Vec::new();
        }

        let mut ips: Vec<IpAddr> = Vec::new();
        for ns_name in &ns_names {
            match resolver.lookup_ip(ns_name.as_str()).await {
                Ok(lookup) => {
                    for ip in lookup.iter() {
                        ips.push(ip);
                    }
                }
                Err(e) => {
                    warn!(
                        zone_apex = %zone_apex,
                        ns_name = %ns_name,
                        error = %e,
                        "Failed to resolve NS hostname to IP"
                    );
                }
            }
        }

        if ips.is_empty() {
            warn!(
                zone_apex = %zone_apex,
                ns_names = ?ns_names,
                "Resolved zero IPs across all NS hostnames; will fall back to recursive resolver"
            );
        } else {
            debug!(
                zone_apex = %zone_apex,
                ns_names = ?ns_names,
                ns_ips = ?ips,
                "Resolved authoritative NS IPs for zone"
            );
        }
        ips
    }
}

/// Build a [`ResolverConfig`] that queries the given IPs directly (UDP+TCP
/// on port 53). Returns `None` if `ns_ips` is empty — the caller should then
/// fall back to a public recursive resolver.
fn auth_ns_resolver_config(ns_ips: &[IpAddr]) -> Option<ResolverConfig> {
    if ns_ips.is_empty() {
        return None;
    }
    // `trust_negative_responses = false` so a transient NXDOMAIN from one
    // NS doesn't stop the resolver from consulting the others.
    let group = NameServerConfigGroup::from_ips_clear(ns_ips, 53, false);
    Some(ResolverConfig::from_parts(None, vec![], group))
}

/// Default Cloudflare API base URL. Overridden in tests.
pub(crate) const CLOUDFLARE_API_BASE: &str = "https://api.cloudflare.com";

/// Cloudflare-backed DNS challenge provider.
///
/// Construct with [`CloudflareDnsProvider::new`] (production) or
/// [`CloudflareDnsProvider::with_api_base`] (tests).
pub struct CloudflareDnsProvider {
    http: reqwest::Client,
    api_token: String,
    zone_id: String,
    /// Base URL for Cloudflare API calls. Defaults to [`CLOUDFLARE_API_BASE`].
    /// Overridden in tests to point at a local mock server.
    api_base: String,
    /// Zone apex (e.g. `rousecontext.com`). Used to look up the
    /// authoritative nameservers we should query directly during
    /// propagation checks. See [`NameServerResolver`].
    base_domain: String,
    ns_resolver: Arc<dyn NameServerResolver>,
    propagation_timeout_secs: u64,
    poll_interval_secs: u64,
}

impl CloudflareDnsProvider {
    /// Create a Cloudflare DNS provider with the given API token, zone ID,
    /// and zone apex.
    ///
    /// `propagation_timeout_secs` is the maximum time to wait for a TXT
    /// record to become visible; `poll_interval_secs` is the gap between
    /// DNS lookup attempts.
    ///
    /// Uses [`SystemNameServerResolver`] to look up the zone's auth NS IPs
    /// at propagation-check time.
    pub fn new(
        api_token: String,
        zone_id: String,
        base_domain: String,
        propagation_timeout_secs: u64,
        poll_interval_secs: u64,
    ) -> Self {
        Self::with_ns_resolver(
            api_token,
            zone_id,
            base_domain,
            Arc::new(SystemNameServerResolver),
            propagation_timeout_secs,
            poll_interval_secs,
        )
    }

    /// Create a Cloudflare DNS provider with a caller-supplied
    /// [`NameServerResolver`]. Tests use this to inject canned NS IPs.
    pub fn with_ns_resolver(
        api_token: String,
        zone_id: String,
        base_domain: String,
        ns_resolver: Arc<dyn NameServerResolver>,
        propagation_timeout_secs: u64,
        poll_interval_secs: u64,
    ) -> Self {
        Self {
            http: reqwest::Client::new(),
            api_token,
            zone_id,
            api_base: CLOUDFLARE_API_BASE.to_string(),
            base_domain,
            ns_resolver,
            propagation_timeout_secs,
            poll_interval_secs,
        }
    }

    /// Create a Cloudflare DNS provider with a custom API base URL. Tests
    /// use this to point at a local mock server.
    #[cfg(test)]
    pub(crate) fn with_api_base(
        api_token: String,
        zone_id: String,
        api_base: String,
        propagation_timeout_secs: u64,
        poll_interval_secs: u64,
    ) -> Self {
        Self {
            http: reqwest::Client::new(),
            api_token,
            zone_id,
            api_base,
            base_domain: "rousecontext.com".to_string(),
            ns_resolver: Arc::new(SystemNameServerResolver),
            propagation_timeout_secs,
            poll_interval_secs,
        }
    }

    /// Variant of [`Self::with_api_base`] that also takes a custom NS
    /// resolver. Test-only.
    #[cfg(test)]
    pub(crate) fn with_api_base_and_ns_resolver(
        api_token: String,
        zone_id: String,
        api_base: String,
        base_domain: String,
        ns_resolver: Arc<dyn NameServerResolver>,
        propagation_timeout_secs: u64,
        poll_interval_secs: u64,
    ) -> Self {
        Self {
            http: reqwest::Client::new(),
            api_token,
            zone_id,
            api_base,
            base_domain,
            ns_resolver,
            propagation_timeout_secs,
            poll_interval_secs,
        }
    }

    /// List IDs of TXT records at the given name.
    ///
    /// Cloudflare rejects creating a TXT record that is identical to an
    /// existing one with a 400 "An identical record already exists." error.
    /// [`Self::cleanup_stale`] uses this to enumerate records before
    /// deleting them.
    async fn list_txt_records_by_name(&self, name: &str) -> Result<Vec<String>, DnsError> {
        let url = format!(
            "{}/client/v4/zones/{}/dns_records?type=TXT&name={}",
            self.api_base, self.zone_id, name
        );

        let resp = self
            .http
            .get(&url)
            .bearer_auth(&self.api_token)
            .send()
            .await
            .map_err(|e| DnsError::Http(format!("Cloudflare list TXT records: {e}")))?;

        let status = resp.status();
        if !status.is_success() {
            let body = resp.text().await.unwrap_or_default();
            return Err(DnsError::Provider(format!(
                "Cloudflare list TXT records failed ({status}): {body}"
            )));
        }

        let body: serde_json::Value = resp
            .json()
            .await
            .map_err(|e| DnsError::Http(format!("Cloudflare list TXT parse: {e}")))?;

        let ids = body["result"]
            .as_array()
            .map(|arr| {
                arr.iter()
                    .filter_map(|r| r["id"].as_str().map(|s| s.to_string()))
                    .collect()
            })
            .unwrap_or_default();

        Ok(ids)
    }

    /// Delete a DNS TXT record via Cloudflare API. Best-effort.
    async fn delete_record_by_id(&self, record_id: &str) {
        let url = format!(
            "{}/client/v4/zones/{}/dns_records/{record_id}",
            self.api_base, self.zone_id
        );

        match self
            .http
            .delete(&url)
            .bearer_auth(&self.api_token)
            .send()
            .await
        {
            Ok(resp) if resp.status().is_success() => {
                debug!(record_id = %record_id, "DNS TXT record deleted");
            }
            Ok(resp) => {
                warn!(
                    record_id = %record_id,
                    status = %resp.status(),
                    "Failed to delete DNS TXT record"
                );
            }
            Err(e) => {
                warn!(record_id = %record_id, error = %e, "Failed to delete DNS TXT record");
            }
        }
    }
}

#[async_trait]
impl DnsChallengeProvider for CloudflareDnsProvider {
    async fn publish_txt(&self, name: &str, value: &str) -> Result<TxtHandle, DnsError> {
        let url = format!(
            "{}/client/v4/zones/{}/dns_records",
            self.api_base, self.zone_id
        );

        info!(
            cf_url = %url,
            txt_name = %name,
            txt_value = %value,
            "Creating DNS TXT record via Cloudflare API"
        );

        let resp = self
            .http
            .post(&url)
            .bearer_auth(&self.api_token)
            .json(&serde_json::json!({
                "type": "TXT",
                "name": name,
                "content": value,
                "ttl": 120,
            }))
            .send()
            .await
            .map_err(|e| DnsError::Http(format!("Cloudflare DNS create: {e}")))?;

        let status = resp.status();
        let body: serde_json::Value = resp
            .json()
            .await
            .map_err(|e| DnsError::Http(format!("Cloudflare DNS parse: {e}")))?;

        info!(
            status = %status,
            success = %body["success"],
            errors = %body["errors"],
            "Cloudflare API response for TXT record creation"
        );

        if !status.is_success() {
            return Err(DnsError::Provider(format!(
                "Cloudflare DNS TXT create failed ({status}): {body}"
            )));
        }

        if body["success"].as_bool() != Some(true) {
            return Err(DnsError::Provider(format!(
                "Cloudflare DNS TXT create returned success=false: errors={}, messages={}",
                body["errors"], body["messages"]
            )));
        }

        let record_id = body["result"]["id"]
            .as_str()
            .ok_or_else(|| {
                DnsError::Provider(format!("no record ID in Cloudflare response: {body}"))
            })?
            .to_string();

        info!(
            record_id = %record_id,
            txt_name = %name,
            txt_value = %value,
            "DNS TXT record created successfully"
        );
        Ok(TxtHandle(record_id))
    }

    async fn wait_for_propagation(&self, name: &str, value: &str) -> Result<(), DnsError> {
        let timeout = std::time::Duration::from_secs(self.propagation_timeout_secs);
        let poll_interval = std::time::Duration::from_secs(self.poll_interval_secs);
        let start = std::time::Instant::now();

        // Look up the zone's authoritative NS IPs once, up front, so we can
        // poll them directly and bypass recursive resolvers' negative cache
        // (see module docs / issue #171).
        let ns_ips = self.ns_resolver.resolve_auth_ns(&self.base_domain).await;
        let resolver_config = auth_ns_resolver_config(&ns_ips);
        let using_auth_ns = resolver_config.is_some();
        let resolver_config = resolver_config.unwrap_or_else(|| {
            warn!(
                zone_apex = %self.base_domain,
                "Falling back to Google recursive resolver for propagation check; \
                 negative-cache poisoning may delay verification"
            );
            ResolverConfig::google()
        });

        let resolver = TokioAsyncResolver::tokio(resolver_config, {
            let mut opts = ResolverOpts::default();
            // Disable the resolver cache so each poll does a fresh lookup.
            opts.cache_size = 0;
            opts
        });

        info!(
            txt_name = %name,
            expected_value = %value,
            timeout_secs = self.propagation_timeout_secs,
            zone_apex = %self.base_domain,
            using_auth_ns = using_auth_ns,
            ns_ips = ?ns_ips,
            "Waiting for DNS TXT record propagation"
        );

        loop {
            match resolver.txt_lookup(name).await {
                Ok(lookup) => {
                    let records: Vec<String> = lookup.iter().map(|txt| txt.to_string()).collect();
                    debug!(
                        txt_name = %name,
                        found_records = ?records,
                        "DNS TXT lookup result"
                    );
                    if records.iter().any(|r| r == value) {
                        let elapsed = start.elapsed();
                        info!(
                            txt_name = %name,
                            elapsed_secs = elapsed.as_secs(),
                            "DNS TXT record verified via public DNS"
                        );
                        return Ok(());
                    }
                    debug!(
                        txt_name = %name,
                        expected = %value,
                        found = ?records,
                        "TXT record not yet matching, will retry"
                    );
                }
                Err(e) => {
                    debug!(
                        txt_name = %name,
                        error = %e,
                        "DNS TXT lookup failed (may not be propagated yet)"
                    );
                }
            }

            if start.elapsed() >= timeout {
                warn!(
                    txt_name = %name,
                    expected_value = %value,
                    timeout_secs = self.propagation_timeout_secs,
                    "DNS TXT propagation verification timed out"
                );
                return Err(DnsError::PropagationTimeout);
            }

            tokio::time::sleep(poll_interval).await;
        }
    }

    async fn delete_txt(&self, handle: &TxtHandle) {
        self.delete_record_by_id(&handle.0).await;
    }

    async fn cleanup_stale(&self, name: &str) -> Result<(), DnsError> {
        let ids = self.list_txt_records_by_name(name).await?;
        if ids.is_empty() {
            debug!(txt_name = %name, "No existing TXT records to clean up");
            return Ok(());
        }

        info!(
            txt_name = %name,
            count = ids.len(),
            "Deleting stale ACME TXT records before creating new one"
        );

        for id in ids {
            // Best-effort: if the record was already deleted by another
            // actor we should still proceed.
            self.delete_record_by_id(&id).await;
        }

        Ok(())
    }
}

#[cfg(test)]
mod tests {
    //! Cloudflare TXT record tests (issue #56).
    //!
    //! Cloudflare rejects creating a TXT record that is identical to an
    //! existing one with "An identical record already exists." We therefore
    //! list and delete any existing records at `_acme-challenge.{subdomain}`
    //! before creating a new one. These tests exercise the minimal set of
    //! Cloudflare endpoints that [`CloudflareDnsProvider`] calls.

    use super::*;
    use axum::extract::{Path, Query, State};
    use axum::http::StatusCode;
    use axum::response::IntoResponse;
    use axum::routing::{delete, get};
    use axum::{Json, Router};
    use std::collections::HashMap;
    use std::net::SocketAddr;
    use std::sync::Arc;
    use std::sync::Mutex;
    use tokio::net::TcpListener;

    /// In-memory Cloudflare TXT record store used by the mock API server below.
    #[derive(Default)]
    struct MockCfRecords {
        /// record id -> (name, content)
        records: HashMap<String, (String, String)>,
        /// monotonically increasing counter used to generate record ids
        next_id: u64,
        /// captured calls for assertion purposes
        list_calls: Vec<String>,
        delete_calls: Vec<String>,
        create_calls: Vec<(String, String)>,
        /// if true, creating a record whose (name, content) already exists
        /// returns a Cloudflare-style 400 "identical record" error
        enforce_identical_record_check: bool,
    }

    #[derive(Clone)]
    struct MockCfState {
        inner: Arc<Mutex<MockCfRecords>>,
    }

    impl MockCfState {
        fn new() -> Self {
            Self {
                inner: Arc::new(Mutex::new(MockCfRecords::default())),
            }
        }

        fn with_existing_record(name: &str, content: &str) -> Self {
            let state = Self::new();
            {
                let mut g = state.inner.lock().unwrap();
                let id = format!("rec-{}", g.next_id);
                g.next_id += 1;
                g.records
                    .insert(id, (name.to_string(), content.to_string()));
            }
            state
        }

        fn enforce_identical_record_check(self) -> Self {
            self.inner.lock().unwrap().enforce_identical_record_check = true;
            self
        }
    }

    async fn mock_list_records(
        State(state): State<MockCfState>,
        Path(_zone): Path<String>,
        Query(params): Query<HashMap<String, String>>,
    ) -> impl IntoResponse {
        let mut g = state.inner.lock().unwrap();
        let name = params.get("name").cloned().unwrap_or_default();
        let record_type = params.get("type").cloned().unwrap_or_default();
        g.list_calls.push(name.clone());

        let results: Vec<serde_json::Value> = g
            .records
            .iter()
            .filter(|(_, (rname, _))| rname == &name)
            .filter(|_| record_type.is_empty() || record_type == "TXT")
            .map(|(id, (rname, rcontent))| {
                serde_json::json!({
                    "id": id,
                    "name": rname,
                    "type": "TXT",
                    "content": rcontent,
                })
            })
            .collect();

        Json(serde_json::json!({
            "success": true,
            "errors": [],
            "messages": [],
            "result": results,
        }))
    }

    async fn mock_delete_record(
        State(state): State<MockCfState>,
        Path((_zone, record_id)): Path<(String, String)>,
    ) -> impl IntoResponse {
        let mut g = state.inner.lock().unwrap();
        g.delete_calls.push(record_id.clone());
        if g.records.remove(&record_id).is_some() {
            Json(serde_json::json!({
                "success": true,
                "errors": [],
                "messages": [],
                "result": {"id": record_id},
            }))
            .into_response()
        } else {
            (
                StatusCode::NOT_FOUND,
                Json(serde_json::json!({
                    "success": false,
                    "errors": [{"code": 81044, "message": "Record not found"}],
                    "messages": [],
                    "result": null,
                })),
            )
                .into_response()
        }
    }

    async fn mock_create_record(
        State(state): State<MockCfState>,
        Path(_zone): Path<String>,
        Json(body): Json<serde_json::Value>,
    ) -> impl IntoResponse {
        let mut g = state.inner.lock().unwrap();
        let name = body["name"].as_str().unwrap_or("").to_string();
        let content = body["content"].as_str().unwrap_or("").to_string();
        g.create_calls.push((name.clone(), content.clone()));

        if g.enforce_identical_record_check
            && g.records
                .values()
                .any(|(rname, rcontent)| rname == &name && rcontent == &content)
        {
            return (
                StatusCode::BAD_REQUEST,
                Json(serde_json::json!({
                    "success": false,
                    "errors": [{
                        "code": 81057,
                        "message": "An identical record already exists."
                    }],
                    "messages": [],
                    "result": null,
                })),
            )
                .into_response();
        }

        let id = format!("rec-{}", g.next_id);
        g.next_id += 1;
        g.records
            .insert(id.clone(), (name.clone(), content.clone()));

        Json(serde_json::json!({
            "success": true,
            "errors": [],
            "messages": [],
            "result": {
                "id": id,
                "name": name,
                "type": "TXT",
                "content": content,
            },
        }))
        .into_response()
    }

    /// Spawn an axum mock Cloudflare API server. Returns the base URL.
    async fn spawn_mock_cloudflare(state: MockCfState) -> String {
        let app = Router::new()
            .route(
                "/client/v4/zones/{zone}/dns_records",
                get(mock_list_records).post(mock_create_record),
            )
            .route(
                "/client/v4/zones/{zone}/dns_records/{record_id}",
                delete(mock_delete_record),
            )
            .with_state(state);

        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let addr: SocketAddr = listener.local_addr().unwrap();
        tokio::spawn(async move {
            axum::serve(listener, app).await.unwrap();
        });
        format!("http://{addr}")
    }

    fn test_provider(base: String) -> CloudflareDnsProvider {
        CloudflareDnsProvider::with_api_base(
            "test-token".to_string(),
            "test-zone".to_string(),
            base,
            1,
            1,
        )
    }

    #[tokio::test]
    async fn list_txt_records_returns_empty_when_none_exist() {
        let state = MockCfState::new();
        let base = spawn_mock_cloudflare(state.clone()).await;
        let provider = test_provider(base);

        let ids = provider
            .list_txt_records_by_name("_acme-challenge.abc.rousecontext.com")
            .await
            .expect("list should succeed");

        assert!(ids.is_empty(), "expected no records, got {ids:?}");
        let g = state.inner.lock().unwrap();
        assert_eq!(g.list_calls.len(), 1);
    }

    #[tokio::test]
    async fn list_txt_records_returns_ids_for_matching_name() {
        let state =
            MockCfState::with_existing_record("_acme-challenge.abc.rousecontext.com", "old-value");
        let base = spawn_mock_cloudflare(state.clone()).await;
        let provider = test_provider(base);

        let ids = provider
            .list_txt_records_by_name("_acme-challenge.abc.rousecontext.com")
            .await
            .expect("list should succeed");

        assert_eq!(ids.len(), 1);
    }

    #[tokio::test]
    async fn cleanup_stale_deletes_all_matches() {
        let state = MockCfState::with_existing_record(
            "_acme-challenge.abc.rousecontext.com",
            "stale-value",
        );
        // Add a second stale record at the same name.
        {
            let mut g = state.inner.lock().unwrap();
            let id = format!("rec-{}", g.next_id);
            g.next_id += 1;
            g.records.insert(
                id,
                (
                    "_acme-challenge.abc.rousecontext.com".to_string(),
                    "another-stale".to_string(),
                ),
            );
        }
        let base = spawn_mock_cloudflare(state.clone()).await;
        let provider = test_provider(base);

        provider
            .cleanup_stale("_acme-challenge.abc.rousecontext.com")
            .await
            .expect("cleanup should succeed");

        let g = state.inner.lock().unwrap();
        assert_eq!(g.delete_calls.len(), 2, "expected 2 deletes");
        assert!(
            g.records.is_empty(),
            "expected all matching records removed"
        );
    }

    #[tokio::test]
    async fn cleanup_stale_is_noop_when_none_exist() {
        let state = MockCfState::new();
        let base = spawn_mock_cloudflare(state.clone()).await;
        let provider = test_provider(base);

        provider
            .cleanup_stale("_acme-challenge.abc.rousecontext.com")
            .await
            .expect("cleanup should succeed when no records");

        let g = state.inner.lock().unwrap();
        assert_eq!(g.list_calls.len(), 1);
        assert_eq!(g.delete_calls.len(), 0);
    }

    #[tokio::test]
    async fn publish_txt_after_cleanup_succeeds_when_identical_record_existed() {
        // Seed an existing identical record so that a naive create would 400
        // with "An identical record already exists."
        let state = MockCfState::with_existing_record(
            "_acme-challenge.abc.rousecontext.com",
            "duplicate-value",
        )
        .enforce_identical_record_check();
        let base = spawn_mock_cloudflare(state.clone()).await;
        let provider = test_provider(base);

        // Baseline: without cleanup, create fails.
        let direct = provider
            .publish_txt("_acme-challenge.abc.rousecontext.com", "duplicate-value")
            .await;
        assert!(
            direct.is_err(),
            "expected identical-record 400 from mock Cloudflare"
        );

        // With cleanup first, create succeeds.
        provider
            .cleanup_stale("_acme-challenge.abc.rousecontext.com")
            .await
            .expect("cleanup");

        let handle = provider
            .publish_txt("_acme-challenge.abc.rousecontext.com", "duplicate-value")
            .await
            .expect("create after cleanup should succeed");

        assert!(!handle.0.is_empty());
        let g = state.inner.lock().unwrap();
        assert!(!g.delete_calls.is_empty(), "expected at least one delete");
    }

    // --- Propagation resolver routing tests (issue #171) -------------------

    use hickory_resolver::config::Protocol;
    use std::net::{IpAddr, Ipv4Addr};

    /// Spy resolver that records the zone apex it was asked about and
    /// returns a canned IP list supplied at construction time.
    struct SpyNameServerResolver {
        requested: Mutex<Vec<String>>,
        response: Vec<IpAddr>,
    }

    impl SpyNameServerResolver {
        fn new(response: Vec<IpAddr>) -> Arc<Self> {
            Arc::new(Self {
                requested: Mutex::new(Vec::new()),
                response,
            })
        }

        fn requested(&self) -> Vec<String> {
            self.requested.lock().unwrap().clone()
        }
    }

    #[async_trait]
    impl NameServerResolver for SpyNameServerResolver {
        async fn resolve_auth_ns(&self, zone_apex: &str) -> Vec<IpAddr> {
            self.requested.lock().unwrap().push(zone_apex.to_string());
            self.response.clone()
        }
    }

    #[test]
    fn auth_ns_resolver_config_returns_none_when_no_ips() {
        assert!(auth_ns_resolver_config(&[]).is_none());
    }

    #[test]
    fn auth_ns_resolver_config_builds_udp_and_tcp_entries_on_port_53() {
        let ips = vec![
            IpAddr::V4(Ipv4Addr::new(192, 0, 2, 1)),
            IpAddr::V4(Ipv4Addr::new(192, 0, 2, 2)),
        ];

        let cfg = auth_ns_resolver_config(&ips).expect("should build config when IPs given");
        let servers: Vec<_> = cfg.name_servers().iter().collect();

        // Two IPs x two protocols (UDP + TCP) = 4 entries.
        assert_eq!(servers.len(), 4, "expected UDP+TCP per IP: {servers:?}");
        for srv in &servers {
            assert_eq!(srv.socket_addr.port(), 53);
            let ip = srv.socket_addr.ip();
            assert!(ips.contains(&ip), "unexpected server IP in config: {ip:?}");
        }
        let protocols: Vec<Protocol> = servers.iter().map(|s| s.protocol).collect();
        assert!(protocols.contains(&Protocol::Udp));
        assert!(protocols.contains(&Protocol::Tcp));

        // `trust_negative_responses = false` so one misbehaving NS doesn't
        // short-circuit the whole lookup on NXDOMAIN.
        for srv in &servers {
            assert!(
                !srv.trust_negative_responses,
                "auth-NS config must not trust negative responses"
            );
        }
    }

    #[tokio::test]
    async fn wait_for_propagation_queries_ns_resolver_with_zone_apex() {
        // Use a blackhole IP so the resolver times out quickly without
        // succeeding -- we don't care about the TXT result here, only that
        // the resolver was asked about the right zone apex.
        let spy = SpyNameServerResolver::new(vec![IpAddr::V4(Ipv4Addr::new(127, 0, 0, 1))]);

        let provider = CloudflareDnsProvider::with_api_base_and_ns_resolver(
            "test-token".to_string(),
            "test-zone".to_string(),
            "http://127.0.0.1:1".to_string(),
            "rousecontext.com".to_string(),
            spy.clone(),
            1,
            1,
        );

        // Short timeout -- just need to let the poll loop start and run once.
        let _ = provider
            .wait_for_propagation("_acme-challenge.abc.rousecontext.com", "value")
            .await;

        let requested = spy.requested();
        assert_eq!(
            requested,
            vec!["rousecontext.com".to_string()],
            "expected NS resolver to be asked for the zone apex exactly once"
        );
    }

    #[tokio::test]
    async fn wait_for_propagation_falls_back_when_ns_resolver_empty() {
        // Empty response -> provider should fall back to Google recursive
        // resolver. We can't observe the Google query itself in a unit
        // test, but we *can* verify that the spy was called (so the
        // fallback branch was taken for the right reason) and that the
        // call still completes with a timeout rather than hanging.
        let spy = SpyNameServerResolver::new(Vec::new());

        let provider = CloudflareDnsProvider::with_api_base_and_ns_resolver(
            "test-token".to_string(),
            "test-zone".to_string(),
            "http://127.0.0.1:1".to_string(),
            "rousecontext.com".to_string(),
            spy.clone(),
            1,
            1,
        );

        let result = tokio::time::timeout(
            std::time::Duration::from_secs(5),
            provider.wait_for_propagation(
                "_acme-challenge.nonexistent-fallback-test.rousecontext.com",
                "value",
            ),
        )
        .await;

        assert!(
            result.is_ok(),
            "wait_for_propagation must not hang when falling back to recursive resolver"
        );

        let requested = spy.requested();
        assert_eq!(
            requested.len(),
            1,
            "NS resolver should be consulted exactly once on entry"
        );
        assert_eq!(requested[0], "rousecontext.com");
    }
}
