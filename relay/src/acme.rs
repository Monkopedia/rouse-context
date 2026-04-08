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
use p256::pkcs8::{DecodePrivateKey, EncodePrivateKey};
use sha2::{Digest, Sha256};
use std::path::Path;
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

/// A certificate bundle containing the PEM cert chain and optionally the private key.
///
/// When the relay generates the keypair (legacy mode), `private_key_pem` contains
/// the key. When the device provides a CSR with its own public key, `private_key_pem`
/// is `None` because the device already holds the private key.
#[derive(Debug, Clone)]
pub struct CertificateBundle {
    /// PEM-encoded certificate chain (leaf + intermediates).
    pub cert_pem: String,
    /// PEM-encoded PKCS#8 private key for the certificate, if the relay generated it.
    pub private_key_pem: Option<String>,
}

#[async_trait]
pub trait AcmeClient: Send + Sync {
    /// Issue a certificate for the given subdomain via DNS-01 challenge.
    ///
    /// If `csr_der` is `Some`, the provided CSR (containing the device's public key)
    /// is submitted to the ACME server for finalization. The CSR must have the correct
    /// FQDN as its subject. No private key is returned in this case.
    ///
    /// If `csr_der` is `None`, the relay generates a fresh keypair and CSR internally,
    /// and returns the private key in the bundle.
    async fn issue_certificate(
        &self,
        subdomain: &str,
        csr_der: Option<&[u8]>,
    ) -> Result<CertificateBundle, AcmeError>;
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
#[allow(dead_code)]
struct AcmeOrder {
    status: String,
    authorizations: Vec<String>,
    finalize: String,
    #[serde(default)]
    certificate: Option<String>,
    /// Let's Encrypt includes these but we don't need them.
    #[serde(default)]
    expires: Option<String>,
    #[serde(default)]
    identifiers: Option<serde_json::Value>,
    #[serde(default)]
    not_before: Option<String>,
    #[serde(default)]
    not_after: Option<String>,
}

/// ACME identifier object (e.g. {"type": "dns", "value": "example.com"}).
#[derive(Debug, Clone, serde::Deserialize)]
#[allow(dead_code)]
struct AcmeIdentifier {
    #[serde(rename = "type")]
    identifier_type: String,
    value: String,
}

/// ACME authorization object.
#[derive(Debug, Clone, serde::Deserialize)]
#[allow(dead_code)]
struct AcmeAuthorization {
    status: String,
    #[serde(default)]
    challenges: Vec<AcmeChallenge>,
    /// The identifier this authorization is for.
    #[serde(default)]
    identifier: Option<AcmeIdentifier>,
    /// Expiry timestamp (ISO 8601).
    #[serde(default)]
    expires: Option<String>,
    /// Whether this is a wildcard authorization.
    #[serde(default)]
    wildcard: Option<bool>,
}

/// ACME challenge object.
#[derive(Debug, Clone, serde::Deserialize)]
#[allow(dead_code)]
struct AcmeChallenge {
    #[serde(rename = "type")]
    challenge_type: String,
    url: String,
    token: String,
    #[serde(default)]
    status: Option<String>,
    /// Error detail from ACME server when challenge fails.
    #[serde(default)]
    error: Option<serde_json::Value>,
    /// Timestamp when challenge was validated (ISO 8601).
    #[serde(default)]
    validated: Option<String>,
}

/// Load an ECDSA P-256 account key from a PEM file, or generate a new one and
/// save it. This ensures the relay reuses the same ACME account across restarts.
fn load_or_create_account_key(key_path: &Path) -> SigningKey {
    if key_path.exists() {
        let pem = std::fs::read_to_string(key_path).expect("failed to read ACME account key file");
        let key = SigningKey::from_pkcs8_pem(&pem).expect("failed to parse ACME account key PEM");
        info!(?key_path, "Loaded existing ACME account key");
        key
    } else {
        let key = SigningKey::random(&mut rand::thread_rng());
        let pem = key
            .to_pkcs8_pem(p256::pkcs8::LineEnding::LF)
            .expect("failed to encode ACME account key as PEM");

        // Ensure parent directory exists.
        if let Some(parent) = key_path.parent() {
            std::fs::create_dir_all(parent)
                .expect("failed to create directory for ACME account key");
        }
        std::fs::write(key_path, pem.as_bytes()).expect("failed to write ACME account key to disk");
        info!(?key_path, "Generated and saved new ACME account key");
        key
    }
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
        let account_key = SigningKey::random(&mut rand::thread_rng());
        info!("Generated fresh ACME account key (no key path configured)");

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

    /// Create a new ACME client that persists its account key to disk.
    ///
    /// If `account_key_path` points to an existing PEM file, the key is loaded
    /// from it. Otherwise a new ECDSA P-256 key is generated and saved there.
    /// This keeps the same ACME account across relay restarts, avoiding
    /// "no such authorization" errors from orphaned pending challenges.
    pub fn with_persistent_key(
        directory_url: String,
        cf_api_token: String,
        cf_zone_id: String,
        base_domain: String,
        dns_propagation_timeout_secs: u64,
        dns_poll_interval_secs: u64,
        account_key_path: &Path,
    ) -> Self {
        let account_key = load_or_create_account_key(account_key_path);

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

        let body = resp
            .text()
            .await
            .map_err(|e| AcmeError::Http(format!("read order body: {e}")))?;

        debug!(order_url = %order_url, status = %status, body = %body, "Raw order response");

        if !status.is_success() {
            return Err(AcmeError::Http(format!(
                "newOrder failed ({status}): {body}"
            )));
        }

        let order: AcmeOrder = serde_json::from_str(&body)
            .map_err(|e| AcmeError::Http(format!("parse order: {e}\n  response body: {body}")))?;

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
        // For wildcards (*.foo.example.com), the TXT record goes at
        // _acme-challenge.foo.example.com (strip the "*." prefix).
        let challenge_domain = fqdn.strip_prefix("*.").unwrap_or(&fqdn);
        let txt_name = format!("_acme-challenge.{challenge_domain}");
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
    use super::*;

    /// Real Let's Encrypt authorization response with all fields present.
    const LETSENCRYPT_AUTHORIZATION: &str = r#"{
        "status": "pending",
        "expires": "2026-04-11T12:00:00Z",
        "identifier": {
            "type": "dns",
            "value": "abc123.rousecontext.com"
        },
        "challenges": [
            {
                "type": "http-01",
                "status": "pending",
                "url": "https://acme-v02.api.letsencrypt.org/acme/chall-v3/123/http",
                "token": "http-token-abc"
            },
            {
                "type": "dns-01",
                "status": "pending",
                "url": "https://acme-v02.api.letsencrypt.org/acme/chall-v3/123/dns",
                "token": "dns-token-xyz"
            },
            {
                "type": "tls-alpn-01",
                "status": "pending",
                "url": "https://acme-v02.api.letsencrypt.org/acme/chall-v3/123/tls",
                "token": "tls-token-def"
            }
        ]
    }"#;

    /// Authorization with a validated challenge (includes extra `validated` field).
    const VALIDATED_AUTHORIZATION: &str = r#"{
        "status": "valid",
        "expires": "2026-04-11T12:00:00Z",
        "identifier": {
            "type": "dns",
            "value": "abc123.rousecontext.com"
        },
        "challenges": [
            {
                "type": "dns-01",
                "status": "valid",
                "url": "https://acme-v02.api.letsencrypt.org/acme/chall-v3/123/dns",
                "token": "dns-token-xyz",
                "validated": "2026-04-04T10:30:00Z"
            }
        ]
    }"#;

    /// Minimal authorization with only required fields.
    const MINIMAL_AUTHORIZATION: &str = r#"{
        "status": "pending",
        "challenges": [
            {
                "type": "dns-01",
                "url": "https://acme-v02.api.letsencrypt.org/acme/chall-v3/456/dns",
                "token": "minimal-token"
            }
        ]
    }"#;

    /// Authorization with challenge error detail.
    const FAILED_AUTHORIZATION: &str = r#"{
        "status": "invalid",
        "identifier": {
            "type": "dns",
            "value": "abc123.rousecontext.com"
        },
        "challenges": [
            {
                "type": "dns-01",
                "status": "invalid",
                "url": "https://acme-v02.api.letsencrypt.org/acme/chall-v3/789/dns",
                "token": "bad-token",
                "error": {
                    "type": "urn:ietf:params:acme:error:dns",
                    "detail": "DNS problem: NXDOMAIN looking up TXT for _acme-challenge.abc123.rousecontext.com",
                    "status": 400
                }
            }
        ]
    }"#;

    /// Real Let's Encrypt order response.
    const LETSENCRYPT_ORDER: &str = r#"{
        "status": "pending",
        "expires": "2026-04-11T12:00:00Z",
        "identifiers": [
            {"type": "dns", "value": "abc123.rousecontext.com"}
        ],
        "authorizations": [
            "https://acme-v02.api.letsencrypt.org/acme/authz-v3/123"
        ],
        "finalize": "https://acme-v02.api.letsencrypt.org/acme/finalize/456/789"
    }"#;

    /// Order with certificate URL (ready to download).
    const COMPLETED_ORDER: &str = r#"{
        "status": "valid",
        "expires": "2026-04-11T12:00:00Z",
        "identifiers": [
            {"type": "dns", "value": "abc123.rousecontext.com"}
        ],
        "authorizations": [
            "https://acme-v02.api.letsencrypt.org/acme/authz-v3/123"
        ],
        "finalize": "https://acme-v02.api.letsencrypt.org/acme/finalize/456/789",
        "certificate": "https://acme-v02.api.letsencrypt.org/acme/cert/abc123"
    }"#;

    #[test]
    fn parse_letsencrypt_authorization() {
        let auth: AcmeAuthorization =
            serde_json::from_str(LETSENCRYPT_AUTHORIZATION).expect("should parse");
        assert_eq!(auth.status, "pending");
        assert_eq!(auth.challenges.len(), 3);
        assert_eq!(
            auth.identifier.as_ref().unwrap().value,
            "abc123.rousecontext.com"
        );
        assert_eq!(auth.expires.as_deref(), Some("2026-04-11T12:00:00Z"));

        let dns = auth
            .challenges
            .iter()
            .find(|c| c.challenge_type == "dns-01")
            .expect("should have dns-01");
        assert_eq!(dns.token, "dns-token-xyz");
        assert_eq!(dns.status.as_deref(), Some("pending"));
    }

    #[test]
    fn parse_validated_authorization() {
        let auth: AcmeAuthorization =
            serde_json::from_str(VALIDATED_AUTHORIZATION).expect("should parse");
        assert_eq!(auth.status, "valid");

        let dns = &auth.challenges[0];
        assert_eq!(dns.status.as_deref(), Some("valid"));
        assert_eq!(dns.validated.as_deref(), Some("2026-04-04T10:30:00Z"));
    }

    #[test]
    fn parse_minimal_authorization() {
        let auth: AcmeAuthorization =
            serde_json::from_str(MINIMAL_AUTHORIZATION).expect("should parse");
        assert_eq!(auth.status, "pending");
        assert!(auth.identifier.is_none());
        assert!(auth.expires.is_none());
        assert!(auth.wildcard.is_none());
        assert_eq!(auth.challenges[0].status, None);
    }

    #[test]
    fn parse_failed_authorization_with_error() {
        let auth: AcmeAuthorization =
            serde_json::from_str(FAILED_AUTHORIZATION).expect("should parse");
        assert_eq!(auth.status, "invalid");

        let dns = &auth.challenges[0];
        assert!(dns.error.is_some());
        let err = dns.error.as_ref().unwrap();
        assert!(err["detail"].as_str().unwrap().contains("NXDOMAIN"));
    }

    #[test]
    fn parse_letsencrypt_order() {
        let order: AcmeOrder = serde_json::from_str(LETSENCRYPT_ORDER).expect("should parse");
        assert_eq!(order.status, "pending");
        assert_eq!(order.authorizations.len(), 1);
        assert!(order.certificate.is_none());
        assert!(order.expires.is_some());
        assert!(order.identifiers.is_some());
    }

    #[test]
    fn parse_completed_order() {
        let order: AcmeOrder = serde_json::from_str(COMPLETED_ORDER).expect("should parse");
        assert_eq!(order.status, "valid");
        assert_eq!(
            order.certificate.as_deref(),
            Some("https://acme-v02.api.letsencrypt.org/acme/cert/abc123")
        );
    }

    /// Ensure we tolerate unknown fields from future ACME extensions.
    #[test]
    fn parse_authorization_with_unknown_fields() {
        let json = r#"{
            "status": "pending",
            "challenges": [],
            "someNewField": true,
            "anotherFutureField": {"nested": "value"}
        }"#;
        let auth: AcmeAuthorization =
            serde_json::from_str(json).expect("should ignore unknown fields");
        assert_eq!(auth.status, "pending");
    }

    #[test]
    fn parse_challenge_with_unknown_fields() {
        let json = r#"{
            "type": "dns-01",
            "url": "https://example.com/chall",
            "token": "abc",
            "status": "pending",
            "futureField": 42
        }"#;
        let challenge: AcmeChallenge =
            serde_json::from_str(json).expect("should ignore unknown fields");
        assert_eq!(challenge.challenge_type, "dns-01");
    }

    #[test]
    fn account_key_created_when_file_missing() {
        let dir = tempfile::tempdir().unwrap();
        let key_path = dir.path().join("account_key.pem");
        assert!(!key_path.exists());

        let key = load_or_create_account_key(&key_path);
        assert!(key_path.exists());

        // Loading again should produce the same key.
        let key2 = load_or_create_account_key(&key_path);
        assert_eq!(key.to_bytes(), key2.to_bytes());
    }

    #[test]
    fn account_key_loaded_from_existing_file() {
        let dir = tempfile::tempdir().unwrap();
        let key_path = dir.path().join("account_key.pem");

        // Write a key, then load it back.
        let original = SigningKey::random(&mut rand::thread_rng());
        let pem = original.to_pkcs8_pem(p256::pkcs8::LineEnding::LF).unwrap();
        std::fs::write(&key_path, pem.as_bytes()).unwrap();

        let loaded = load_or_create_account_key(&key_path);
        assert_eq!(original.to_bytes(), loaded.to_bytes());
    }

    #[test]
    fn account_key_creates_parent_directories() {
        let dir = tempfile::tempdir().unwrap();
        let key_path = dir.path().join("nested").join("dir").join("key.pem");
        assert!(!key_path.parent().unwrap().exists());

        let _key = load_or_create_account_key(&key_path);
        assert!(key_path.exists());
    }
}
