//! DNS record management via the Cloudflare API.
//!
//! Provides a trait for creating and deleting CNAME records that point
//! device subdomains to the relay server. Uses a trait so tests can
//! substitute a mock without hitting the real Cloudflare API.

use async_trait::async_trait;
use thiserror::Error;
use tracing::{debug, info, warn};

#[derive(Debug, Error)]
pub enum DnsError {
    #[error("http error: {0}")]
    Http(String),
    #[error("record not found: {0}")]
    NotFound(String),
}

/// Manages DNS CNAME records for device subdomains.
#[async_trait]
pub trait DnsClient: Send + Sync {
    /// Delete ALL DNS records for the given subdomain (wildcard + bare).
    ///
    /// This removes any CNAME records like `*.{subdomain}.{base_domain}` and
    /// `{subdomain}.{base_domain}`. Silently succeeds if no records exist.
    async fn delete_subdomain_records(&self, subdomain: &str) -> Result<(), DnsError>;
}

/// Default Cloudflare v4 API base URL. Overridable via
/// [`CloudflareDnsClient::with_api_base`] for tests.
pub(crate) const CLOUDFLARE_API_BASE: &str = "https://api.cloudflare.com";

/// A [`DnsClient`] backed by the Cloudflare REST API.
pub struct CloudflareDnsClient {
    http: reqwest::Client,
    api_token: String,
    zone_id: String,
    base_domain: String,
    api_base: String,
}

impl CloudflareDnsClient {
    /// Create a new Cloudflare DNS client pointed at the production
    /// Cloudflare API.
    ///
    /// - `api_token`: Cloudflare API token with DNS edit permission
    /// - `zone_id`: Cloudflare zone ID for the base domain
    /// - `base_domain`: e.g. "rousecontext.com"
    pub fn new(api_token: String, zone_id: String, base_domain: String) -> Self {
        Self::with_api_base(
            api_token,
            zone_id,
            base_domain,
            CLOUDFLARE_API_BASE.to_string(),
        )
    }

    /// Construct a client pointed at an arbitrary API base URL. Used by
    /// tests to redirect traffic at an in-process mock Cloudflare server.
    pub(crate) fn with_api_base(
        api_token: String,
        zone_id: String,
        base_domain: String,
        api_base: String,
    ) -> Self {
        Self {
            http: reqwest::Client::new(),
            api_token,
            zone_id,
            base_domain,
            api_base,
        }
    }

    /// List DNS record IDs matching a given name (e.g. "*.foo.rousecontext.com").
    async fn list_records_by_name(&self, name: &str) -> Result<Vec<String>, DnsError> {
        let url = format!(
            "{}/client/v4/zones/{}/dns_records?name={}",
            self.api_base, self.zone_id, name
        );

        let resp = self
            .http
            .get(&url)
            .bearer_auth(&self.api_token)
            .send()
            .await
            .map_err(|e| DnsError::Http(format!("Cloudflare list records: {e}")))?;

        if !resp.status().is_success() {
            let status = resp.status();
            let body = resp.text().await.unwrap_or_default();
            return Err(DnsError::Http(format!(
                "Cloudflare list records failed ({status}): {body}"
            )));
        }

        let body: serde_json::Value = resp
            .json()
            .await
            .map_err(|e| DnsError::Http(format!("Cloudflare parse response: {e}")))?;

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

    /// Delete a single DNS record by ID.
    async fn delete_record(&self, record_id: &str) -> Result<(), DnsError> {
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
                debug!(record_id, "DNS record deleted");
                Ok(())
            }
            Ok(resp) if resp.status().as_u16() == 404 => {
                debug!(record_id, "DNS record already gone");
                Ok(())
            }
            Ok(resp) => {
                let status = resp.status();
                let body = resp.text().await.unwrap_or_default();
                Err(DnsError::Http(format!(
                    "Cloudflare delete record failed ({status}): {body}"
                )))
            }
            Err(e) => Err(DnsError::Http(format!("Cloudflare delete request: {e}"))),
        }
    }
}

#[async_trait]
impl DnsClient for CloudflareDnsClient {
    async fn delete_subdomain_records(&self, subdomain: &str) -> Result<(), DnsError> {
        // Delete wildcard record: *.{subdomain}.{base_domain}
        let wildcard_name = format!("*.{subdomain}.{}", self.base_domain);
        // Delete bare record: {subdomain}.{base_domain}
        let bare_name = format!("{subdomain}.{}", self.base_domain);

        let mut errors = Vec::new();

        for name in [&wildcard_name, &bare_name] {
            match self.list_records_by_name(name).await {
                Ok(ids) => {
                    for id in ids {
                        if let Err(e) = self.delete_record(&id).await {
                            warn!(name, record_id = %id, error = %e, "Failed to delete DNS record");
                            errors.push(e);
                        }
                    }
                }
                Err(e) => {
                    warn!(name, error = %e, "Failed to list DNS records for deletion");
                    errors.push(e);
                }
            }
        }

        if let Some(first_err) = errors.into_iter().next() {
            return Err(first_err);
        }

        info!(subdomain, "Deleted DNS records for old subdomain");
        Ok(())
    }
}

/// A no-op DNS client for use when Cloudflare is not configured.
pub struct StubDnsClient;

#[async_trait]
impl DnsClient for StubDnsClient {
    async fn delete_subdomain_records(&self, subdomain: &str) -> Result<(), DnsError> {
        debug!(subdomain, "StubDnsClient: skipping DNS record deletion");
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    //! Cloudflare CNAME record tests (issue #304).
    //!
    //! Exercises the minimal set of Cloudflare endpoints that
    //! [`CloudflareDnsClient`] calls (list by name + delete by id) against
    //! an in-process axum mock server. Pattern mirrors the TXT-record
    //! tests in `cloudflare_dns.rs`.
    //!
    //! [`StubDnsClient`] is also covered since it's used by
    //! [`build_dns_client`] when Cloudflare credentials are absent.

    use super::*;
    use axum::extract::{Path, Query, State};
    use axum::http::StatusCode;
    use axum::response::IntoResponse;
    use axum::routing::{delete, get};
    use axum::{Json, Router};
    use std::collections::HashMap;
    use std::net::SocketAddr;
    use std::sync::{Arc, Mutex};
    use tokio::net::TcpListener;

    /// In-memory Cloudflare CNAME record store used by the mock API server.
    #[derive(Default)]
    struct MockCfRecords {
        /// record id -> name
        records: HashMap<String, String>,
        /// monotonically increasing counter for record id generation
        next_id: u64,
        /// names the list endpoint was called with
        list_calls: Vec<String>,
        /// record ids the delete endpoint was called with
        delete_calls: Vec<String>,
        /// If set, delete returns this status instead of the usual behaviour.
        /// Used to simulate Cloudflare 5xx failures.
        delete_override_status: Option<StatusCode>,
        /// If set, list returns this status instead of success. Used to
        /// simulate Cloudflare failures on list.
        list_override_status: Option<StatusCode>,
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

        fn seed(&self, name: &str) -> String {
            let mut g = self.inner.lock().unwrap();
            let id = format!("rec-{}", g.next_id);
            g.next_id += 1;
            g.records.insert(id.clone(), name.to_string());
            id
        }

        fn with_delete_failure(self, status: StatusCode) -> Self {
            self.inner.lock().unwrap().delete_override_status = Some(status);
            self
        }

        fn with_list_failure(self, status: StatusCode) -> Self {
            self.inner.lock().unwrap().list_override_status = Some(status);
            self
        }
    }

    async fn mock_list_records(
        State(state): State<MockCfState>,
        Path(_zone): Path<String>,
        Query(params): Query<HashMap<String, String>>,
    ) -> impl IntoResponse {
        let mut g = state.inner.lock().unwrap();
        if let Some(status) = g.list_override_status {
            return (
                status,
                Json(serde_json::json!({
                    "success": false,
                    "errors": [{"code": 9999, "message": "simulated list failure"}],
                    "messages": [],
                    "result": null,
                })),
            )
                .into_response();
        }

        let name = params.get("name").cloned().unwrap_or_default();
        g.list_calls.push(name.clone());

        let results: Vec<serde_json::Value> = g
            .records
            .iter()
            .filter(|(_, rname)| *rname == &name)
            .map(|(id, rname)| {
                serde_json::json!({
                    "id": id,
                    "name": rname,
                    "type": "CNAME",
                })
            })
            .collect();

        Json(serde_json::json!({
            "success": true,
            "errors": [],
            "messages": [],
            "result": results,
        }))
        .into_response()
    }

    async fn mock_delete_record(
        State(state): State<MockCfState>,
        Path((_zone, record_id)): Path<(String, String)>,
    ) -> impl IntoResponse {
        let mut g = state.inner.lock().unwrap();
        g.delete_calls.push(record_id.clone());
        if let Some(status) = g.delete_override_status {
            return (
                status,
                Json(serde_json::json!({
                    "success": false,
                    "errors": [{"code": 9999, "message": "simulated delete failure"}],
                    "messages": [],
                    "result": null,
                })),
            )
                .into_response();
        }

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

    async fn spawn_mock_cloudflare(state: MockCfState) -> String {
        let app = Router::new()
            .route(
                "/client/v4/zones/{zone}/dns_records",
                get(mock_list_records),
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

    fn test_client(base: String) -> CloudflareDnsClient {
        CloudflareDnsClient::with_api_base(
            "test-token".to_string(),
            "test-zone".to_string(),
            "rousecontext.com".to_string(),
            base,
        )
    }

    #[tokio::test]
    async fn delete_subdomain_records_is_noop_when_no_records_exist() {
        let state = MockCfState::new();
        let base = spawn_mock_cloudflare(state.clone()).await;
        let client = test_client(base);

        client
            .delete_subdomain_records("abc")
            .await
            .expect("delete should succeed");

        let g = state.inner.lock().unwrap();
        // Expect two list calls: wildcard + bare.
        assert_eq!(
            g.list_calls,
            vec![
                "*.abc.rousecontext.com".to_string(),
                "abc.rousecontext.com".to_string(),
            ]
        );
        assert!(g.delete_calls.is_empty());
    }

    #[tokio::test]
    async fn delete_subdomain_records_removes_wildcard_and_bare() {
        let state = MockCfState::new();
        let wildcard_id = state.seed("*.abc.rousecontext.com");
        let bare_id = state.seed("abc.rousecontext.com");
        let base = spawn_mock_cloudflare(state.clone()).await;
        let client = test_client(base);

        client
            .delete_subdomain_records("abc")
            .await
            .expect("delete should succeed");

        let g = state.inner.lock().unwrap();
        assert!(g.records.is_empty(), "expected all matching records gone");
        assert!(
            g.delete_calls.contains(&wildcard_id),
            "wildcard record id should be deleted"
        );
        assert!(
            g.delete_calls.contains(&bare_id),
            "bare record id should be deleted"
        );
    }

    #[tokio::test]
    async fn delete_subdomain_records_deletes_multiple_records_at_same_name() {
        let state = MockCfState::new();
        // Two records at the same wildcard name (unusual but possible).
        let id1 = state.seed("*.abc.rousecontext.com");
        let id2 = state.seed("*.abc.rousecontext.com");
        let base = spawn_mock_cloudflare(state.clone()).await;
        let client = test_client(base);

        client
            .delete_subdomain_records("abc")
            .await
            .expect("delete should succeed");

        let g = state.inner.lock().unwrap();
        assert!(
            g.delete_calls.contains(&id1) && g.delete_calls.contains(&id2),
            "both duplicate records should have been deleted: {:?}",
            g.delete_calls
        );
    }

    #[tokio::test]
    async fn delete_subdomain_records_ignores_unrelated_subdomain() {
        let state = MockCfState::new();
        // Seed a record for a *different* subdomain that must be preserved.
        state.seed("*.other.rousecontext.com");
        let base = spawn_mock_cloudflare(state.clone()).await;
        let client = test_client(base);

        client
            .delete_subdomain_records("abc")
            .await
            .expect("delete should succeed");

        let g = state.inner.lock().unwrap();
        assert_eq!(
            g.records.len(),
            1,
            "unrelated subdomain record must not be deleted"
        );
        assert!(g.delete_calls.is_empty());
    }

    #[tokio::test]
    async fn delete_subdomain_records_returns_error_when_list_fails() {
        let state = MockCfState::new().with_list_failure(StatusCode::INTERNAL_SERVER_ERROR);
        let base = spawn_mock_cloudflare(state.clone()).await;
        let client = test_client(base);

        let err = client
            .delete_subdomain_records("abc")
            .await
            .expect_err("expected list failure to surface");

        assert!(matches!(err, DnsError::Http(_)), "got: {err:?}");
    }

    #[tokio::test]
    async fn delete_subdomain_records_returns_error_when_delete_fails() {
        let state = MockCfState::new();
        state.seed("*.abc.rousecontext.com");
        let state = state.with_delete_failure(StatusCode::INTERNAL_SERVER_ERROR);
        let base = spawn_mock_cloudflare(state.clone()).await;
        let client = test_client(base);

        let err = client
            .delete_subdomain_records("abc")
            .await
            .expect_err("expected delete failure to surface");

        assert!(matches!(err, DnsError::Http(_)), "got: {err:?}");
    }

    #[tokio::test]
    async fn delete_record_treats_404_as_success() {
        // Seed then manually drop the record so that the id we ask to delete
        // no longer exists -- the mock returns 404 which we want to treat
        // as a successful no-op.
        let state = MockCfState::new();
        let id = state.seed("*.abc.rousecontext.com");
        state.inner.lock().unwrap().records.clear();
        let base = spawn_mock_cloudflare(state.clone()).await;
        let client = test_client(base);

        client
            .delete_record(&id)
            .await
            .expect("404 should be swallowed as success");
    }

    #[tokio::test]
    async fn stub_dns_client_is_always_ok() {
        let stub = StubDnsClient;
        stub.delete_subdomain_records("abc")
            .await
            .expect("stub must always succeed");
    }

    #[tokio::test]
    async fn dns_error_display_includes_message() {
        let http_err = DnsError::Http("boom".to_string());
        assert!(format!("{http_err}").contains("boom"));

        let not_found = DnsError::NotFound("abc".to_string());
        assert!(format!("{not_found}").contains("abc"));
    }
}
