//! JWS signing and ACME transport layer.
//!
//! This layer knows about ECDSA P-256 keys, JSON Web Signatures, nonces, and
//! posting signed JWS requests to ACME endpoints. It does NOT know about
//! orders, authorizations, or challenges.

use p256::ecdsa::{signature::Signer, SigningKey};
use sha2::{Digest, Sha256};

use super::types::base64url;
use super::AcmeError;

/// Build the JWK (public key) for the given ECDSA P-256 signing key.
pub(super) fn jwk(account_key: &SigningKey) -> serde_json::Value {
    let public_key = account_key.verifying_key();
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

/// Build the JWK thumbprint for the given ECDSA P-256 signing key, used in
/// DNS-01 challenge key authorizations.
pub(super) fn jwk_thumbprint(account_key: &SigningKey) -> String {
    let public_key = account_key.verifying_key();
    let point = public_key.to_encoded_point(false);
    let x = base64url(point.x().expect("x coordinate"));
    let y = base64url(point.y().expect("y coordinate"));

    // JWK thumbprint per RFC 7638: lexicographic JSON with required members
    let jwk_json = format!(r#"{{"crv":"P-256","kty":"EC","x":"{x}","y":"{y}"}}"#);
    let digest = Sha256::digest(jwk_json.as_bytes());
    base64url(&digest)
}

/// Get a fresh nonce from the ACME server via HEAD on `new_nonce_url`.
pub(super) async fn get_nonce(
    http: &reqwest::Client,
    new_nonce_url: &str,
) -> Result<String, AcmeError> {
    let resp = http
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

/// Sign an ACME request payload as a JWS (Flattened JSON Serialization).
///
/// Uses JWK in protected header when `kid` is None (for newAccount),
/// or `kid` when set (for all subsequent requests).
pub(super) fn sign_jws(
    account_key: &SigningKey,
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
            "jwk": jwk(account_key),
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
    let signature: p256::ecdsa::Signature = account_key.sign(signing_input.as_bytes());
    let sig_b64 = base64url(&signature.to_bytes());

    serde_json::json!({
        "protected": protected_b64,
        "payload": payload_b64,
        "signature": sig_b64,
    })
}

/// Send a signed ACME POST request, returning (response, new_nonce).
pub(super) async fn acme_post(
    http: &reqwest::Client,
    account_key: &SigningKey,
    url: &str,
    nonce: &str,
    payload: &str,
    kid: Option<&str>,
) -> Result<(reqwest::Response, String), AcmeError> {
    let body = sign_jws(account_key, url, nonce, payload, kid);
    let resp = http
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

#[cfg(test)]
mod tests {
    use super::*;
    use base64::Engine as _;

    #[test]
    fn jwk_has_required_fields() {
        let key = SigningKey::random(&mut rand::thread_rng());
        let j = jwk(&key);
        assert_eq!(j["kty"], "EC");
        assert_eq!(j["crv"], "P-256");
        assert!(!j["x"].as_str().unwrap().is_empty());
        assert!(!j["y"].as_str().unwrap().is_empty());
    }

    #[test]
    fn jwk_thumbprint_is_deterministic_for_same_key() {
        let key = SigningKey::random(&mut rand::thread_rng());
        let a = jwk_thumbprint(&key);
        let b = jwk_thumbprint(&key);
        assert_eq!(a, b);
    }

    #[test]
    fn sign_jws_produces_three_base64url_fields() {
        let key = SigningKey::random(&mut rand::thread_rng());
        let signed = sign_jws(
            &key,
            "https://example.com/newOrder",
            "nonce-123",
            r#"{"foo":"bar"}"#,
            Some("kid-xyz"),
        );
        assert!(!signed["protected"].as_str().unwrap().is_empty());
        assert!(!signed["payload"].as_str().unwrap().is_empty());
        assert!(!signed["signature"].as_str().unwrap().is_empty());
    }

    #[test]
    fn sign_jws_uses_jwk_when_kid_is_none() {
        let key = SigningKey::random(&mut rand::thread_rng());
        let signed = sign_jws(
            &key,
            "https://example.com/newAccount",
            "nonce-123",
            r#"{"termsOfServiceAgreed":true}"#,
            None,
        );
        // Decode protected header and check it has jwk, not kid.
        let protected_b64 = signed["protected"].as_str().unwrap();
        let protected_bytes = base64::engine::general_purpose::URL_SAFE_NO_PAD
            .decode(protected_b64)
            .unwrap();
        let protected: serde_json::Value = serde_json::from_slice(&protected_bytes).unwrap();
        assert!(protected.get("jwk").is_some());
        assert!(protected.get("kid").is_none());
    }
}
