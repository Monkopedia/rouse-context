//! Firebase ID token verification abstraction.
//!
//! Verifies Firebase anonymous auth ID tokens (RS256 JWTs) against
//! Google's public keys. Uses a trait so tests can substitute a mock.

use async_trait::async_trait;
use jsonwebtoken::{Algorithm, DecodingKey, Validation};
use serde::Deserialize;
use std::collections::HashMap;
use std::sync::Arc;
use thiserror::Error;
use tokio::sync::Mutex;
use tracing::debug;

/// URL for Google's public keys used to verify Firebase ID tokens.
const GOOGLE_CERTS_URL: &str =
    "https://www.googleapis.com/robot/v1/metadata/x509/securetoken@system.gserviceaccount.com";

#[derive(Debug, Error)]
pub enum FirebaseAuthError {
    #[error("invalid token: {0}")]
    InvalidToken(String),
    #[error("token expired")]
    Expired,
    #[error("wrong project: expected {expected}, got {actual}")]
    WrongProject { expected: String, actual: String },
    #[error("key fetch error: {0}")]
    KeyFetch(String),
}

/// Claims extracted from a verified Firebase ID token.
#[derive(Debug, Clone)]
pub struct FirebaseClaims {
    /// The Firebase UID (`sub` claim).
    pub uid: String,
}

#[async_trait]
pub trait FirebaseAuth: Send + Sync {
    /// Verify a Firebase ID token and extract claims.
    async fn verify_id_token(&self, token: &str) -> Result<FirebaseClaims, FirebaseAuthError>;
}

/// Cached Google public keys with expiry.
struct CachedKeys {
    keys: HashMap<String, DecodingKey>,
    expires_at: u64,
}

/// Production Firebase ID token verifier.
///
/// Fetches and caches Google's public signing keys, then validates
/// RS256 JWTs against them.
pub struct RealFirebaseAuth {
    project_id: String,
    http: reqwest::Client,
    cached_keys: Arc<Mutex<Option<CachedKeys>>>,
}

impl RealFirebaseAuth {
    pub fn new(project_id: String) -> Self {
        Self {
            project_id,
            http: reqwest::Client::new(),
            cached_keys: Arc::new(Mutex::new(None)),
        }
    }

    /// Fetch (or return cached) Google public keys.
    async fn get_keys(&self) -> Result<HashMap<String, DecodingKey>, FirebaseAuthError> {
        let mut cached = self.cached_keys.lock().await;

        let now = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap()
            .as_secs();

        if let Some(ref c) = *cached {
            if now < c.expires_at {
                return Ok(c.keys.clone());
            }
        }

        debug!("Fetching Google public keys for Firebase auth");

        let resp = self
            .http
            .get(GOOGLE_CERTS_URL)
            .send()
            .await
            .map_err(|e| FirebaseAuthError::KeyFetch(e.to_string()))?;

        // Parse max-age from Cache-Control header for cache duration
        let max_age = resp
            .headers()
            .get("cache-control")
            .and_then(|v| v.to_str().ok())
            .and_then(parse_max_age)
            .unwrap_or(3600);

        let certs: HashMap<String, String> = resp
            .json()
            .await
            .map_err(|e| FirebaseAuthError::KeyFetch(e.to_string()))?;

        let mut keys = HashMap::new();
        for (kid, pem) in &certs {
            match DecodingKey::from_rsa_pem(pem.as_bytes()) {
                Ok(key) => {
                    keys.insert(kid.clone(), key);
                }
                Err(e) => {
                    tracing::warn!(kid, "Failed to parse public key: {e}");
                }
            }
        }

        *cached = Some(CachedKeys {
            keys: keys.clone(),
            expires_at: now + max_age,
        });

        Ok(keys)
    }
}

/// Parse `max-age=NNN` from a Cache-Control header value.
fn parse_max_age(header: &str) -> Option<u64> {
    for part in header.split(',') {
        let part = part.trim();
        if let Some(rest) = part.strip_prefix("max-age=") {
            return rest.trim().parse().ok();
        }
    }
    None
}

/// JWT claims we care about for Firebase ID tokens.
#[derive(Debug, Deserialize)]
struct FirebaseTokenClaims {
    sub: String,
    aud: String,
}

#[async_trait]
impl FirebaseAuth for RealFirebaseAuth {
    async fn verify_id_token(&self, token: &str) -> Result<FirebaseClaims, FirebaseAuthError> {
        // Decode header to find the key ID
        let header = jsonwebtoken::decode_header(token)
            .map_err(|e| FirebaseAuthError::InvalidToken(e.to_string()))?;

        let kid = header
            .kid
            .ok_or_else(|| FirebaseAuthError::InvalidToken("missing kid in header".to_string()))?;

        let keys = self.get_keys().await?;
        let decoding_key = keys
            .get(&kid)
            .ok_or_else(|| FirebaseAuthError::InvalidToken(format!("unknown kid: {kid}")))?;

        let mut validation = Validation::new(Algorithm::RS256);
        validation.set_audience(&[&self.project_id]);
        validation.set_issuer(&[format!(
            "https://securetoken.google.com/{}",
            self.project_id
        )]);

        let token_data =
            jsonwebtoken::decode::<FirebaseTokenClaims>(token, decoding_key, &validation).map_err(
                |e| match e.kind() {
                    jsonwebtoken::errors::ErrorKind::ExpiredSignature => FirebaseAuthError::Expired,
                    _ => FirebaseAuthError::InvalidToken(e.to_string()),
                },
            )?;

        if token_data.claims.aud != self.project_id {
            return Err(FirebaseAuthError::WrongProject {
                expected: self.project_id.clone(),
                actual: token_data.claims.aud,
            });
        }

        Ok(FirebaseClaims {
            uid: token_data.claims.sub,
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parse_max_age_from_cache_control() {
        assert_eq!(
            parse_max_age("public, max-age=3600, must-revalidate"),
            Some(3600)
        );
        assert_eq!(parse_max_age("max-age=600"), Some(600));
        assert_eq!(parse_max_age("no-cache"), None);
    }
}
