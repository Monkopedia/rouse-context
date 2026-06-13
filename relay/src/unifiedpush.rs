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
    /// The endpoint was refused by the SSRF guard — either the URL failed
    /// string-level validation, or the host resolved to an internal IP at
    /// send time (DNS-rebinding defense). The relay never connects in this
    /// case.
    #[error("endpoint blocked: {0}")]
    Blocked(String),
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
/// - **IPv6 unique-local** (`fc00::/7`) — the IPv6 analogue of RFC 1918.
///
/// This is the cheap, string-level first layer of defense. It inspects only
/// the host *string* and cannot catch a public hostname that *resolves* to an
/// internal IP (DNS rebinding). [`RealUnifiedPushClient`] therefore re-runs
/// this check AND resolves + re-validates the host at send time, pinning the
/// connection to the validated IP. Validation also happens at store time
/// (called from `/register` and the WS control-message handler) so obviously
/// bad endpoints are rejected before they are ever stored.
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
        if is_blocked_ip(ip) {
            return Err("push_endpoint: IP address is in a restricted range");
        }
    }

    Ok(())
}

/// Returns `true` when `ip` is in a range the relay must never connect to
/// (loopback, link-local / cloud-metadata, RFC 1918 / ULA private, or the
/// unspecified address). Shared between the cheap string-level
/// [`validate_push_endpoint`] check and the resolve-time check in
/// [`RealUnifiedPushClient`], so both layers block exactly the same ranges.
fn is_blocked_ip(ip: std::net::IpAddr) -> bool {
    match ip {
        std::net::IpAddr::V4(v4) => {
            v4.is_loopback() || v4.is_private() || v4.is_link_local() || v4.is_unspecified()
        }
        std::net::IpAddr::V6(v6) => {
            v6.is_loopback()
                || v6.is_unspecified()
                || is_ipv6_link_local(v6)
                || is_ipv6_unique_local(v6)
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
    }
}

/// Returns `true` when `ip` falls in the IPv6 link-local range (`fe80::/10`).
fn is_ipv6_link_local(ip: std::net::Ipv6Addr) -> bool {
    let octets = ip.octets();
    octets[0] == 0xfe && (octets[1] & 0xc0) == 0x80
}

/// Returns `true` when `ip` falls in the IPv6 unique-local range (`fc00::/7`),
/// the IPv6 equivalent of RFC 1918 private addressing.
fn is_ipv6_unique_local(ip: std::net::Ipv6Addr) -> bool {
    (ip.octets()[0] & 0xfe) == 0xfc
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

/// Per-request timeout for endpoint POSTs. The device only needs a quick
/// acknowledgement; a slow/hung endpoint must not tie up a relay task.
const REQUEST_TIMEOUT: std::time::Duration = std::time::Duration::from_secs(10);

/// Injectable host resolver. Takes the host and port and returns candidate
/// socket addresses. Used only in tests to make the resolve-time SSRF check
/// deterministic without real DNS; production always resolves via the system
/// resolver (`tokio::net::lookup_host`).
type ResolverFn =
    std::sync::Arc<dyn Fn(&str, u16) -> std::io::Result<Vec<std::net::SocketAddr>> + Send + Sync>;

/// Real UnifiedPush client: POSTs the JSON payload to the device's endpoint.
///
/// SSRF hardening (multi-tenant relay POSTing to a device-supplied URL):
///
/// - **Redirects are disabled** ([`reqwest::redirect::Policy::none`]). A
///   validated public endpoint must not be able to `302` us onto an internal
///   address.
/// - **Resolve-time IP check + connection pinning.** At every send we re-run
///   [`validate_push_endpoint`], then resolve the host and reject if *any*
///   resolved address is internal (closing the DNS-rebinding gap that the
///   string check alone cannot). We then pin the connection to the exact
///   address we validated via [`reqwest::ClientBuilder::resolve`], so a
///   concurrent re-resolve cannot swap in an internal IP (TOCTOU).
pub struct RealUnifiedPushClient {
    /// Base client used for the non-guarded test path. Redirects disabled.
    http: reqwest::Client,
    /// When `true` (always, in production) the resolve-time SSRF guard runs.
    /// Only test constructors set this `false`.
    enforce_guard: bool,
    /// Test-only resolver override; `None` in production.
    resolver: Option<ResolverFn>,
}

impl RealUnifiedPushClient {
    pub fn new() -> Self {
        Self {
            http: Self::build_client(None),
            enforce_guard: true,
            resolver: None,
        }
    }

    /// Build a reqwest client with redirects disabled and a request timeout.
    /// When `pin` is `Some((host, addr))`, DNS for `host` is overridden to
    /// `addr` so the connection cannot be re-resolved to a different IP.
    fn build_client(pin: Option<(&str, std::net::SocketAddr)>) -> reqwest::Client {
        let mut builder = reqwest::Client::builder()
            .redirect(reqwest::redirect::Policy::none())
            .timeout(REQUEST_TIMEOUT);
        if let Some((host, addr)) = pin {
            builder = builder.resolve(host, addr);
        }
        builder
            .build()
            .expect("reqwest client builder with static config must succeed")
    }

    async fn resolve_host(
        &self,
        host: &str,
        port: u16,
    ) -> std::io::Result<Vec<std::net::SocketAddr>> {
        if let Some(resolver) = &self.resolver {
            resolver(host, port)
        } else {
            Ok(tokio::net::lookup_host((host, port)).await?.collect())
        }
    }

    /// Re-validate `endpoint` at send time, resolve it, reject any internal
    /// resolved IP, and return a client pinned to the validated address.
    async fn guarded_client(&self, endpoint: &str) -> Result<reqwest::Client, UnifiedPushError> {
        // Cheap string-level layer first (also catches literal-IP endpoints).
        validate_push_endpoint(endpoint).map_err(|e| UnifiedPushError::Blocked(e.to_string()))?;

        let url = reqwest::Url::parse(endpoint)
            .map_err(|_| UnifiedPushError::Blocked("invalid URL".to_string()))?;
        let host_str = url
            .host_str()
            .ok_or_else(|| UnifiedPushError::Blocked("missing host".to_string()))?;
        let host = host_str
            .strip_prefix('[')
            .and_then(|s| s.strip_suffix(']'))
            .unwrap_or(host_str);
        let port = url.port_or_known_default().unwrap_or(443);

        let addrs = self
            .resolve_host(host, port)
            .await
            .map_err(|e| UnifiedPushError::Blocked(format!("DNS resolution failed: {e}")))?;
        if addrs.is_empty() {
            return Err(UnifiedPushError::Blocked(
                "DNS resolution returned no addresses".to_string(),
            ));
        }
        for addr in &addrs {
            if is_blocked_ip(addr.ip()) {
                return Err(UnifiedPushError::Blocked(format!(
                    "host resolved to restricted IP {}",
                    addr.ip()
                )));
            }
        }

        // Pin to the exact address we validated to avoid a TOCTOU re-resolve.
        Ok(Self::build_client(Some((host, addrs[0]))))
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
        let client = if self.enforce_guard {
            self.guarded_client(endpoint).await?
        } else {
            self.http.clone()
        };

        let resp = client
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

/// Test-only constructors that relax the SSRF guard so tests can drive the
/// HTTP/redirect behavior against a loopback server, or inject a deterministic
/// resolver to exercise the resolve-time check without real DNS.
#[cfg(test)]
impl RealUnifiedPushClient {
    /// Guard disabled: POSTs straight to `endpoint` (used by tests that hit a
    /// loopback capture server). Redirects are still disabled.
    fn unguarded() -> Self {
        Self {
            http: Self::build_client(None),
            enforce_guard: false,
            resolver: None,
        }
    }

    /// Guard enabled with an injected resolver, for deterministic resolve-time
    /// SSRF checks.
    fn with_resolver(resolver: ResolverFn) -> Self {
        Self {
            http: Self::build_client(None),
            enforce_guard: true,
            resolver: Some(resolver),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::fcm::{renew_payload, wake_payload};
    use axum::routing::post;
    use axum::Router;
    use std::net::SocketAddr;
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
        let client = RealUnifiedPushClient::unguarded();
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
        let client = RealUnifiedPushClient::unguarded();
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

        let client = RealUnifiedPushClient::unguarded();
        let err = client
            .send_data_message(&endpoint, &wake_payload())
            .await
            .expect_err("502 must surface as an error");
        assert!(matches!(err, UnifiedPushError::Rejected(_)));
    }

    // --- resolve-time SSRF guard (DNS-rebinding) --------------------------

    #[tokio::test]
    async fn send_rejects_hostname_resolving_to_loopback() {
        // A public-looking hostname that passes string validation but resolves
        // to a loopback address must be blocked before any connection is made.
        let resolver: ResolverFn =
            Arc::new(|_host, port| Ok(vec![SocketAddr::from(([127, 0, 0, 1], port))]));
        let client = RealUnifiedPushClient::with_resolver(resolver);

        let err = client
            .send_data_message("https://rebind.example.com/up", &wake_payload())
            .await
            .expect_err("host resolving to loopback must be rejected");
        assert!(matches!(err, UnifiedPushError::Blocked(_)), "got {err:?}");
    }

    #[tokio::test]
    async fn send_rejects_hostname_resolving_to_private_and_metadata() {
        for ip in [[10, 0, 0, 5], [192, 168, 1, 9], [169, 254, 169, 254]] {
            let resolver: ResolverFn =
                Arc::new(move |_host, port| Ok(vec![SocketAddr::from((ip, port))]));
            let client = RealUnifiedPushClient::with_resolver(resolver);
            let err = client
                .send_data_message("https://rebind.example.com/up", &wake_payload())
                .await
                .expect_err("host resolving to internal IP must be rejected");
            assert!(
                matches!(err, UnifiedPushError::Blocked(_)),
                "ip {ip:?}: {err:?}"
            );
        }
    }

    #[tokio::test]
    async fn send_rejects_literal_internal_ip_via_string_layer() {
        // The cheap string layer still rejects literal-IP endpoints at send.
        let client = RealUnifiedPushClient::new();
        let err = client
            .send_data_message("https://10.0.0.1/up", &wake_payload())
            .await
            .expect_err("literal private IP must be rejected");
        assert!(matches!(err, UnifiedPushError::Blocked(_)), "got {err:?}");
    }

    // --- redirect handling ------------------------------------------------

    #[tokio::test]
    async fn redirect_is_not_followed() {
        // A target capture server that must NEVER receive the POST.
        let (target, captured) = spawn_capture_server().await;

        // A redirector that 302s to the target.
        let location = target.clone();
        let app = Router::new().route(
            "/push",
            post(move || {
                let location = location.clone();
                async move {
                    (
                        axum::http::StatusCode::FOUND,
                        [(axum::http::header::LOCATION, location)],
                    )
                }
            }),
        );
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let addr = listener.local_addr().unwrap();
        tokio::spawn(async move {
            axum::serve(listener, app).await.unwrap();
        });
        let endpoint = format!("http://127.0.0.1:{}/push", addr.port());

        // unguarded so the loopback redirector is reachable; the point under
        // test is the redirect policy, not the IP guard.
        let client = RealUnifiedPushClient::unguarded();
        let err = client
            .send_data_message(&endpoint, &wake_payload())
            .await
            .expect_err("302 must not be followed; the 3xx surfaces as an error");
        assert!(matches!(err, UnifiedPushError::Rejected(_)), "got {err:?}");

        // The redirect target was never POSTed to.
        assert!(
            captured.lock().unwrap().is_empty(),
            "redirect target must not receive the payload"
        );
    }
}
