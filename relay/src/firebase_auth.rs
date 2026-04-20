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
///
/// Overridable for tests via [`RealFirebaseAuth::with_certs_url`].
pub(crate) const GOOGLE_CERTS_URL: &str =
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
    certs_url: String,
}

impl RealFirebaseAuth {
    pub fn new(project_id: String) -> Self {
        Self::with_certs_url(project_id, GOOGLE_CERTS_URL.to_string())
    }

    /// Construct a verifier pointed at an arbitrary certs URL. Used by
    /// tests to redirect traffic at an in-process mock server.
    pub(crate) fn with_certs_url(project_id: String, certs_url: String) -> Self {
        Self {
            project_id,
            http: reqwest::Client::new(),
            cached_keys: Arc::new(Mutex::new(None)),
            certs_url,
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
            .get(&self.certs_url)
            .send()
            .await
            .map_err(|e| FirebaseAuthError::KeyFetch(e.to_string()))?;

        if !resp.status().is_success() {
            return Err(FirebaseAuthError::KeyFetch(format!(
                "certs endpoint returned {}",
                resp.status()
            )));
        }

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
    //! Firebase ID token verification tests (issue #306).
    //!
    //! Exercises [`RealFirebaseAuth::verify_id_token`] against an in-process
    //! axum mock that serves Google public-key PEM bundles. Tokens are
    //! signed with a test RSA keypair embedded in this module.

    use super::*;
    use axum::http::StatusCode;
    use axum::response::IntoResponse;
    use axum::routing::get;
    use axum::Router;
    use base64::engine::general_purpose::URL_SAFE_NO_PAD;
    use base64::Engine;
    use jsonwebtoken::{EncodingKey, Header};
    use std::net::SocketAddr;
    use std::sync::{Arc, Mutex};
    use tokio::net::TcpListener;

    /// Test RSA private key (PKCS#8 PEM). Used to sign JWTs in tests.
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

    /// x509 self-signed cert matching [`TEST_PRIV_KEY`]. Google returns
    /// cert PEM (not raw public keys) from the certs endpoint; we match
    /// that shape.
    const TEST_CERT_PEM: &str = "-----BEGIN CERTIFICATE-----\n\
MIIC/zCCAeegAwIBAgIUE6y5B632gz35tRKlEOWBa9SuCr8wDQYJKoZIhvcNAQEL\n\
BQAwDzENMAsGA1UEAwwEdGVzdDAeFw0yNjA0MjAwMzI1MTFaFw0zNjA0MTcwMzI1\n\
MTFaMA8xDTALBgNVBAMMBHRlc3QwggEiMA0GCSqGSIb3DQEBAQUAA4IBDwAwggEK\n\
AoIBAQCZuvETqv/fL7dnWcA6ABLuYpQyp+ClSpttt6VOcVxS6DDK8GJ7zO8/f6Oe\n\
fMBlZnNQAgA8mlY5aNWQlPBxPBbuxNlT7Jj0CkLiFUi+zbPE/ejFwwd9WZAFQhUD\n\
tSMrzyB7SWNbrqhRcz5snygNJAfdVzlf0gBGO0j1V5zQTqvLmFzm6l8EKdQyqtzM\n\
JZ6pegozwuA7khwJR5MbQsfwkCNfHDyp0zMd372N2YMbsA/r9eCuJMom4826nU54\n\
IXWeCY2njlWE9KJXC0EIjPaosAym2Nvdk/ebxnK62OHb6fbJDOc4rgTDwUP9lIje\n\
0b05rCAz3rt1q1TDAHb2h/+80pDjAgMBAAGjUzBRMB0GA1UdDgQWBBRn80SHqIOl\n\
qokHmPz3pz9Z/8QzkjAfBgNVHSMEGDAWgBRn80SHqIOlqokHmPz3pz9Z/8QzkjAP\n\
BgNVHRMBAf8EBTADAQH/MA0GCSqGSIb3DQEBCwUAA4IBAQA8gXfqGqaXSgfrY1va\n\
WiqlSDXQpb94yr5fhH099geSRlAJ5/HCo643TVJXQEVeTWkXDrE3jJu18JSX2F1x\n\
R+m7MJt8sY0pFdJ8JJ5103AT9D6mbA/XH1zrTnKQiXuVPtZqIBn2a9hQpH7mmrLM\n\
G22q+Xzp3qXTyUA/2yjuQ3CoZW+NrIZWOoeFzqwlNddhKOqHykmUXLQSuCo1dS6Q\n\
O1KveQzapIUpr79mdtKZAVktt72kannvb/tdGFJsLFrj3HqWhMQANOptBqRWZbkI\n\
Vzek/LM0aq/Oo1MWw/mmpXIChCk11YkUXTcSlryieAmiCeROq9T938K6KLB0W/V6\n\
4aQM\n\
-----END CERTIFICATE-----\n";

    const TEST_KID: &str = "test-kid-1";
    const TEST_PROJECT: &str = "test-project";

    fn now_secs() -> u64 {
        std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap()
            .as_secs()
    }

    /// JWT payload matching what Firebase issues. `iat`/`exp` are seconds.
    #[derive(serde::Serialize)]
    struct Payload {
        iss: String,
        aud: String,
        sub: String,
        iat: u64,
        exp: u64,
    }

    fn sign_token(kid: &str, payload: &Payload) -> String {
        let mut header = Header::new(Algorithm::RS256);
        header.kid = Some(kid.to_string());
        let key =
            EncodingKey::from_rsa_pem(TEST_PRIV_KEY.as_bytes()).expect("test priv key must parse");
        jsonwebtoken::encode(&header, payload, &key).expect("encode must succeed")
    }

    fn valid_payload() -> Payload {
        let now = now_secs();
        Payload {
            iss: format!("https://securetoken.google.com/{TEST_PROJECT}"),
            aud: TEST_PROJECT.to_string(),
            sub: "test-uid".to_string(),
            iat: now,
            exp: now + 3600,
        }
    }

    /// State shared between the mock certs endpoint and the test.
    #[derive(Clone)]
    struct MockCertsState {
        body: Arc<Mutex<serde_json::Value>>,
        cache_control: Arc<Mutex<Option<String>>>,
        status: Arc<Mutex<StatusCode>>,
        /// When true, the endpoint returns malformed JSON so we can test
        /// parse failures.
        malformed: Arc<Mutex<bool>>,
        /// How many times the endpoint has been hit.
        hits: Arc<Mutex<u32>>,
    }

    impl MockCertsState {
        fn new() -> Self {
            let body = serde_json::json!({ TEST_KID: TEST_CERT_PEM });
            Self {
                body: Arc::new(Mutex::new(body)),
                cache_control: Arc::new(Mutex::new(Some("public, max-age=3600".to_string()))),
                status: Arc::new(Mutex::new(StatusCode::OK)),
                malformed: Arc::new(Mutex::new(false)),
                hits: Arc::new(Mutex::new(0)),
            }
        }

        fn hits(&self) -> u32 {
            *self.hits.lock().unwrap()
        }

        fn set_body(&self, body: serde_json::Value) {
            *self.body.lock().unwrap() = body;
        }

        fn set_status(&self, status: StatusCode) {
            *self.status.lock().unwrap() = status;
        }

        fn set_malformed(&self, yes: bool) {
            *self.malformed.lock().unwrap() = yes;
        }

        fn set_cache_control(&self, value: Option<String>) {
            *self.cache_control.lock().unwrap() = value;
        }
    }

    async fn mock_certs_handler(
        axum::extract::State(state): axum::extract::State<MockCertsState>,
    ) -> axum::response::Response {
        *state.hits.lock().unwrap() += 1;
        let status = *state.status.lock().unwrap();
        if !status.is_success() {
            return (status, "error").into_response();
        }
        let malformed = *state.malformed.lock().unwrap();
        let cache_control = state.cache_control.lock().unwrap().clone();
        let body = state.body.lock().unwrap().clone();

        let mut builder = axum::response::Response::builder().status(StatusCode::OK);
        if let Some(cc) = cache_control {
            builder = builder.header("cache-control", cc);
        }
        if malformed {
            builder = builder.header("content-type", "application/json");
            builder.body(axum::body::Body::from("{not json")).unwrap()
        } else {
            builder = builder.header("content-type", "application/json");
            builder
                .body(axum::body::Body::from(body.to_string()))
                .unwrap()
        }
    }

    async fn spawn_mock_certs(state: MockCertsState) -> String {
        let app = Router::new()
            .route("/certs", get(mock_certs_handler))
            .with_state(state);
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let addr: SocketAddr = listener.local_addr().unwrap();
        tokio::spawn(async move {
            axum::serve(listener, app).await.unwrap();
        });
        format!("http://{addr}/certs")
    }

    fn test_auth(certs_url: String) -> RealFirebaseAuth {
        RealFirebaseAuth::with_certs_url(TEST_PROJECT.to_string(), certs_url)
    }

    #[test]
    fn parse_max_age_from_cache_control() {
        assert_eq!(
            parse_max_age("public, max-age=3600, must-revalidate"),
            Some(3600)
        );
        assert_eq!(parse_max_age("max-age=600"), Some(600));
        assert_eq!(parse_max_age("no-cache"), None);
        assert_eq!(parse_max_age("max-age=abc"), None);
        assert_eq!(parse_max_age(""), None);
    }

    #[tokio::test]
    async fn verify_id_token_accepts_well_formed_token() {
        let state = MockCertsState::new();
        let url = spawn_mock_certs(state).await;
        let auth = test_auth(url);

        let token = sign_token(TEST_KID, &valid_payload());
        let claims = auth.verify_id_token(&token).await.expect("must verify");
        assert_eq!(claims.uid, "test-uid");
    }

    #[tokio::test]
    async fn verify_id_token_rejects_expired_token() {
        let state = MockCertsState::new();
        let url = spawn_mock_certs(state).await;
        let auth = test_auth(url);

        let mut payload = valid_payload();
        payload.iat = now_secs() - 7200;
        payload.exp = now_secs() - 3600;
        let token = sign_token(TEST_KID, &payload);

        let err = auth
            .verify_id_token(&token)
            .await
            .expect_err("expired token must fail");
        assert!(matches!(err, FirebaseAuthError::Expired), "got: {err:?}");
    }

    #[tokio::test]
    async fn verify_id_token_rejects_wrong_audience() {
        let state = MockCertsState::new();
        let url = spawn_mock_certs(state).await;
        let auth = test_auth(url);

        let mut payload = valid_payload();
        payload.aud = "some-other-project".to_string();
        let token = sign_token(TEST_KID, &payload);

        let err = auth
            .verify_id_token(&token)
            .await
            .expect_err("wrong aud must fail");
        // jsonwebtoken surfaces as InvalidToken because aud validation is
        // internal. We don't care which variant as long as it's not Expired.
        assert!(!matches!(err, FirebaseAuthError::Expired), "got: {err:?}");
    }

    #[tokio::test]
    async fn verify_id_token_rejects_wrong_issuer() {
        let state = MockCertsState::new();
        let url = spawn_mock_certs(state).await;
        let auth = test_auth(url);

        let mut payload = valid_payload();
        payload.iss = "https://evil.example.com/".to_string();
        let token = sign_token(TEST_KID, &payload);

        let err = auth
            .verify_id_token(&token)
            .await
            .expect_err("wrong iss must fail");
        assert!(
            matches!(err, FirebaseAuthError::InvalidToken(_)),
            "got: {err:?}"
        );
    }

    #[tokio::test]
    async fn verify_id_token_rejects_mangled_jwt() {
        let state = MockCertsState::new();
        let url = spawn_mock_certs(state).await;
        let auth = test_auth(url);

        let err = auth
            .verify_id_token("not-a-jwt")
            .await
            .expect_err("mangled jwt must fail");
        assert!(
            matches!(err, FirebaseAuthError::InvalidToken(_)),
            "got: {err:?}"
        );
    }

    #[tokio::test]
    async fn verify_id_token_rejects_missing_kid() {
        let state = MockCertsState::new();
        let url = spawn_mock_certs(state).await;
        let auth = test_auth(url);

        // Hand-craft a JWT with no kid in header.
        let header = serde_json::json!({"alg": "RS256", "typ": "JWT"}).to_string();
        let payload = serde_json::to_string(&valid_payload()).unwrap();
        let signing_input = format!(
            "{}.{}",
            URL_SAFE_NO_PAD.encode(header),
            URL_SAFE_NO_PAD.encode(payload)
        );
        let key = jsonwebtoken::EncodingKey::from_rsa_pem(TEST_PRIV_KEY.as_bytes()).unwrap();
        let sig = jsonwebtoken::crypto::sign(
            signing_input.as_bytes(),
            &key,
            jsonwebtoken::Algorithm::RS256,
        )
        .unwrap();
        let token = format!("{signing_input}.{sig}");

        let err = auth
            .verify_id_token(&token)
            .await
            .expect_err("missing kid must fail");
        match err {
            FirebaseAuthError::InvalidToken(msg) => assert!(msg.contains("kid")),
            other => panic!("unexpected: {other:?}"),
        }
    }

    #[tokio::test]
    async fn verify_id_token_rejects_unknown_kid() {
        let state = MockCertsState::new();
        let url = spawn_mock_certs(state).await;
        let auth = test_auth(url);

        let token = sign_token("unknown-kid", &valid_payload());
        let err = auth
            .verify_id_token(&token)
            .await
            .expect_err("unknown kid must fail");
        match err {
            FirebaseAuthError::InvalidToken(msg) => {
                assert!(msg.contains("unknown kid"), "got: {msg}");
            }
            other => panic!("unexpected: {other:?}"),
        }
    }

    #[tokio::test]
    async fn verify_id_token_rejects_hs256_algorithm() {
        let state = MockCertsState::new();
        let url = spawn_mock_certs(state).await;
        let auth = test_auth(url);

        // Sign an HS256 token with a symmetric key — must be rejected.
        let mut header = Header::new(Algorithm::HS256);
        header.kid = Some(TEST_KID.to_string());
        let key = EncodingKey::from_secret(b"shared-secret");
        let token = jsonwebtoken::encode(&header, &valid_payload(), &key).unwrap();

        let err = auth
            .verify_id_token(&token)
            .await
            .expect_err("wrong alg must fail");
        assert!(
            matches!(err, FirebaseAuthError::InvalidToken(_)),
            "got: {err:?}"
        );
    }

    #[tokio::test]
    async fn get_keys_errors_on_non_200_status() {
        let state = MockCertsState::new();
        state.set_status(StatusCode::INTERNAL_SERVER_ERROR);
        let url = spawn_mock_certs(state).await;
        let auth = test_auth(url);

        let err = auth
            .verify_id_token(&sign_token(TEST_KID, &valid_payload()))
            .await
            .expect_err("5xx on certs must fail");
        assert!(
            matches!(err, FirebaseAuthError::KeyFetch(_)),
            "got: {err:?}"
        );
    }

    #[tokio::test]
    async fn get_keys_errors_on_malformed_json() {
        let state = MockCertsState::new();
        state.set_malformed(true);
        let url = spawn_mock_certs(state).await;
        let auth = test_auth(url);

        let err = auth
            .verify_id_token(&sign_token(TEST_KID, &valid_payload()))
            .await
            .expect_err("malformed certs body must fail");
        assert!(
            matches!(err, FirebaseAuthError::KeyFetch(_)),
            "got: {err:?}"
        );
    }

    #[tokio::test]
    async fn get_keys_errors_on_unreachable_endpoint() {
        // Point at a TCP port that is (virtually) guaranteed to reject:
        // bind then immediately drop the listener so nothing is listening.
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let addr = listener.local_addr().unwrap();
        drop(listener);
        let auth = test_auth(format!("http://{addr}/certs"));

        let err = auth
            .verify_id_token(&sign_token(TEST_KID, &valid_payload()))
            .await
            .expect_err("unreachable endpoint must fail");
        assert!(
            matches!(err, FirebaseAuthError::KeyFetch(_)),
            "got: {err:?}"
        );
    }

    #[tokio::test]
    async fn get_keys_skips_unparseable_pem_and_succeeds_with_valid_ones() {
        let state = MockCertsState::new();
        state.set_body(serde_json::json!({
            "garbage-kid": "not a pem",
            TEST_KID: TEST_CERT_PEM,
        }));
        let url = spawn_mock_certs(state).await;
        let auth = test_auth(url);

        let token = sign_token(TEST_KID, &valid_payload());
        auth.verify_id_token(&token).await.expect("must verify");
    }

    #[tokio::test]
    async fn get_keys_caches_between_calls() {
        let state = MockCertsState::new();
        let url = spawn_mock_certs(state.clone()).await;
        let auth = test_auth(url);

        let token = sign_token(TEST_KID, &valid_payload());
        auth.verify_id_token(&token).await.unwrap();
        auth.verify_id_token(&token).await.unwrap();
        auth.verify_id_token(&token).await.unwrap();

        assert_eq!(state.hits(), 1, "keys should be cached after first fetch");
    }

    #[tokio::test]
    async fn get_keys_refetches_when_cache_expired() {
        let state = MockCertsState::new();
        // Cache for 0 seconds so second call refetches.
        state.set_cache_control(Some("max-age=0".to_string()));
        let url = spawn_mock_certs(state.clone()).await;
        let auth = test_auth(url);

        let token = sign_token(TEST_KID, &valid_payload());
        auth.verify_id_token(&token).await.unwrap();
        // Give the clock a chance to move past `now + 0`.
        tokio::time::sleep(std::time::Duration::from_millis(1100)).await;
        auth.verify_id_token(&token).await.unwrap();

        assert!(state.hits() >= 2, "expired cache should trigger refetch");
    }

    #[tokio::test]
    async fn get_keys_uses_default_cache_when_header_missing() {
        let state = MockCertsState::new();
        state.set_cache_control(None);
        let url = spawn_mock_certs(state.clone()).await;
        let auth = test_auth(url);

        let token = sign_token(TEST_KID, &valid_payload());
        auth.verify_id_token(&token).await.unwrap();
        auth.verify_id_token(&token).await.unwrap();
        // Without max-age, we fall back to 3600s and should cache.
        assert_eq!(state.hits(), 1);
    }

    #[test]
    fn firebase_auth_error_display_is_informative() {
        let e = FirebaseAuthError::InvalidToken("x".to_string());
        assert!(format!("{e}").contains("x"));

        let e = FirebaseAuthError::Expired;
        assert!(format!("{e}").contains("expired"));

        let e = FirebaseAuthError::WrongProject {
            expected: "a".to_string(),
            actual: "b".to_string(),
        };
        let msg = format!("{e}");
        assert!(msg.contains('a') && msg.contains('b'));

        let e = FirebaseAuthError::KeyFetch("boom".to_string());
        assert!(format!("{e}").contains("boom"));
    }

    #[test]
    fn new_uses_production_certs_url() {
        // Smoke test that `new` doesn't panic and records something that
        // looks like the real URL. We don't hit the network.
        let auth = RealFirebaseAuth::new("any".to_string());
        assert_eq!(auth.certs_url, GOOGLE_CERTS_URL);
        assert_eq!(auth.project_id, "any");
    }
}
