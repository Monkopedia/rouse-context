//! FCM (Firebase Cloud Messaging) client abstraction.
//!
//! Sends data-only push notifications to devices via the FCM HTTP v1 API.
//! Uses a trait so tests can substitute a mock.

use async_trait::async_trait;
use serde::Serialize;
use thiserror::Error;

#[derive(Debug, Error)]
pub enum FcmError {
    #[error("http error: {0}")]
    Http(String),
    #[error("auth error: {0}")]
    Auth(String),
    #[error("invalid token")]
    InvalidToken,
}

/// The data payload for an FCM message.
#[derive(Debug, Clone, Serialize)]
pub struct FcmData {
    #[serde(rename = "type")]
    pub message_type: String,
    pub relay_host: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub relay_port: Option<String>,
}

#[async_trait]
pub trait FcmClient: Send + Sync {
    /// Send a high-priority data message to a device.
    async fn send_data_message(
        &self,
        fcm_token: &str,
        data: &FcmData,
        high_priority: bool,
    ) -> Result<(), FcmError>;
}

/// Build a "wake" FCM data payload.
pub fn wake_payload(relay_host: &str) -> FcmData {
    FcmData {
        message_type: "wake".to_string(),
        relay_host: relay_host.to_string(),
        relay_port: Some("443".to_string()),
    }
}

/// Build a "renew" FCM data payload.
pub fn renew_payload(relay_host: &str) -> FcmData {
    FcmData {
        message_type: "renew".to_string(),
        relay_host: relay_host.to_string(),
        relay_port: None,
    }
}
