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

/// A [`DnsClient`] backed by the Cloudflare REST API.
pub struct CloudflareDnsClient {
    http: reqwest::Client,
    api_token: String,
    zone_id: String,
    base_domain: String,
}

impl CloudflareDnsClient {
    /// Create a new Cloudflare DNS client.
    ///
    /// - `api_token`: Cloudflare API token with DNS edit permission
    /// - `zone_id`: Cloudflare zone ID for the base domain
    /// - `base_domain`: e.g. "rousecontext.com"
    pub fn new(api_token: String, zone_id: String, base_domain: String) -> Self {
        Self {
            http: reqwest::Client::new(),
            api_token,
            zone_id,
            base_domain,
        }
    }

    /// List DNS record IDs matching a given name (e.g. "*.foo.rousecontext.com").
    async fn list_records_by_name(&self, name: &str) -> Result<Vec<String>, DnsError> {
        let url = format!(
            "https://api.cloudflare.com/client/v4/zones/{}/dns_records?name={}",
            self.zone_id, name
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
            "https://api.cloudflare.com/client/v4/zones/{}/dns_records/{record_id}",
            self.zone_id
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
