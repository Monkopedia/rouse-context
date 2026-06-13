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

/// Validate a UnifiedPush endpoint URL before storing it in a [`DeviceRecord`]
/// or using it to send a push.
///
/// Enforces the minimum SSRF-prevention bar required on a multi-tenant relay:
///
/// - **Scheme** must be `https` — prevents plaintext exfiltration and
///   non-HTTP schemes (`file://`, `ftp://`, etc.).
/// - **Loopback** (`127.0.0.0/8`, `::1`, hostname `localhost`) — protects
///   relay-host local services.
/// - **Link-local / cloud-metadata** (`169.254.0.0/16`, `fe80::/10`) —
///   covers GCP/AWS instance-metadata endpoints (`169.254.169.254`).
/// - **RFC 1918 private ranges** (`10.0.0.0/8`, `172.16.0.0/12`,
///   `192.168.0.0/16`) — protects the relay's LAN.
/// - **Internal DNS names** (`.internal`, `.local`, `.localhost` suffixes) —
///   covers `metadata.google.internal`, mDNS names, etc.
/// - **IPv4-mapped IPv6** (e.g. `::ffff:10.0.0.1`) — applies the same v4
///   rules to IPv4 addresses expressed in IPv6 notation.
///
/// Validation happens at store time (called from `/register` and the WS
/// control-message handler); [`RealUnifiedPushClient`] trusts that stored
/// endpoints have already been validated.
pub fn validate_push_endpoint(endpoint: &str) -> Result<(), &'static str> {
    let url = reqwest::Url::parse(endpoint).map_err(|_| "push_endpoint: invalid URL")?;

    if url.scheme() != "https" {
        return Err("push_endpoint: must use https scheme");
    }

    let host_str = url.host_str().ok_or("push_endpoint: missing host")?;

    // The url crate includes surrounding `[` / `]` brackets in host_str() for
    // IPv6 addresses (e.g. `"[::1]"`). Strip them so hostname checks and
    // IpAddr parsing work uniformly.
    let host = host_str
        .strip_prefix('[')
        .and_then(|s| s.strip_suffix(']'))
        .unwrap_or(host_str);

    // Reject reserved / internal hostnames.
    let h = host.to_ascii_lowercase();
    if h == "localhost"
        || h.ends_with(".internal")
        || h.ends_with(".local")
        || h.ends_with(".localhost")
    {
        return Err("push_endpoint: host is a reserved/internal name");
    }

    // Parse as an IP address and reject private/loopback/link-local ranges.
    if let Ok(ip) = host.parse::<std::net::IpAddr>() {
        let blocked = match ip {
            std::net::IpAddr::V4(v4) => {
                v4.is_loopback() || v4.is_private() || v4.is_link_local() || v4.is_unspecified()
            }
            std::net::IpAddr::V6(v6) => {
                v6.is_loopback()
                    || v6.is_unspecified()
                    || is_ipv6_link_local(v6)
                    || v6
                        .to_ipv4_mapped()
                        .map(|v4| {
                            v4.is_loopback()
                                || v4.is_private()
                                || v4.is_link_local()
                                || v4.is_unspecified()
                        })
                        .unwrap_or(false)
            }
        };
        if blocked {
            return Err("push_endpoint: IP address is in a restricted range");
        }
    }

    Ok(())
}

/// Returns `true` when `ip` falls in the IPv6 link-local range (`fe80::/10`).
fn is_ipv6_link_local(ip: std::net::Ipv6Addr) -> bool {
    let octets = ip.octets();
    octets[0] == 0xfe && (octets[1] & 0xc0) == 0x80
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

    // --- validate_push_endpoint ------------------------------------------

    #[test]
    fn validate_accepts_public_https_endpoints() {
        assert!(validate_push_endpoint("https://ntfy.sh/up123").is_ok());
        assert!(validate_push_endpoint("https://up.example.com/token").is_ok());
        assert!(validate_push_endpoint("https://push.gotify.example.org/UP123abc").is_ok());
    }

    #[test]
    fn validate_rejects_http_scheme() {
        assert!(validate_push_endpoint("http://ntfy.sh/x").is_err());
    }

    #[test]
    fn validate_rejects_non_http_schemes() {
        assert!(validate_push_endpoint("file:///etc/passwd").is_err());
        assert!(validate_push_endpoint("ftp://ntfy.sh/x").is_err());
    }

    #[test]
    fn validate_rejects_invalid_url() {
        assert!(validate_push_endpoint("not-a-url").is_err());
        assert!(validate_push_endpoint("").is_err());
    }

    #[test]
    fn validate_rejects_loopback() {
        assert!(validate_push_endpoint("https://127.0.0.1/x").is_err());
        assert!(validate_push_endpoint("https://127.0.0.2/x").is_err());
        assert!(validate_push_endpoint("https://localhost/x").is_err());
        assert!(validate_push_endpoint("https://[::1]/x").is_err());
    }

    #[test]
    fn validate_rejects_link_local_and_metadata() {
        // GCP/AWS instance-metadata address
        assert!(validate_push_endpoint("https://169.254.169.254/metadata").is_err());
        // Other link-local addresses
        assert!(validate_push_endpoint("https://169.254.1.1/x").is_err());
        // IPv6 link-local (fe80::/10)
        assert!(validate_push_endpoint("https://[fe80::1]/x").is_err());
    }

    #[test]
    fn validate_rejects_rfc1918_private_ranges() {
        assert!(validate_push_endpoint("https://10.0.0.1/x").is_err());
        assert!(validate_push_endpoint("https://10.255.255.255/x").is_err());
        assert!(validate_push_endpoint("https://192.168.1.1/x").is_err());
        assert!(validate_push_endpoint("https://172.16.0.1/x").is_err());
        assert!(validate_push_endpoint("https://172.31.255.255/x").is_err());
    }

    #[test]
    fn validate_rejects_ipv4_mapped_ipv6() {
        assert!(validate_push_endpoint("https://[::ffff:10.0.0.1]/x").is_err());
        assert!(validate_push_endpoint("https://[::ffff:192.168.1.1]/x").is_err());
        assert!(validate_push_endpoint("https://[::ffff:127.0.0.1]/x").is_err());
        assert!(validate_push_endpoint("https://[::ffff:169.254.169.254]/x").is_err());
    }

    #[test]
    fn validate_rejects_internal_dns_names() {
        assert!(validate_push_endpoint("https://metadata.google.internal/x").is_err());
        assert!(validate_push_endpoint("https://myhost.local/x").is_err());
        assert!(validate_push_endpoint("https://myhost.localhost/x").is_err());
    }

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
