//! [`RealAcmeClient`] — the [`AcmeClient`] implementation that performs
//! DNS-01 challenges via Cloudflare and fetches certs from Let's Encrypt.

use async_trait::async_trait;
use hickory_resolver::config::{ResolverConfig, ResolverOpts};
use hickory_resolver::TokioAsyncResolver;
use p256::ecdsa::SigningKey;
use sha2::{Digest, Sha256};
use std::path::Path;
use tracing::{debug, error, info, warn};

use super::account::{ensure_account, load_or_create_account_key};
use super::jws::{acme_post, jwk_thumbprint};
use super::protocol::{create_order, get_directory, poll_status};
use super::types::{base64url, AcmeAuthorization, AcmeOrder};
use super::{AcmeClient, AcmeError, CertificateBundle};

/// Default Cloudflare API base URL. Overridden in tests.
const CLOUDFLARE_API_BASE: &str = "https://api.cloudflare.com";

/// Real ACME client that performs DNS-01 challenges via Cloudflare.
pub struct RealAcmeClient {
    http: reqwest::Client,
    directory_url: String,
    account_key: SigningKey,
    cf_api_token: String,
    cf_zone_id: String,
    /// Base URL for Cloudflare API calls. Defaults to [`CLOUDFLARE_API_BASE`].
    /// Overridden in tests to point at a local mock server.
    cf_api_base: String,
    base_domain: String,
    dns_propagation_timeout_secs: u64,
    dns_poll_interval_secs: u64,
}

impl RealAcmeClient {
    /// Create a new ACME client.
    ///
    /// - `directory_url`: ACME directory URL (e.g. Let's Encrypt production or staging)
    /// - `cf_api_token`: Cloudflare API token with DNS edit permission
    /// - `cf_zone_id`: Cloudflare zone ID for the base domain
    /// - `base_domain`: The base domain (e.g. "rousecontext.com")
    pub fn new(
        directory_url: String,
        cf_api_token: String,
        cf_zone_id: String,
        base_domain: String,
    ) -> Self {
        Self::with_dns_settings(directory_url, cf_api_token, cf_zone_id, base_domain, 60, 5)
    }

    /// Create a new ACME client with explicit DNS propagation settings.
    pub fn with_dns_settings(
        directory_url: String,
        cf_api_token: String,
        cf_zone_id: String,
        base_domain: String,
        dns_propagation_timeout_secs: u64,
        dns_poll_interval_secs: u64,
    ) -> Self {
        let account_key = SigningKey::random(&mut rand::thread_rng());
        info!("Generated fresh ACME account key (no key path configured)");

        Self {
            http: reqwest::Client::new(),
            directory_url,
            account_key,
            cf_api_token,
            cf_zone_id,
            cf_api_base: CLOUDFLARE_API_BASE.to_string(),
            base_domain,
            dns_propagation_timeout_secs,
            dns_poll_interval_secs,
        }
    }

    /// Create a new ACME client that persists its account key to disk.
    ///
    /// If `account_key_path` points to an existing PEM file, the key is loaded
    /// from it. Otherwise a new ECDSA P-256 key is generated and saved there.
    /// This keeps the same ACME account across relay restarts, avoiding
    /// "no such authorization" errors from orphaned pending challenges.
    ///
    /// When `require_existing_account` is true and the key file is missing,
    /// construction panics rather than silently generating a new account. Use
    /// this in production to catch deploy misconfigurations that would
    /// otherwise rotate the relay's Let's Encrypt identity.
    #[allow(clippy::too_many_arguments)]
    pub fn with_persistent_key(
        directory_url: String,
        cf_api_token: String,
        cf_zone_id: String,
        base_domain: String,
        dns_propagation_timeout_secs: u64,
        dns_poll_interval_secs: u64,
        account_key_path: &Path,
        require_existing_account: bool,
    ) -> Self {
        let account_key = load_or_create_account_key(account_key_path, require_existing_account);

        Self {
            http: reqwest::Client::new(),
            directory_url,
            account_key,
            cf_api_token,
            cf_zone_id,
            cf_api_base: CLOUDFLARE_API_BASE.to_string(),
            base_domain,
            dns_propagation_timeout_secs,
            dns_poll_interval_secs,
        }
    }

    /// List IDs of TXT records at the given name.
    ///
    /// Cloudflare rejects creating a TXT record that is identical to an
    /// existing one with a 400 "An identical record already exists." error.
    /// Callers use this together with [`Self::cleanup_existing_txt_records`]
    /// to remove stale records left behind by previous challenge attempts
    /// before creating a new one.
    async fn list_txt_records_by_name(&self, name: &str) -> Result<Vec<String>, AcmeError> {
        let url = format!(
            "{}/client/v4/zones/{}/dns_records?type=TXT&name={}",
            self.cf_api_base, self.cf_zone_id, name
        );

        let resp = self
            .http
            .get(&url)
            .bearer_auth(&self.cf_api_token)
            .send()
            .await
            .map_err(|e| AcmeError::Http(format!("Cloudflare list TXT records: {e}")))?;

        let status = resp.status();
        if !status.is_success() {
            let body = resp.text().await.unwrap_or_default();
            return Err(AcmeError::Http(format!(
                "Cloudflare list TXT records failed ({status}): {body}"
            )));
        }

        let body: serde_json::Value = resp
            .json()
            .await
            .map_err(|e| AcmeError::Http(format!("Cloudflare list TXT parse: {e}")))?;

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

    /// Delete any existing TXT records at `name`.
    ///
    /// This is a no-op if no records exist. Returns an error only if a
    /// list or delete call fails unexpectedly; individual 404s during
    /// deletion are tolerated.
    async fn cleanup_existing_txt_records(&self, name: &str) -> Result<(), AcmeError> {
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
            // delete_dns_txt is best-effort and does not return errors.
            // That is the right behavior here too: if the record was
            // already deleted by another actor we should still proceed.
            self.delete_dns_txt(&id).await;
        }

        Ok(())
    }

    /// Set a DNS TXT record via Cloudflare API. Returns the record ID.
    async fn set_dns_txt(&self, name: &str, value: &str) -> Result<String, AcmeError> {
        let url = format!(
            "{}/client/v4/zones/{}/dns_records",
            self.cf_api_base, self.cf_zone_id
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
            .bearer_auth(&self.cf_api_token)
            .json(&serde_json::json!({
                "type": "TXT",
                "name": name,
                "content": value,
                "ttl": 120,
            }))
            .send()
            .await
            .map_err(|e| AcmeError::Http(format!("Cloudflare DNS create: {e}")))?;

        let status = resp.status();
        let body: serde_json::Value = resp
            .json()
            .await
            .map_err(|e| AcmeError::Http(format!("Cloudflare DNS parse: {e}")))?;

        info!(
            status = %status,
            success = %body["success"],
            errors = %body["errors"],
            "Cloudflare API response for TXT record creation"
        );

        if !status.is_success() {
            return Err(AcmeError::ChallengeFailed(format!(
                "Cloudflare DNS TXT create failed ({status}): {body}"
            )));
        }

        if body["success"].as_bool() != Some(true) {
            return Err(AcmeError::ChallengeFailed(format!(
                "Cloudflare DNS TXT create returned success=false: errors={}, messages={}",
                body["errors"], body["messages"]
            )));
        }

        let record_id = body["result"]["id"]
            .as_str()
            .ok_or_else(|| {
                AcmeError::ChallengeFailed(format!("no record ID in Cloudflare response: {body}"))
            })?
            .to_string();

        info!(
            record_id = %record_id,
            txt_name = %name,
            txt_value = %value,
            "DNS TXT record created successfully"
        );
        Ok(record_id)
    }

    /// Verify DNS TXT record propagation by querying public DNS resolvers.
    ///
    /// Polls until the expected value appears in TXT records for `name`, or
    /// until the timeout is reached. Returns Ok(()) if the record is found,
    /// or Err if the timeout expires.
    async fn verify_dns_propagation(
        &self,
        name: &str,
        expected_value: &str,
    ) -> Result<(), AcmeError> {
        let timeout = std::time::Duration::from_secs(self.dns_propagation_timeout_secs);
        let poll_interval = std::time::Duration::from_secs(self.dns_poll_interval_secs);
        let start = std::time::Instant::now();

        // Use Google and Cloudflare public DNS to avoid local caching issues
        let resolver = TokioAsyncResolver::tokio(ResolverConfig::google(), {
            let mut opts = ResolverOpts::default();
            // Disable the resolver cache so each poll does a fresh lookup
            opts.cache_size = 0;
            opts
        });

        info!(
            txt_name = %name,
            expected_value = %expected_value,
            timeout_secs = self.dns_propagation_timeout_secs,
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
                    if records.iter().any(|r| r == expected_value) {
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
                        expected = %expected_value,
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
                    expected_value = %expected_value,
                    timeout_secs = self.dns_propagation_timeout_secs,
                    "DNS TXT propagation verification timed out"
                );
                return Err(AcmeError::ChallengeFailed(format!(
                    "DNS TXT record for {name} did not propagate within {}s",
                    self.dns_propagation_timeout_secs
                )));
            }

            tokio::time::sleep(poll_interval).await;
        }
    }

    /// Delete a DNS TXT record via Cloudflare API.
    async fn delete_dns_txt(&self, record_id: &str) {
        let url = format!(
            "{}/client/v4/zones/{}/dns_records/{record_id}",
            self.cf_api_base, self.cf_zone_id
        );

        match self
            .http
            .delete(&url)
            .bearer_auth(&self.cf_api_token)
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
impl AcmeClient for RealAcmeClient {
    async fn issue_certificate(
        &self,
        subdomain: &str,
        device_csr_der: Option<&[u8]>,
    ) -> Result<CertificateBundle, AcmeError> {
        // Wildcard cert: *.{subdomain}.{base_domain} covers all secret prefixes
        let fqdn = format!("*.{subdomain}.{}", self.base_domain);
        info!(fqdn = %fqdn, device_csr = device_csr_der.is_some(), "Starting ACME DNS-01 certificate issuance");

        // Either use the device-provided CSR or generate a fresh keypair + CSR.
        let (csr_der, private_key_pem) = if let Some(csr) = device_csr_der {
            (csr.to_vec(), None)
        } else {
            let key_pair = rcgen::KeyPair::generate()
                .map_err(|e| AcmeError::Http(format!("key generation failed: {e}")))?;
            let pem = key_pair.serialize_pem();

            let mut params = rcgen::CertificateParams::new(vec![fqdn.clone()])
                .map_err(|e| AcmeError::Http(format!("CSR params failed: {e}")))?;
            params.distinguished_name = rcgen::DistinguishedName::new();
            params
                .distinguished_name
                .push(rcgen::DnType::CommonName, &fqdn);

            let csr = params
                .serialize_request(&key_pair)
                .map_err(|e| AcmeError::Http(format!("CSR generation failed: {e}")))?
                .der()
                .to_vec();
            (csr, Some(pem))
        };

        // 1. Fetch directory
        let dir = get_directory(&self.http, &self.directory_url).await?;

        // 2. Create/retrieve account
        let (kid, nonce) = ensure_account(&self.http, &self.account_key, &dir).await?;

        // 3. Create order
        let (order, order_url, nonce) =
            create_order(&self.http, &self.account_key, &dir, &kid, &nonce, &fqdn).await?;

        if order.authorizations.is_empty() {
            return Err(AcmeError::ChallengeFailed(
                "order has no authorizations".to_string(),
            ));
        }

        // 4. Get authorization and find DNS-01 challenge
        let (resp, mut nonce) = acme_post(
            &self.http,
            &self.account_key,
            &order.authorizations[0],
            &nonce,
            "",
            Some(&kid),
        )
        .await?;

        let auth_status = resp.status();
        let auth_body = resp
            .text()
            .await
            .map_err(|e| AcmeError::Http(format!("read authorization body: {e}")))?;

        debug!(
            url = %order.authorizations[0],
            status = %auth_status,
            body = %auth_body,
            "Raw authorization response"
        );

        if !auth_status.is_success() {
            return Err(AcmeError::Http(format!(
                "fetch authorization failed ({auth_status}): {auth_body}"
            )));
        }

        let auth: AcmeAuthorization = serde_json::from_str(&auth_body).map_err(|e| {
            AcmeError::Http(format!(
                "parse authorization: {e}\n  response body: {auth_body}"
            ))
        })?;

        let dns_challenge = auth
            .challenges
            .iter()
            .find(|c| c.challenge_type == "dns-01")
            .ok_or_else(|| {
                AcmeError::ChallengeFailed("no dns-01 challenge in authorization".to_string())
            })?;

        // 5. Compute key authorization and DNS TXT value
        let thumbprint = jwk_thumbprint(&self.account_key);
        let key_auth = format!("{}.{thumbprint}", dns_challenge.token);
        let dns_value = base64url(&Sha256::digest(key_auth.as_bytes()));

        info!(
            token = %dns_challenge.token,
            thumbprint = %thumbprint,
            dns_value = %dns_value,
            "Computed DNS-01 challenge response"
        );

        // 6. Set DNS TXT record via Cloudflare
        // For wildcards (*.foo.example.com), the TXT record goes at
        // _acme-challenge.foo.example.com (strip the "*." prefix).
        let challenge_domain = fqdn.strip_prefix("*.").unwrap_or(&fqdn);
        let txt_name = format!("_acme-challenge.{challenge_domain}");
        info!(
            txt_name = %txt_name,
            txt_value = %dns_value,
            "Setting DNS TXT record for ACME challenge"
        );

        // Delete any stale TXT records at this name before creating a new one.
        // Cloudflare returns 400 "An identical record already exists." if we
        // try to POST a record that duplicates an existing (name, content)
        // pair. Stale records can be left behind by a previous challenge
        // attempt that failed before `delete_dns_txt` ran. See issue #56.
        self.cleanup_existing_txt_records(&txt_name).await?;

        let record_id = self.set_dns_txt(&txt_name, &dns_value).await?;

        // 7. Verify DNS propagation by polling public DNS resolvers
        if let Err(e) = self.verify_dns_propagation(&txt_name, &dns_value).await {
            // Clean up before returning
            self.delete_dns_txt(&record_id).await;
            return Err(e);
        }

        // 8. Respond to challenge (tell ACME server we are ready)
        let challenge_result = async {
            info!(
                challenge_url = %dns_challenge.url,
                "Notifying ACME server that DNS challenge is ready"
            );

            let (resp, new_nonce) = acme_post(
                &self.http,
                &self.account_key,
                &dns_challenge.url,
                &nonce,
                "{}",
                Some(&kid),
            )
            .await?;
            nonce = new_nonce;

            let status = resp.status();
            if !status.is_success() {
                let body = resp.text().await.unwrap_or_default();
                error!(
                    status = %status,
                    body = %body,
                    "ACME challenge response failed"
                );
                return Err(AcmeError::ChallengeFailed(format!(
                    "challenge response failed ({status}): {body}"
                )));
            }

            info!("Challenge response accepted, polling authorization...");

            // 9. Poll authorization until valid
            let (auth, new_nonce): (AcmeAuthorization, String) = poll_status(
                &self.http,
                &self.account_key,
                &order.authorizations[0],
                &kid,
                nonce,
                30,
            )
            .await?;
            nonce = new_nonce;

            if auth.status != "valid" {
                // Log the challenge details for debugging
                for c in &auth.challenges {
                    let error_detail = c
                        .error
                        .as_ref()
                        .map(|e| e.to_string())
                        .unwrap_or_else(|| "none".to_string());
                    error!(
                        challenge_type = %c.challenge_type,
                        challenge_status = c.status.as_deref().unwrap_or("unknown"),
                        challenge_url = %c.url,
                        error = %error_detail,
                        "Challenge detail in failed authorization"
                    );
                }
                return Err(AcmeError::ChallengeFailed(format!(
                    "authorization status: {}",
                    auth.status
                )));
            }

            info!("Authorization valid, finalizing order...");

            // 10. Finalize order with relay-generated CSR
            let csr_b64 = base64url(&csr_der);
            let finalize_payload = serde_json::json!({"csr": csr_b64}).to_string();

            let (resp, new_nonce) = acme_post(
                &self.http,
                &self.account_key,
                &order.finalize,
                &nonce,
                &finalize_payload,
                Some(&kid),
            )
            .await?;
            nonce = new_nonce;

            let status = resp.status();
            if !status.is_success() {
                let body = resp.text().await.unwrap_or_default();
                return Err(AcmeError::Http(format!(
                    "finalize failed ({status}): {body}"
                )));
            }

            // 11. Poll order until certificate URL is available
            let (final_order, new_nonce): (AcmeOrder, String) =
                poll_status(&self.http, &self.account_key, &order_url, &kid, nonce, 30).await?;
            nonce = new_nonce;

            let cert_url = final_order.certificate.ok_or_else(|| {
                AcmeError::Http(format!(
                    "order completed with status '{}' but no certificate URL",
                    final_order.status
                ))
            })?;

            // 12. Download certificate
            let (resp, _) = acme_post(
                &self.http,
                &self.account_key,
                &cert_url,
                &nonce,
                "",
                Some(&kid),
            )
            .await?;

            let status = resp.status();
            if !status.is_success() {
                let body = resp.text().await.unwrap_or_default();
                return Err(AcmeError::Http(format!(
                    "cert download failed ({status}): {body}"
                )));
            }

            let cert_pem = resp
                .text()
                .await
                .map_err(|e| AcmeError::Http(format!("read cert body: {e}")))?;

            info!(fqdn = %fqdn, cert_len = cert_pem.len(), "Certificate issued");
            Ok(CertificateBundle {
                cert_pem,
                private_key_pem: private_key_pem.clone(),
            })
        }
        .await;

        // Always clean up the DNS TXT record
        self.delete_dns_txt(&record_id).await;

        challenge_result
    }
}

#[cfg(test)]
mod tests {
    //! Cloudflare TXT record cleanup tests (issue #56).
    //!
    //! Cloudflare rejects creating a TXT record that is identical to an
    //! existing one with "An identical record already exists." We therefore
    //! list and delete any existing records at `_acme-challenge.{subdomain}`
    //! before creating a new one.

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

    /// Spawn an axum mock Cloudflare API server. Returns the base URL
    /// (e.g. "http://127.0.0.1:45617"). The caller retains the state
    /// handle (which is [`Clone`] because [`MockCfState`] wraps an `Arc`).
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

    fn test_client(base: String) -> RealAcmeClient {
        let mut c = RealAcmeClient::with_dns_settings(
            "unused".to_string(),
            "test-token".to_string(),
            "test-zone".to_string(),
            "rousecontext.com".to_string(),
            1,
            1,
        );
        c.cf_api_base = base;
        c
    }

    #[tokio::test]
    async fn list_txt_records_returns_empty_when_none_exist() {
        let state = MockCfState::new();
        let base = spawn_mock_cloudflare(state.clone()).await;
        let client = test_client(base);

        let ids = client
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
        let client = test_client(base);

        let ids = client
            .list_txt_records_by_name("_acme-challenge.abc.rousecontext.com")
            .await
            .expect("list should succeed");

        assert_eq!(ids.len(), 1);
    }

    #[tokio::test]
    async fn cleanup_existing_txt_records_deletes_all_matches() {
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
        let client = test_client(base);

        client
            .cleanup_existing_txt_records("_acme-challenge.abc.rousecontext.com")
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
    async fn cleanup_existing_txt_records_is_noop_when_none_exist() {
        let state = MockCfState::new();
        let base = spawn_mock_cloudflare(state.clone()).await;
        let client = test_client(base);

        client
            .cleanup_existing_txt_records("_acme-challenge.abc.rousecontext.com")
            .await
            .expect("cleanup should succeed when no records");

        let g = state.inner.lock().unwrap();
        assert_eq!(g.list_calls.len(), 1);
        assert_eq!(g.delete_calls.len(), 0);
    }

    #[tokio::test]
    async fn set_dns_txt_after_cleanup_succeeds_when_identical_record_existed() {
        // Seed an existing identical record so that a naive create would 400
        // with "An identical record already exists."
        let state = MockCfState::with_existing_record(
            "_acme-challenge.abc.rousecontext.com",
            "duplicate-value",
        )
        .enforce_identical_record_check();
        let base = spawn_mock_cloudflare(state.clone()).await;
        let client = test_client(base);

        // Baseline: without cleanup, create fails.
        let direct = client
            .set_dns_txt("_acme-challenge.abc.rousecontext.com", "duplicate-value")
            .await;
        assert!(
            direct.is_err(),
            "expected identical-record 400 from mock Cloudflare"
        );

        // With cleanup first, create succeeds.
        client
            .cleanup_existing_txt_records("_acme-challenge.abc.rousecontext.com")
            .await
            .expect("cleanup");

        let new_id = client
            .set_dns_txt("_acme-challenge.abc.rousecontext.com", "duplicate-value")
            .await
            .expect("create after cleanup should succeed");

        assert!(!new_id.is_empty());
        let g = state.inner.lock().unwrap();
        assert!(!g.delete_calls.is_empty(), "expected at least one delete");
    }
}
