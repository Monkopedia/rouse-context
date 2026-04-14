//! Cloudflare implementation of [`DnsChallengeProvider`].
//!
//! Uses the Cloudflare v4 DNS API to publish, wait for, and delete TXT
//! records for ACME DNS-01 challenges. Propagation is checked against
//! public DNS resolvers (Google + Cloudflare) rather than the Cloudflare
//! authoritative servers, because what matters is whether the ACME server's
//! resolver sees the record.

use async_trait::async_trait;
use hickory_resolver::config::{ResolverConfig, ResolverOpts};
use hickory_resolver::TokioAsyncResolver;
use tracing::{debug, info, warn};

use crate::dns_challenge::{DnsChallengeProvider, DnsError, TxtHandle};

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
    propagation_timeout_secs: u64,
    poll_interval_secs: u64,
}

impl CloudflareDnsProvider {
    /// Create a Cloudflare DNS provider with the given API token and zone ID.
    ///
    /// `propagation_timeout_secs` is the maximum time to wait for a TXT
    /// record to become visible on public resolvers; `poll_interval_secs`
    /// is the gap between DNS lookup attempts.
    pub fn new(
        api_token: String,
        zone_id: String,
        propagation_timeout_secs: u64,
        poll_interval_secs: u64,
    ) -> Self {
        Self {
            http: reqwest::Client::new(),
            api_token,
            zone_id,
            api_base: CLOUDFLARE_API_BASE.to_string(),
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

        // Use Google public DNS to avoid local caching issues.
        let resolver = TokioAsyncResolver::tokio(ResolverConfig::google(), {
            let mut opts = ResolverOpts::default();
            // Disable the resolver cache so each poll does a fresh lookup
            opts.cache_size = 0;
            opts
        });

        info!(
            txt_name = %name,
            expected_value = %value,
            timeout_secs = self.propagation_timeout_secs,
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
}
