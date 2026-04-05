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
/// Only contains the message type — the device knows its relay URL from BuildConfig.
#[derive(Debug, Clone, Serialize)]
pub struct FcmData {
    #[serde(rename = "type")]
    pub message_type: String,
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
pub fn wake_payload() -> FcmData {
    FcmData {
        message_type: "wake".to_string(),
    }
}

/// Build a "renew" FCM data payload.
pub fn renew_payload() -> FcmData {
    FcmData {
        message_type: "renew".to_string(),
    }
}

/// Default FCM base URL (Google's production endpoint).
pub const DEFAULT_FCM_BASE_URL: &str = "https://fcm.googleapis.com";

/// Real FCM client that sends messages via the FCM HTTP v1 API.
///
/// Accepts a configurable `base_url` so tests can point at a fake FCM server
/// by setting the `FCM_BASE_URL` environment variable.
pub struct RealFcmClient {
    http: reqwest::Client,
    base_url: String,
    project_id: String,
}

impl RealFcmClient {
    /// Create a new FCM client.
    ///
    /// `base_url` is the scheme+host (e.g. `https://fcm.googleapis.com`).
    /// `project_id` is the Firebase project ID used in the send URL path.
    pub fn new(base_url: String, project_id: String) -> Self {
        Self {
            http: reqwest::Client::new(),
            base_url,
            project_id,
        }
    }

    fn send_url(&self) -> String {
        format!(
            "{}/v1/projects/{}/messages:send",
            self.base_url.trim_end_matches('/'),
            self.project_id
        )
    }
}

#[async_trait]
impl FcmClient for RealFcmClient {
    async fn send_data_message(
        &self,
        fcm_token: &str,
        data: &FcmData,
        high_priority: bool,
    ) -> Result<(), FcmError> {
        let priority = if high_priority { "high" } else { "normal" };

        let body = serde_json::json!({
            "message": {
                "token": fcm_token,
                "android": { "priority": priority },
                "data": data
            }
        });

        let resp = self
            .http
            .post(self.send_url())
            .json(&body)
            .send()
            .await
            .map_err(|e| FcmError::Http(e.to_string()))?;

        let status = resp.status();
        if status.is_success() {
            Ok(())
        } else if status.as_u16() == 404 || status.as_u16() == 400 {
            // FCM returns 404 or 400 for invalid/expired tokens
            Err(FcmError::InvalidToken)
        } else if status.as_u16() == 401 || status.as_u16() == 403 {
            let text = resp.text().await.unwrap_or_default();
            Err(FcmError::Auth(format!("{status}: {text}")))
        } else {
            let text = resp.text().await.unwrap_or_default();
            Err(FcmError::Http(format!("{status}: {text}")))
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn send_url_uses_default_base() {
        let client =
            RealFcmClient::new(DEFAULT_FCM_BASE_URL.to_string(), "my-project".to_string());
        assert_eq!(
            client.send_url(),
            "https://fcm.googleapis.com/v1/projects/my-project/messages:send"
        );
    }

    #[test]
    fn send_url_uses_custom_base() {
        let client =
            RealFcmClient::new("http://localhost:9099".to_string(), "test-proj".to_string());
        assert_eq!(
            client.send_url(),
            "http://localhost:9099/v1/projects/test-proj/messages:send"
        );
    }

    #[test]
    fn send_url_strips_trailing_slash() {
        let client =
            RealFcmClient::new("http://localhost:9099/".to_string(), "test-proj".to_string());
        assert_eq!(
            client.send_url(),
            "http://localhost:9099/v1/projects/test-proj/messages:send"
        );
    }

    #[test]
    fn wake_payload_has_correct_type() {
        let data = wake_payload();
        assert_eq!(data.message_type, "wake");
    }

    #[test]
    fn renew_payload_has_correct_type() {
        let data = renew_payload();
        assert_eq!(data.message_type, "renew");
    }
}
