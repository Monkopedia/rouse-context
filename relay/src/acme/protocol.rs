//! ACME protocol flow: directory discovery, order creation, status polling,
//! challenge response, finalization, and certificate download.
//!
//! Functions here take a [`reqwest::Client`] + [`SigningKey`] and perform
//! [`acme_post`] calls. They know nothing about DNS providers.

use p256::ecdsa::SigningKey;
use tracing::debug;

use super::jws::acme_post;
use super::types::{AcmeDirectory, AcmeOrder};
use super::AcmeError;

/// Fetch the ACME directory.
pub(super) async fn get_directory(
    http: &reqwest::Client,
    directory_url: &str,
) -> Result<AcmeDirectory, AcmeError> {
    let resp = http
        .get(directory_url)
        .send()
        .await
        .map_err(|e| AcmeError::Http(format!("directory fetch: {e}")))?;
    resp.json::<AcmeDirectory>()
        .await
        .map_err(|e| AcmeError::Http(format!("directory parse: {e}")))
}

/// Create a new ACME order for the given FQDN. Returns (order, order_url, nonce).
pub(super) async fn create_order(
    http: &reqwest::Client,
    account_key: &SigningKey,
    dir: &AcmeDirectory,
    kid: &str,
    nonce: &str,
    fqdn: &str,
) -> Result<(AcmeOrder, String, String), AcmeError> {
    let payload = serde_json::json!({
        "identifiers": [{"type": "dns", "value": fqdn}],
    })
    .to_string();

    let (resp, nonce) = acme_post(
        http,
        account_key,
        &dir.new_order,
        nonce,
        &payload,
        Some(kid),
    )
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

/// Poll an ACME URL until the status is no longer "pending" or "processing".
pub(super) async fn poll_status<T: serde::de::DeserializeOwned>(
    http: &reqwest::Client,
    account_key: &SigningKey,
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
        let (resp, new_nonce) = acme_post(http, account_key, url, &nonce, "", Some(kid)).await?;
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
        let value: serde_json::Value =
            serde_json::from_str(&text).map_err(|e| AcmeError::Http(format!("poll parse: {e}")))?;
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
