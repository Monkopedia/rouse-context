//! UnifiedPush client abstraction.
//!
//! Delivers wake/renew data messages to `foss`-flavor devices by HTTP POST to
//! the device's stored UnifiedPush endpoint URL. Unlike FCM there is no OAuth
//! and no service account — the endpoint URL itself is the capability, so the
//! client is a thin HTTP wrapper.
//!
//! The payload is the same [`FcmData`] the FCM path uses (`{"type":"wake"}` /
//! `{"type":"renew"}`) so the device's `FcmDispatch.resolve` handles both
//! transports uniformly. The trait mirrors [`crate::fcm::FcmClient`] so the two
//! senders route behind a common layer (see [`crate::push`]).

use crate::fcm::FcmData;
use async_trait::async_trait;
use thiserror::Error;

#[derive(Debug, Error)]
pub enum UnifiedPushError {
    #[error("http error: {0}")]
    Http(String),
    #[error("endpoint rejected delivery: {0}")]
    Rejected(String),
}

#[async_trait]
pub trait UnifiedPushClient: Send + Sync {
    /// POST a data message to a device's UnifiedPush endpoint URL.
    async fn send_data_message(
        &self,
        endpoint: &str,
        data: &FcmData,
    ) -> Result<(), UnifiedPushError>;
}

/// Real UnifiedPush client: POSTs the JSON payload to the device's endpoint.
pub struct RealUnifiedPushClient {
    http: reqwest::Client,
}

impl RealUnifiedPushClient {
    pub fn new() -> Self {
        Self {
            http: reqwest::Client::new(),
        }
    }
}

impl Default for RealUnifiedPushClient {
    fn default() -> Self {
        Self::new()
    }
}

#[async_trait]
impl UnifiedPushClient for RealUnifiedPushClient {
    async fn send_data_message(
        &self,
        endpoint: &str,
        data: &FcmData,
    ) -> Result<(), UnifiedPushError> {
        let resp = self
            .http
            .post(endpoint)
            .json(data)
            .send()
            .await
            .map_err(|e| UnifiedPushError::Http(e.to_string()))?;

        let status = resp.status();
        if status.is_success() {
            Ok(())
        } else {
            let text = resp.text().await.unwrap_or_default();
            Err(UnifiedPushError::Rejected(format!("{status}: {text}")))
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::fcm::{renew_payload, wake_payload};
    use axum::routing::post;
    use axum::Router;
    use std::sync::{Arc, Mutex};
    use tokio::net::TcpListener;

    /// Spin up a tiny HTTP server that records the JSON body of POSTs to
    /// `/push`. Returns the base URL and the shared capture buffer.
    async fn spawn_capture_server() -> (String, Arc<Mutex<Vec<serde_json::Value>>>) {
        let captured: Arc<Mutex<Vec<serde_json::Value>>> = Arc::new(Mutex::new(Vec::new()));
        let cap = captured.clone();
        let app = Router::new().route(
            "/push",
            post(move |body: axum::body::Bytes| {
                let cap = cap.clone();
                async move {
                    let v: serde_json::Value = serde_json::from_slice(&body).unwrap();
                    cap.lock().unwrap().push(v);
                    axum::http::StatusCode::OK
                }
            }),
        );
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let addr = listener.local_addr().unwrap();
        tokio::spawn(async move {
            axum::serve(listener, app).await.unwrap();
        });
        (format!("http://127.0.0.1:{}/push", addr.port()), captured)
    }

    #[tokio::test]
    async fn posts_wake_payload_to_endpoint() {
        let (endpoint, captured) = spawn_capture_server().await;
        let client = RealUnifiedPushClient::new();
        client
            .send_data_message(&endpoint, &wake_payload())
            .await
            .expect("wake POST should succeed");
        let got = captured.lock().unwrap();
        assert_eq!(got.len(), 1);
        assert_eq!(got[0], serde_json::json!({ "type": "wake" }));
    }

    #[tokio::test]
    async fn posts_renew_payload_to_endpoint() {
        let (endpoint, captured) = spawn_capture_server().await;
        let client = RealUnifiedPushClient::new();
        client
            .send_data_message(&endpoint, &renew_payload())
            .await
            .expect("renew POST should succeed");
        let got = captured.lock().unwrap();
        assert_eq!(got.len(), 1);
        assert_eq!(got[0], serde_json::json!({ "type": "renew" }));
    }

    #[tokio::test]
    async fn non_success_status_is_rejected_error() {
        // Server that always returns 502.
        let app = Router::new().route(
            "/push",
            post(|| async { axum::http::StatusCode::BAD_GATEWAY }),
        );
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let addr = listener.local_addr().unwrap();
        tokio::spawn(async move {
            axum::serve(listener, app).await.unwrap();
        });
        let endpoint = format!("http://127.0.0.1:{}/push", addr.port());

        let client = RealUnifiedPushClient::new();
        let err = client
            .send_data_message(&endpoint, &wake_payload())
            .await
            .expect_err("502 must surface as an error");
        assert!(matches!(err, UnifiedPushError::Rejected(_)));
    }
}
