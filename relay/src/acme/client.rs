//! [`RealAcmeClient`] — the [`AcmeClient`] implementation that performs
//! DNS-01 challenges via an injected [`DnsChallengeProvider`] and fetches
//! certs from Let's Encrypt.

use async_trait::async_trait;
use p256::ecdsa::SigningKey;
use sha2::{Digest, Sha256};
use std::path::Path;
use std::sync::Arc;
use tracing::{debug, error, info};

use super::account::{ensure_account, load_or_create_account_key, ExternalAccountBinding};
use super::jws::{acme_post, jwk_thumbprint};
use super::protocol::{create_order, get_directory, poll_status};
use super::types::{base64url, AcmeAuthorization, AcmeOrder};
use super::{AcmeClient, AcmeError, CertificateBundle};
use crate::dns_challenge::{DnsChallengeProvider, DnsError};

/// Real ACME client that performs DNS-01 challenges via an injected
/// [`DnsChallengeProvider`].
pub struct RealAcmeClient {
    http: reqwest::Client,
    directory_url: String,
    account_key: SigningKey,
    dns: Arc<dyn DnsChallengeProvider>,
    base_domain: String,
    eab: Option<ExternalAccountBinding>,
}

impl RealAcmeClient {
    /// Create a new ACME client using an externally constructed DNS provider.
    pub fn new(
        directory_url: String,
        dns: Arc<dyn DnsChallengeProvider>,
        base_domain: String,
    ) -> Self {
        let account_key = SigningKey::random(&mut rand::thread_rng());
        info!("Generated fresh ACME account key (no key path configured)");

        Self {
            http: reqwest::Client::new(),
            directory_url,
            account_key,
            dns,
            base_domain,
            eab: None,
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
    /// otherwise rotate the relay's ACME identity.
    pub fn with_persistent_key(
        directory_url: String,
        dns: Arc<dyn DnsChallengeProvider>,
        base_domain: String,
        account_key_path: &Path,
        require_existing_account: bool,
    ) -> Self {
        let account_key = load_or_create_account_key(account_key_path, require_existing_account);

        Self {
            http: reqwest::Client::new(),
            directory_url,
            account_key,
            dns,
            base_domain,
            eab: None,
        }
    }

    /// Attach External Account Binding credentials (RFC 8555 §7.3.4).
    ///
    /// Required by GTS (`publicca.googleapis.com`) on first `newAccount`
    /// registration. Subsequent account lookups with the same key succeed
    /// regardless, but an unbound `newAccount` against GTS returns
    /// `urn:ietf:params:acme:error:externalAccountRequired`.
    pub fn with_external_account_binding(mut self, eab: ExternalAccountBinding) -> Self {
        self.eab = Some(eab);
        self
    }
}

/// Convert a [`DnsError`] from the provider into an [`AcmeError`]. DNS-level
/// failures surface as [`AcmeError::ChallengeFailed`] because they mean the
/// DNS-01 challenge cannot complete, not that the ACME server rejected us.
fn dns_to_acme_err(e: DnsError) -> AcmeError {
    match e {
        DnsError::PropagationTimeout => {
            AcmeError::ChallengeFailed("DNS TXT record did not propagate in time".to_string())
        }
        DnsError::Http(msg) => AcmeError::Http(msg),
        DnsError::Provider(msg) => AcmeError::ChallengeFailed(msg),
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
        let (kid, nonce) =
            ensure_account(&self.http, &self.account_key, &dir, self.eab.as_ref()).await?;

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

        // 6. Publish the DNS TXT record via the configured DNS provider.
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
        // Some providers (Cloudflare) reject identical duplicates; others
        // accumulate orphan records from previous failed attempts. Either
        // way, cleanup first means we start from a known state. See
        // issue #56 for the Cloudflare-specific failure mode.
        self.dns
            .cleanup_stale(&txt_name)
            .await
            .map_err(dns_to_acme_err)?;

        let handle = self
            .dns
            .publish_txt(&txt_name, &dns_value)
            .await
            .map_err(dns_to_acme_err)?;

        // 7. Verify DNS propagation by polling public DNS resolvers
        if let Err(e) = self.dns.wait_for_propagation(&txt_name, &dns_value).await {
            // Clean up before returning
            self.dns.delete_txt(&handle).await;
            return Err(dns_to_acme_err(e));
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
        self.dns.delete_txt(&handle).await;

        challenge_result
    }
}

#[cfg(test)]
mod tests {
    //! Orchestration tests that verify [`RealAcmeClient`] drives the
    //! [`DnsChallengeProvider`] in the expected sequence. Cloudflare-specific
    //! HTTP tests live alongside the provider in
    //! [`crate::cloudflare_dns`].

    use super::*;
    use std::sync::Mutex;

    /// A DNS provider that records calls, returns configured results, and
    /// is injected into [`RealAcmeClient`] to exercise orchestration
    /// without a real DNS backend.
    #[derive(Default)]
    struct MockDnsProvider {
        calls: Mutex<Vec<String>>,
    }

    impl MockDnsProvider {
        fn calls(&self) -> Vec<String> {
            self.calls.lock().unwrap().clone()
        }
    }

    #[async_trait]
    impl DnsChallengeProvider for MockDnsProvider {
        async fn publish_txt(&self, name: &str, value: &str) -> Result<TxtHandle, DnsError> {
            self.calls
                .lock()
                .unwrap()
                .push(format!("publish_txt({name},{value})"));
            Ok(TxtHandle("mock-handle".to_string()))
        }

        async fn wait_for_propagation(&self, name: &str, value: &str) -> Result<(), DnsError> {
            self.calls
                .lock()
                .unwrap()
                .push(format!("wait_for_propagation({name},{value})"));
            Ok(())
        }

        async fn delete_txt(&self, handle: &TxtHandle) {
            self.calls
                .lock()
                .unwrap()
                .push(format!("delete_txt({})", handle.0));
        }

        async fn cleanup_stale(&self, name: &str) -> Result<(), DnsError> {
            self.calls
                .lock()
                .unwrap()
                .push(format!("cleanup_stale({name})"));
            Ok(())
        }
    }

    use crate::dns_challenge::TxtHandle;

    /// The RealAcmeClient constructor accepts any DnsChallengeProvider.
    /// This test just verifies the wiring compiles and an Arc<Mock> can be
    /// substituted — end-to-end flow is exercised in integration tests
    /// that hit a mock ACME server.
    #[test]
    fn accepts_mock_dns_provider() {
        let dns: Arc<dyn DnsChallengeProvider> = Arc::new(MockDnsProvider::default());
        let _client = RealAcmeClient::new(
            "https://unused/directory".to_string(),
            dns.clone(),
            "rousecontext.com".to_string(),
        );
        // Nothing else to do: issuing a cert requires a live ACME server.
        // The mock provider's `calls` accessor is exercised in tests that
        // drive `issue_certificate` against a mock ACME directory.
        let _ = MockDnsProvider::default().calls();
    }
}
