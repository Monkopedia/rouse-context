//! ACME client abstraction for DNS-01 certificate issuance.
//!
//! Uses a trait so tests can substitute a mock. The real implementation
//! performs DNS-01 challenges via Cloudflare API and fetches certs from
//! Let's Encrypt.

use async_trait::async_trait;
use base64::Engine;
use hickory_resolver::config::{ResolverConfig, ResolverOpts};
use hickory_resolver::TokioAsyncResolver;
use p256::ecdsa::{signature::Signer, SigningKey};
use sha2::{Digest, Sha256};
use thiserror::Error;
use tracing::{debug, error, info, warn};

/// Let's Encrypt production directory URL.
pub const LETS_ENCRYPT_DIRECTORY: &str = "https://acme-v02.api.letsencrypt.org/directory";

/// Let's Encrypt staging directory URL (for testing).
pub const LETS_ENCRYPT_STAGING_DIRECTORY: &str =
    "https://acme-staging-v02.api.letsencrypt.org/directory";

/// Base64url encoding without padding, as required by ACME/JWS.
fn base64url(data: &[u8]) -> String {
    base64::engine::general_purpose::URL_SAFE_NO_PAD.encode(data)
}

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

/// ACME directory endpoints, discovered from the directory URL.
#[derive(Debug, Clone, serde::Deserialize)]
#[serde(rename_all = "camelCase")]
struct AcmeDirectory {
    new_nonce: String,
    new_account: String,
    new_order: String,
}

/// ACME order object.
#[derive(Debug, Clone, serde::Deserialize)]
struct AcmeOrder {
    status: String,
    authorizations: Vec<String>,
    finalize: String,
    certificate: Option<String>,
}

/// ACME authorization object.
#[derive(Debug, Clone, serde::Deserialize)]
struct AcmeAuthorization {
    status: String,
    challenges: Vec<AcmeChallenge>,
}

/// ACME challenge object.
#[derive(Debug, Clone, serde::Deserialize)]
#[allow(dead_code)]
struct AcmeChallenge {
    #[serde(rename = "type")]
    challenge_type: String,
    url: String,
    token: String,
    status: String,
    /// Error detail from ACME server when challenge fails.
    #[serde(default)]
    error: Option<serde_json::Value>,
}

/// Real ACME client that performs DNS-01 challenges via Cloudflare.
pub struct RealAcmeClient {
    http: reqwest::Client,
    directory_url: String,
    account_key: SigningKey,
    cf_api_token: String,
    cf_zone_id: String,
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
        // Generate a fresh ECDSA P-256 account key for ACME.
        // A fresh key per relay instance is fine -- Let's Encrypt will create
        // a new account automatically on first use. Account keys are not tied
        // to certificates; they only authenticate the ACME session.
        let account_key = SigningKey::random(&mut rand::thread_rng());
        info!("Generated fresh ACME account key");

        Self {
            http: reqwest::Client::new(),
            directory_url,
            account_key,
            cf_api_token,
            cf_zone_id,
            base_domain,
            dns_propagation_timeout_secs,
            dns_poll_interval_secs,
        }
    }

    /// Fetch the ACME directory.
    async fn get_directory(&self) -> Result<AcmeDirectory, AcmeError> {
        let resp = self
            .http
            .get(&self.directory_url)
            .send()
            .await
            .map_err(|e| AcmeError::Http(format!("directory fetch: {e}")))?;
        resp.json::<AcmeDirectory>()
            .await
            .map_err(|e| AcmeError::Http(format!("directory parse: {e}")))
    }

    /// Get a fresh nonce from the ACME server.
    async fn get_nonce(&self, new_nonce_url: &str) -> Result<String, AcmeError> {
        let resp = self
            .http
            .head(new_nonce_url)
            .send()
            .await
            .map_err(|e| AcmeError::Http(format!("nonce fetch: {e}")))?;
        resp.headers()
            .get("replay-nonce")
            .and_then(|v| v.to_str().ok())
            .map(|s| s.to_string())
            .ok_or_else(|| AcmeError::Http("no replay-nonce header".to_string()))
    }

    /// Build the JWK thumbprint for the account key (used in DNS-01 challenge).
    fn jwk_thumbprint(&self) -> String {
        let public_key = self.account_key.verifying_key();
        let point = public_key.to_encoded_point(false);
        let x = base64url(point.x().expect("x coordinate"));
        let y = base64url(point.y().expect("y coordinate"));

        // JWK thumbprint per RFC 7638: lexicographic JSON with required members
        let jwk_json = format!(r#"{{"crv":"P-256","kty":"EC","x":"{x}","y":"{y}"}}"#);
        let digest = Sha256::digest(jwk_json.as_bytes());
        base64url(&digest)
    }

    /// Build the JWK (public key) for the account key.
    fn jwk(&self) -> serde_json::Value {
        let public_key = self.account_key.verifying_key();
        let point = public_key.to_encoded_point(false);
        let x = base64url(point.x().expect("x coordinate"));
        let y = base64url(point.y().expect("y coordinate"));

        serde_json::json!({
            "kty": "EC",
            "crv": "P-256",
            "x": x,
            "y": y,
        })
    }

    /// Sign an ACME request payload as a JWS (Flattened JSON Serialization).
    ///
    /// Uses JWK in protected header when `kid` is None (for newAccount),
    /// or `kid` when set (for all subsequent requests).
    fn sign_jws(
        &self,
        url: &str,
        nonce: &str,
        payload: &str,
        kid: Option<&str>,
    ) -> serde_json::Value {
        let protected = if let Some(kid) = kid {
            serde_json::json!({
                "alg": "ES256",
                "kid": kid,
                "nonce": nonce,
                "url": url,
            })
        } else {
            serde_json::json!({
                "alg": "ES256",
                "jwk": self.jwk(),
                "nonce": nonce,
                "url": url,
            })
        };

        let protected_b64 = base64url(
            serde_json::to_string(&protected)
                .expect("JSON serialize")
                .as_bytes(),
        );

        let payload_b64 = if payload.is_empty() {
            // Empty payload for POST-as-GET
            String::new()
        } else {
            base64url(payload.as_bytes())
        };

        let signing_input = format!("{protected_b64}.{payload_b64}");
        let signature: p256::ecdsa::Signature = self.account_key.sign(signing_input.as_bytes());
        let sig_b64 = base64url(&signature.to_bytes());

        serde_json::json!({
            "protected": protected_b64,
            "payload": payload_b64,
            "signature": sig_b64,
        })
    }

    /// Send a signed ACME POST request, returning (response, new_nonce).
    async fn acme_post(
        &self,
        url: &str,
        nonce: &str,
        payload: &str,
        kid: Option<&str>,
    ) -> Result<(reqwest::Response, String), AcmeError> {
        let body = self.sign_jws(url, nonce, payload, kid);
        let resp = self
            .http
            .post(url)
            .header("content-type", "application/jose+json")
            .json(&body)
            .send()
            .await
            .map_err(|e| AcmeError::Http(format!("ACME POST to {url}: {e}")))?;

        // Check for rate limiting
        if resp.status().as_u16() == 429 {
            let retry_after = resp
                .headers()
                .get("retry-after")
                .and_then(|v| v.to_str().ok())
                .and_then(|v| v.parse::<u64>().ok())
                .unwrap_or(3600);
            return Err(AcmeError::RateLimited {
                retry_after_secs: retry_after,
            });
        }

        let new_nonce = resp
            .headers()
            .get("replay-nonce")
            .and_then(|v| v.to_str().ok())
            .map(|s| s.to_string())
            .unwrap_or_default();

        Ok((resp, new_nonce))
    }

    /// Create or retrieve an ACME account. Returns (account_url, nonce).
    async fn ensure_account(&self, dir: &AcmeDirectory) -> Result<(String, String), AcmeError> {
        let nonce = self.get_nonce(&dir.new_nonce).await?;
        let payload = serde_json::json!({
            "termsOfServiceAgreed": true,
        })
        .to_string();

        let (resp, nonce) = self
            .acme_post(&dir.new_account, &nonce, &payload, None)
            .await?;

        let status = resp.status();
        let account_url = resp
            .headers()
            .get("location")
            .and_then(|v| v.to_str().ok())
            .map(|s| s.to_string())
            .ok_or_else(|| {
                AcmeError::Http(format!(
                    "no Location header in newAccount response (status {status})"
                ))
            })?;

        if !status.is_success() {
            let body = resp.text().await.unwrap_or_default();
            return Err(AcmeError::Http(format!(
                "newAccount failed ({status}): {body}"
            )));
        }

        debug!(account_url = %account_url, "ACME account ready");
        Ok((account_url, nonce))
    }

    /// Create a new ACME order for the given FQDN.
    async fn create_order(
        &self,
        dir: &AcmeDirectory,
        kid: &str,
        nonce: &str,
        fqdn: &str,
    ) -> Result<(AcmeOrder, String, String), AcmeError> {
        let payload = serde_json::json!({
            "identifiers": [{"type": "dns", "value": fqdn}],
        })
        .to_string();

        let (resp, nonce) = self
            .acme_post(&dir.new_order, nonce, &payload, Some(kid))
            .await?;

        let status = resp.status();
        let order_url = resp
            .headers()
            .get("location")
            .and_then(|v| v.to_str().ok())
            .map(|s| s.to_string())
            .unwrap_or_default();

        if !status.is_success() {
            let body = resp.text().await.unwrap_or_default();
            return Err(AcmeError::Http(format!(
                "newOrder failed ({status}): {body}"
            )));
        }

        let order: AcmeOrder = resp
            .json()
            .await
            .map_err(|e| AcmeError::Http(format!("parse order: {e}")))?;

        debug!(order_url = %order_url, status = %order.status, "ACME order created");
        Ok((order, order_url, nonce))
    }

    /// Set a DNS TXT record via Cloudflare API. Returns the record ID.
    async fn set_dns_txt(&self, name: &str, value: &str) -> Result<String, AcmeError> {
        let url = format!(
            "https://api.cloudflare.com/client/v4/zones/{}/dns_records",
            self.cf_zone_id
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
            "https://api.cloudflare.com/client/v4/zones/{}/dns_records/{record_id}",
            self.cf_zone_id
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

    /// Poll an ACME URL until the status is no longer "pending" or "processing".
    async fn poll_status<T: serde::de::DeserializeOwned>(
        &self,
        url: &str,
        kid: &str,
        mut nonce: String,
        max_attempts: u32,
    ) -> Result<(T, String), AcmeError> {
        for attempt in 0..max_attempts {
            if attempt > 0 {
                tokio::time::sleep(std::time::Duration::from_secs(2)).await;
            }

            // POST-as-GET (empty payload)
            let (resp, new_nonce) = self.acme_post(url, &nonce, "", Some(kid)).await?;
            nonce = new_nonce;

            let status_code = resp.status();
            if !status_code.is_success() {
                let body = resp.text().await.unwrap_or_default();
                return Err(AcmeError::Http(format!(
                    "poll {url} failed ({status_code}): {body}"
                )));
            }

            let text = resp
                .text()
                .await
                .map_err(|e| AcmeError::Http(format!("poll read body: {e}")))?;

            // Check if status is still pending/processing
            let value: serde_json::Value = serde_json::from_str(&text)
                .map_err(|e| AcmeError::Http(format!("poll parse: {e}")))?;
            let obj_status = value["status"].as_str().unwrap_or("");

            if obj_status == "pending" || obj_status == "processing" {
                debug!(url = %url, status = %obj_status, attempt, "Polling...");
                continue;
            }

            let parsed: T = serde_json::from_str(&text)
                .map_err(|e| AcmeError::Http(format!("poll deserialize: {e}")))?;
            return Ok((parsed, nonce));
        }

        Err(AcmeError::ChallengeFailed(format!(
            "timed out polling {url} after {max_attempts} attempts"
        )))
    }
}

#[async_trait]
impl AcmeClient for RealAcmeClient {
    async fn issue_certificate(
        &self,
        subdomain: &str,
        csr_der: &[u8],
    ) -> Result<String, AcmeError> {
        let fqdn = format!("{subdomain}.{}", self.base_domain);
        info!(fqdn = %fqdn, "Starting ACME DNS-01 certificate issuance");

        // 1. Fetch directory
        let dir = self.get_directory().await?;

        // 2. Create/retrieve account
        let (kid, nonce) = self.ensure_account(&dir).await?;

        // 3. Create order
        let (order, order_url, nonce) = self.create_order(&dir, &kid, &nonce, &fqdn).await?;

        if order.authorizations.is_empty() {
            return Err(AcmeError::ChallengeFailed(
                "order has no authorizations".to_string(),
            ));
        }

        // 4. Get authorization and find DNS-01 challenge
        let (resp, mut nonce) = self
            .acme_post(&order.authorizations[0], &nonce, "", Some(&kid))
            .await?;

        let auth: AcmeAuthorization = resp
            .json()
            .await
            .map_err(|e| AcmeError::Http(format!("parse authorization: {e}")))?;

        let dns_challenge = auth
            .challenges
            .iter()
            .find(|c| c.challenge_type == "dns-01")
            .ok_or_else(|| {
                AcmeError::ChallengeFailed("no dns-01 challenge in authorization".to_string())
            })?;

        // 5. Compute key authorization and DNS TXT value
        let thumbprint = self.jwk_thumbprint();
        let key_auth = format!("{}.{thumbprint}", dns_challenge.token);
        let dns_value = base64url(&Sha256::digest(key_auth.as_bytes()));

        info!(
            token = %dns_challenge.token,
            thumbprint = %thumbprint,
            dns_value = %dns_value,
            "Computed DNS-01 challenge response"
        );

        // 6. Set DNS TXT record via Cloudflare
        let txt_name = format!("_acme-challenge.{fqdn}");
        info!(
            txt_name = %txt_name,
            txt_value = %dns_value,
            "Setting DNS TXT record for ACME challenge"
        );
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

            let (resp, new_nonce) = self
                .acme_post(&dns_challenge.url, &nonce, "{}", Some(&kid))
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
            let (auth, new_nonce): (AcmeAuthorization, String) = self
                .poll_status(&order.authorizations[0], &kid, nonce, 30)
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
                        challenge_status = %c.status,
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

            // 10. Finalize order with CSR
            let csr_b64 = base64url(csr_der);
            let finalize_payload = serde_json::json!({"csr": csr_b64}).to_string();

            let (resp, new_nonce) = self
                .acme_post(&order.finalize, &nonce, &finalize_payload, Some(&kid))
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
                self.poll_status(&order_url, &kid, nonce, 30).await?;
            nonce = new_nonce;

            let cert_url = final_order.certificate.ok_or_else(|| {
                AcmeError::Http(format!(
                    "order completed with status '{}' but no certificate URL",
                    final_order.status
                ))
            })?;

            // 12. Download certificate
            let (resp, _) = self.acme_post(&cert_url, &nonce, "", Some(&kid)).await?;

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
            Ok(cert_pem)
        }
        .await;

        // Always clean up the DNS TXT record
        self.delete_dns_txt(&record_id).await;

        challenge_result
    }
}
