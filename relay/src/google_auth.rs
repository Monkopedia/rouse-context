//! Google OAuth2 service account authentication.
//!
//! Loads a service account JSON key file and mints short-lived OAuth2
//! access tokens via the JWT grant flow. Tokens are cached and refreshed
//! automatically when they expire.

use base64::engine::general_purpose::URL_SAFE_NO_PAD;
use base64::Engine;
use serde::Deserialize;
use std::path::Path;
use std::sync::Arc;
use thiserror::Error;
use tokio::sync::Mutex;
use tracing::debug;

#[derive(Debug, Error)]
pub enum AuthError {
    #[error("failed to read service account file: {0}")]
    Io(#[from] std::io::Error),
    #[error("failed to parse service account JSON: {0}")]
    Json(#[from] serde_json::Error),
    #[error("JWT signing error: {0}")]
    Signing(String),
    #[error("token exchange error: {0}")]
    Exchange(String),
}

/// Relevant fields from a Google service account JSON key file.
#[derive(Debug, Clone, Deserialize)]
pub struct ServiceAccountKey {
    pub client_email: String,
    pub private_key: String,
    pub project_id: String,
    pub token_uri: String,
}

impl ServiceAccountKey {
    /// Load from a JSON file on disk.
    pub fn from_file(path: &Path) -> Result<Self, AuthError> {
        let contents = std::fs::read_to_string(path)?;
        let key: ServiceAccountKey = serde_json::from_str(&contents)?;
        Ok(key)
    }
}

/// Provides a way to get a fresh access token.
#[async_trait::async_trait]
pub trait TokenProvider: Send + Sync {
    /// Get a valid access token, refreshing if necessary.
    async fn access_token(&self) -> Result<String, AuthError>;
}

/// Cached token with expiry.
struct CachedToken {
    token: String,
    /// Expiry time as seconds since UNIX epoch.
    expires_at: u64,
}

/// Manages OAuth2 tokens for a Google service account.
///
/// Automatically refreshes tokens when they are close to expiry.
pub struct GoogleAuthManager {
    key: ServiceAccountKey,
    scopes: Vec<String>,
    http: reqwest::Client,
    cached: Mutex<Option<CachedToken>>,
}

impl GoogleAuthManager {
    /// Create a new auth manager for the given service account and scopes.
    pub fn new(key: ServiceAccountKey, scopes: Vec<String>) -> Self {
        Self {
            key,
            scopes,
            http: reqwest::Client::new(),
            cached: Mutex::new(None),
        }
    }

    /// Create a self-signed JWT assertion for the token exchange.
    fn make_jwt_assertion(&self) -> Result<String, AuthError> {
        let now = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap()
            .as_secs();

        let header = serde_json::json!({
            "alg": "RS256",
            "typ": "JWT"
        });

        let claims = serde_json::json!({
            "iss": self.key.client_email,
            "scope": self.scopes.join(" "),
            "aud": self.key.token_uri,
            "iat": now,
            "exp": now + 3600,
        });

        let header_b64 = URL_SAFE_NO_PAD.encode(header.to_string().as_bytes());
        let claims_b64 = URL_SAFE_NO_PAD.encode(claims.to_string().as_bytes());
        let signing_input = format!("{header_b64}.{claims_b64}");

        // Parse the PEM private key and sign with RS256
        let encoding_key = jsonwebtoken::EncodingKey::from_rsa_pem(self.key.private_key.as_bytes())
            .map_err(|e| AuthError::Signing(e.to_string()))?;

        let signature = jsonwebtoken::crypto::sign(
            signing_input.as_bytes(),
            &encoding_key,
            jsonwebtoken::Algorithm::RS256,
        )
        .map_err(|e| AuthError::Signing(e.to_string()))?;

        Ok(format!("{signing_input}.{signature}"))
    }

    /// Exchange a JWT assertion for an access token via Google's token endpoint.
    async fn exchange_token(&self) -> Result<CachedToken, AuthError> {
        let assertion = self.make_jwt_assertion()?;

        let resp = self
            .http
            .post(&self.key.token_uri)
            .form(&[
                ("grant_type", "urn:ietf:params:oauth:grant-type:jwt-bearer"),
                ("assertion", &assertion),
            ])
            .send()
            .await
            .map_err(|e| AuthError::Exchange(e.to_string()))?;

        if !resp.status().is_success() {
            let text = resp.text().await.unwrap_or_default();
            return Err(AuthError::Exchange(format!(
                "token endpoint returned: {text}"
            )));
        }

        #[derive(Deserialize)]
        struct TokenResponse {
            access_token: String,
            expires_in: u64,
        }

        let token_resp: TokenResponse = resp
            .json()
            .await
            .map_err(|e| AuthError::Exchange(e.to_string()))?;

        let now = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap()
            .as_secs();

        Ok(CachedToken {
            token: token_resp.access_token,
            // Refresh 60 seconds before actual expiry
            expires_at: now + token_resp.expires_in.saturating_sub(60),
        })
    }
}

#[async_trait::async_trait]
impl TokenProvider for GoogleAuthManager {
    async fn access_token(&self) -> Result<String, AuthError> {
        let mut cached = self.cached.lock().await;

        let now = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap()
            .as_secs();

        if let Some(ref token) = *cached {
            if now < token.expires_at {
                return Ok(token.token.clone());
            }
        }

        debug!("Refreshing Google OAuth2 access token");
        let new_token = self.exchange_token().await?;
        let access_token = new_token.token.clone();
        *cached = Some(new_token);
        Ok(access_token)
    }
}

/// FCM messaging scope.
pub const FCM_SCOPE: &str = "https://www.googleapis.com/auth/firebase.messaging";

/// Create a [`GoogleAuthManager`] for FCM from a service account key file.
pub fn fcm_auth_manager(key: ServiceAccountKey) -> Arc<GoogleAuthManager> {
    Arc::new(GoogleAuthManager::new(key, vec![FCM_SCOPE.to_string()]))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn service_account_key_from_json() {
        let json = r#"{
            "client_email": "test@proj.iam.gserviceaccount.com",
            "private_key": "-----BEGIN RSA PRIVATE KEY-----\nfake\n-----END RSA PRIVATE KEY-----\n",
            "project_id": "my-project",
            "token_uri": "https://oauth2.googleapis.com/token"
        }"#;
        let key: ServiceAccountKey = serde_json::from_str(json).unwrap();
        assert_eq!(key.project_id, "my-project");
        assert_eq!(key.client_email, "test@proj.iam.gserviceaccount.com");
    }

    #[test]
    fn service_account_key_missing_field() {
        let json = r#"{ "client_email": "test@test.com" }"#;
        let result: Result<ServiceAccountKey, _> = serde_json::from_str(json);
        assert!(result.is_err());
    }
}
