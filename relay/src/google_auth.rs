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
    //! Google OAuth2 service-account token exchange tests (issue #306).
    //!
    //! Exercises [`GoogleAuthManager::access_token`] and
    //! [`GoogleAuthManager::make_jwt_assertion`] against an in-process
    //! axum mock of the Google token endpoint, using the
    //! [`ServiceAccountKey::token_uri`] field as the injection point.

    use super::*;
    use axum::extract::State;
    use axum::http::StatusCode;
    use axum::response::IntoResponse;
    use axum::routing::post;
    use axum::{Form, Json, Router};
    use std::collections::HashMap;
    use std::net::SocketAddr;
    use std::sync::Mutex as StdMutex;
    use tokio::net::TcpListener;

    /// Test RSA private key (PKCS#8 PEM). Used to sign the JWT assertion.
    const TEST_PRIV_KEY: &str = "-----BEGIN PRIVATE KEY-----\n\
MIIEvQIBADANBgkqhkiG9w0BAQEFAASCBKcwggSjAgEAAoIBAQCZuvETqv/fL7dn\n\
WcA6ABLuYpQyp+ClSpttt6VOcVxS6DDK8GJ7zO8/f6OefMBlZnNQAgA8mlY5aNWQ\n\
lPBxPBbuxNlT7Jj0CkLiFUi+zbPE/ejFwwd9WZAFQhUDtSMrzyB7SWNbrqhRcz5s\n\
nygNJAfdVzlf0gBGO0j1V5zQTqvLmFzm6l8EKdQyqtzMJZ6pegozwuA7khwJR5Mb\n\
QsfwkCNfHDyp0zMd372N2YMbsA/r9eCuJMom4826nU54IXWeCY2njlWE9KJXC0EI\n\
jPaosAym2Nvdk/ebxnK62OHb6fbJDOc4rgTDwUP9lIje0b05rCAz3rt1q1TDAHb2\n\
h/+80pDjAgMBAAECggEAS520byQxb6qc3+05rE3VAgTjOHdy/FrSUQl/+jGwY+dp\n\
+Kh9CMAo/mbeKFrcmAPovHX/f8+6kcqLIe7gxhH0hcW10J4ULhXOCD7H5XJw9nie\n\
QohH6tRfDvcONyCmCCp9o6bZhINIr6esEOnIXY5Xf/wjcIpvMByBKozJyXyo7B9m\n\
icJ0+pTfhLxcCIksDFb+vNEdbVlOM55eHO3PlBG3A9cLRNAnwda+e9ot+ac82BvE\n\
NANLk+YQ1G8bYBRyIFRKOmsLqVi9GVEBv4SkP+ygI3RsiAP5Ljl9jcrivIjgRaJa\n\
x0SGUN2MBR9MDTB+GpnyXJQUSJZouiHd2b0UH4N+4QKBgQDHCQ8+MZfAfqt6qli6\n\
1XXwEehRGeidTprNpSZwXqAAK/HjL0/Tm3uYjONLV0rQjhYIUqtTr92SMa3sPaSN\n\
rWUJZZDj0efSQPFKbm5LLCeT/pddVNkqEKBrXLs6DW9Kj+xH6aOXp5LLkY9rWgGD\n\
TREMO/BEJt7in5P2xgxSzx43bwKBgQDFunYHJcg4ieCQgL0Q5EmlA3SCgD0dK1fp\n\
THdpDtzEhpDdVthHQZnbmrPgYYfqdkwhzSsBMTjUXynlzv5+H0mNETAP6+Axv+d+\n\
tskIA/mm8HRyVSNdvhReaVNd/NQWPLpVWGKoqOegNAW9xeWkWLxkgZ3WeP5vRnDa\n\
T9gpmrQjzQKBgEzE48o7Wqr2sLGJjtvRhcHpRlAxzBUQwojbUG47MT+fs5bLIuEd\n\
sZhvjyP6MXMrurfPGyIWTUIcQ1dBl3zGCpiLQk19Iwtn3Sm2WnhIOaPNqRhop7Kf\n\
4yBGDjkgAXMi/CHorh7KlcZLCKSBfN/mE9NCMzQ2QfXrUyj1zr8KAD+lAoGBAIYB\n\
WPx/HrMyvn8wwPIxxbeQH+ZSAxlBxtLWgBcze2u1x3g641lnnF64+i+X6gV9JxvB\n\
cOPd+CX2WO7m2pOfoLl6bJhdxBPze3DlcFl+WDRLwp+6E730lNlniJiqQRLRFXfB\n\
7xtfXZu1pi53cKtxeDylm9M/LTE9DD7o3hdUQcIBAoGAVIJAsLc+VEHvmUvAC+sP\n\
lQEWb1+8RXHV7/YIqAHHljxcYo8nHO8LivVDY9iLULFfwm32UKWCvnLHIladRb1F\n\
L/Mi6VARahG0Y25KHRB9D0SMZHXG+efH6+iDRYiO++bzJ83PwZp+HlfdgF7ZQ17o\n\
fw8/8IuqJW21qI3USWr5qTU=\n\
-----END PRIVATE KEY-----\n";

    /// Captures what the mock token endpoint saw on the last call.
    #[derive(Default)]
    struct MockState {
        /// Form fields from the last POST.
        last_form: HashMap<String, String>,
        /// Number of times the endpoint was hit.
        hits: u32,
        /// Token value to return on success.
        access_token: String,
        /// Seconds-until-expiry to return.
        expires_in: u64,
        /// When set, the endpoint returns this status and an error body.
        fail_status: Option<StatusCode>,
        /// When true, return malformed JSON on success.
        malformed_body: bool,
    }

    #[derive(Clone)]
    struct Mock {
        inner: Arc<StdMutex<MockState>>,
    }

    impl Mock {
        fn new() -> Self {
            Self {
                inner: Arc::new(StdMutex::new(MockState {
                    access_token: "access-token-xyz".to_string(),
                    expires_in: 3600,
                    ..Default::default()
                })),
            }
        }

        fn with_token(self, t: &str) -> Self {
            self.inner.lock().unwrap().access_token = t.to_string();
            self
        }

        fn with_expires_in(self, e: u64) -> Self {
            self.inner.lock().unwrap().expires_in = e;
            self
        }

        fn with_failure(self, s: StatusCode) -> Self {
            self.inner.lock().unwrap().fail_status = Some(s);
            self
        }

        fn with_malformed_body(self) -> Self {
            self.inner.lock().unwrap().malformed_body = true;
            self
        }

        fn hits(&self) -> u32 {
            self.inner.lock().unwrap().hits
        }

        fn last_form(&self) -> HashMap<String, String> {
            self.inner.lock().unwrap().last_form.clone()
        }
    }

    async fn mock_token_handler(
        State(state): State<Mock>,
        Form(form): Form<HashMap<String, String>>,
    ) -> axum::response::Response {
        let mut g = state.inner.lock().unwrap();
        g.hits += 1;
        g.last_form = form.clone();
        if let Some(status) = g.fail_status {
            return (status, Json(serde_json::json!({"error": "invalid_grant"}))).into_response();
        }
        if g.malformed_body {
            return axum::response::Response::builder()
                .status(StatusCode::OK)
                .header("content-type", "application/json")
                .body(axum::body::Body::from("{malformed"))
                .unwrap();
        }
        Json(serde_json::json!({
            "access_token": g.access_token,
            "expires_in": g.expires_in,
            "token_type": "Bearer",
        }))
        .into_response()
    }

    async fn spawn_mock_token_endpoint(mock: Mock) -> String {
        let app = Router::new()
            .route("/token", post(mock_token_handler))
            .with_state(mock);
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let addr: SocketAddr = listener.local_addr().unwrap();
        tokio::spawn(async move {
            axum::serve(listener, app).await.unwrap();
        });
        format!("http://{addr}/token")
    }

    fn make_key(token_uri: &str) -> ServiceAccountKey {
        ServiceAccountKey {
            client_email: "test@proj.iam.gserviceaccount.com".to_string(),
            private_key: TEST_PRIV_KEY.to_string(),
            project_id: "my-project".to_string(),
            token_uri: token_uri.to_string(),
        }
    }

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

    #[test]
    fn from_file_reads_json_from_disk() {
        let tmp = tempfile::NamedTempFile::new().unwrap();
        let path = tmp.path().to_path_buf();
        std::fs::write(
            &path,
            r#"{
                "client_email": "a@b.iam.gserviceaccount.com",
                "private_key": "-----BEGIN PRIVATE KEY-----\nx\n-----END PRIVATE KEY-----\n",
                "project_id": "proj",
                "token_uri": "https://oauth2.googleapis.com/token"
            }"#,
        )
        .unwrap();

        let key = ServiceAccountKey::from_file(&path).expect("must load");
        assert_eq!(key.project_id, "proj");
    }

    #[test]
    fn from_file_errors_on_missing_file() {
        let err = ServiceAccountKey::from_file(Path::new("/nonexistent/path/xyz.json"))
            .expect_err("must fail");
        assert!(matches!(err, AuthError::Io(_)), "got: {err:?}");
    }

    #[test]
    fn from_file_errors_on_bad_json() {
        let tmp = tempfile::NamedTempFile::new().unwrap();
        std::fs::write(tmp.path(), "not valid json").unwrap();

        let err = ServiceAccountKey::from_file(tmp.path()).expect_err("must fail");
        assert!(matches!(err, AuthError::Json(_)), "got: {err:?}");
    }

    #[tokio::test]
    async fn access_token_fetches_happy_path() {
        let mock = Mock::new().with_token("happy-access-token");
        let url = spawn_mock_token_endpoint(mock.clone()).await;
        let mgr = GoogleAuthManager::new(make_key(&url), vec!["scope-a".to_string()]);

        let tok = mgr.access_token().await.expect("must mint token");
        assert_eq!(tok, "happy-access-token");
        assert_eq!(mock.hits(), 1);

        // Form submitted to the token endpoint must match the OAuth2
        // JWT-bearer grant type.
        let form = mock.last_form();
        assert_eq!(
            form.get("grant_type").map(|s| s.as_str()),
            Some("urn:ietf:params:oauth:grant-type:jwt-bearer")
        );
        let assertion = form.get("assertion").expect("assertion field");
        // JWT should have three dot-separated parts.
        assert_eq!(assertion.split('.').count(), 3);
    }

    #[tokio::test]
    async fn access_token_caches_fresh_token() {
        let mock = Mock::new();
        let url = spawn_mock_token_endpoint(mock.clone()).await;
        let mgr = GoogleAuthManager::new(make_key(&url), vec!["scope-a".to_string()]);

        mgr.access_token().await.unwrap();
        mgr.access_token().await.unwrap();
        mgr.access_token().await.unwrap();

        assert_eq!(
            mock.hits(),
            1,
            "second+ call should hit cache, not the endpoint"
        );
    }

    #[tokio::test]
    async fn access_token_refreshes_when_close_to_expiry() {
        // expires_in=60 -> after saturating_sub(60) the cached token is
        // already considered expired and will refresh every call.
        let mock = Mock::new().with_expires_in(60);
        let url = spawn_mock_token_endpoint(mock.clone()).await;
        let mgr = GoogleAuthManager::new(make_key(&url), vec!["scope-a".to_string()]);

        mgr.access_token().await.unwrap();
        mgr.access_token().await.unwrap();

        assert_eq!(mock.hits(), 2);
    }

    #[tokio::test]
    async fn access_token_surfaces_http_error() {
        let mock = Mock::new().with_failure(StatusCode::UNAUTHORIZED);
        let url = spawn_mock_token_endpoint(mock).await;
        let mgr = GoogleAuthManager::new(make_key(&url), vec!["scope-a".to_string()]);

        let err = mgr.access_token().await.expect_err("expected failure");
        match err {
            AuthError::Exchange(msg) => assert!(msg.contains("invalid_grant"), "got: {msg}"),
            other => panic!("unexpected: {other:?}"),
        }
    }

    #[tokio::test]
    async fn access_token_surfaces_server_error() {
        let mock = Mock::new().with_failure(StatusCode::INTERNAL_SERVER_ERROR);
        let url = spawn_mock_token_endpoint(mock).await;
        let mgr = GoogleAuthManager::new(make_key(&url), vec!["scope-a".to_string()]);

        let err = mgr.access_token().await.expect_err("expected failure");
        assert!(matches!(err, AuthError::Exchange(_)), "got: {err:?}");
    }

    #[tokio::test]
    async fn access_token_surfaces_malformed_response_body() {
        let mock = Mock::new().with_malformed_body();
        let url = spawn_mock_token_endpoint(mock).await;
        let mgr = GoogleAuthManager::new(make_key(&url), vec!["scope-a".to_string()]);

        let err = mgr.access_token().await.expect_err("expected failure");
        assert!(matches!(err, AuthError::Exchange(_)), "got: {err:?}");
    }

    #[tokio::test]
    async fn access_token_surfaces_network_error() {
        // Bind then drop so nothing is actually listening.
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let addr = listener.local_addr().unwrap();
        drop(listener);

        let mgr = GoogleAuthManager::new(
            make_key(&format!("http://{addr}/token")),
            vec!["scope-a".to_string()],
        );

        let err = mgr.access_token().await.expect_err("expected failure");
        assert!(matches!(err, AuthError::Exchange(_)), "got: {err:?}");
    }

    #[test]
    fn make_jwt_assertion_encodes_claims_and_scopes() {
        let key = make_key("https://oauth2.googleapis.com/token");
        let mgr = GoogleAuthManager::new(
            key.clone(),
            vec!["scope-a".to_string(), "scope-b".to_string()],
        );

        let jwt = mgr.make_jwt_assertion().expect("must sign");
        let parts: Vec<&str> = jwt.split('.').collect();
        assert_eq!(parts.len(), 3);

        // Decode the claims segment (URL-safe, no padding) and sanity-check.
        let claims_bytes = URL_SAFE_NO_PAD.decode(parts[1]).unwrap();
        let claims: serde_json::Value = serde_json::from_slice(&claims_bytes).unwrap();
        assert_eq!(claims["iss"], "test@proj.iam.gserviceaccount.com");
        assert_eq!(claims["aud"], "https://oauth2.googleapis.com/token");
        assert_eq!(claims["scope"], "scope-a scope-b");
        assert!(claims["iat"].is_number());
        assert!(claims["exp"].is_number());
        // exp should be 1h after iat.
        let iat = claims["iat"].as_u64().unwrap();
        let exp = claims["exp"].as_u64().unwrap();
        assert_eq!(exp - iat, 3600);
    }

    #[test]
    fn make_jwt_assertion_errors_on_invalid_pem() {
        let mut key = make_key("https://oauth2.googleapis.com/token");
        key.private_key = "not a pem".to_string();
        let mgr = GoogleAuthManager::new(key, vec!["scope".to_string()]);

        let err = mgr.make_jwt_assertion().expect_err("must fail");
        assert!(matches!(err, AuthError::Signing(_)), "got: {err:?}");
    }

    #[tokio::test]
    async fn access_token_errors_if_private_key_is_garbage() {
        let mut key = make_key("https://oauth2.googleapis.com/token");
        key.private_key =
            "-----BEGIN PRIVATE KEY-----\nnope\n-----END PRIVATE KEY-----\n".to_string();
        let mgr = GoogleAuthManager::new(key, vec!["scope".to_string()]);

        let err = mgr.access_token().await.expect_err("must fail");
        assert!(matches!(err, AuthError::Signing(_)), "got: {err:?}");
    }

    #[tokio::test]
    async fn fcm_auth_manager_wires_fcm_scope() {
        let mock = Mock::new();
        let url = spawn_mock_token_endpoint(mock.clone()).await;
        let mgr = fcm_auth_manager(make_key(&url));

        mgr.access_token().await.expect("must mint token");
        let form = mock.last_form();
        let assertion = form.get("assertion").unwrap();
        let payload_b64 = assertion.split('.').nth(1).unwrap();
        let payload_bytes = URL_SAFE_NO_PAD.decode(payload_b64).unwrap();
        let claims: serde_json::Value = serde_json::from_slice(&payload_bytes).unwrap();
        assert_eq!(claims["scope"], FCM_SCOPE);
    }

    #[test]
    fn auth_error_display_is_informative() {
        let e = AuthError::Signing("bad".to_string());
        assert!(format!("{e}").contains("bad"));

        let e = AuthError::Exchange("nope".to_string());
        assert!(format!("{e}").contains("nope"));

        // Json / Io errors pick up their inner messages.
        let json_err: serde_json::Error = serde_json::from_str::<u32>("xyz").unwrap_err();
        let e = AuthError::Json(json_err);
        assert!(!format!("{e}").is_empty());

        let io_err = std::io::Error::from(std::io::ErrorKind::NotFound);
        let e = AuthError::Io(io_err);
        assert!(!format!("{e}").is_empty());
    }
}
