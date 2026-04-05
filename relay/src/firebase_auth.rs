//! Firebase ID token verification abstraction.
//!
//! Verifies Firebase anonymous auth ID tokens (RS256 JWTs) against
//! Google's public keys. Uses a trait so tests can substitute a mock.

use async_trait::async_trait;
use thiserror::Error;

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
