use serde::Deserialize;
use std::path::Path;
use thiserror::Error;

#[derive(Debug, Error)]
pub enum ConfigError {
    #[error("failed to read config file: {0}")]
    Io(#[from] std::io::Error),
    #[error("failed to parse TOML: {0}")]
    Parse(#[from] toml::de::Error),
    #[error("environment variable '{0}' is not set")]
    MissingEnvVar(String),
    #[error("invalid base64url for ACME EAB HMAC: {0}")]
    InvalidEabHmac(String),
}

#[derive(Debug, Clone, Deserialize)]
#[serde(default)]
pub struct RelayConfig {
    pub server: ServerConfig,
    pub tls: TlsConfig,
    pub firebase: FirebaseConfig,
    pub cloudflare: CloudflareConfig,
    pub limits: LimitsConfig,
    pub acme: AcmeConfig,
    pub device_ca: DeviceCaConfig,
    /// Known integration names. Each device gets a per-integration secret
    /// in the format `{adjective}-{integration}`.
    pub integrations: Vec<String>,
}

impl Default for RelayConfig {
    fn default() -> Self {
        Self {
            server: ServerConfig::default(),
            tls: TlsConfig::default(),
            firebase: FirebaseConfig::default(),
            cloudflare: CloudflareConfig::default(),
            limits: LimitsConfig::default(),
            acme: AcmeConfig::default(),
            device_ca: DeviceCaConfig::default(),
            integrations: default_integrations(),
        }
    }
}

fn default_integrations() -> Vec<String> {
    vec![
        "health".to_string(),
        "outreach".to_string(),
        "notifications".to_string(),
        "usage".to_string(),
        "test".to_string(),
    ]
}

#[derive(Debug, Clone, Deserialize)]
#[serde(default)]
pub struct ServerConfig {
    pub bind_addr: String,
    pub relay_hostname: String,
    /// Base domain under which all devices are provisioned. Every device gets a
    /// unique subdomain `{subdomain}.{base_domain}`, and per-integration hostnames
    /// take the form `{secret}.{subdomain}.{base_domain}`.
    ///
    /// If empty, derived from `relay_hostname` by stripping a leading `"relay."`
    /// (so `relay_hostname = "relay.example.com"` yields `base_domain = "example.com"`).
    /// Set explicitly when your relay hostname doesn't follow that convention.
    pub base_domain: String,
    /// Additional base domains used by `POST /request-subdomain` for the
    /// multi-domain release valve. When multiple domains are configured, a new
    /// reservation is spread across them (currently: simple round-robin by
    /// density; future: weighted by ACME quota remaining). When empty, the
    /// single `resolved_base_domain()` is used.
    ///
    /// The primary `base_domain` is always included implicitly.
    #[serde(default)]
    pub additional_base_domains: Vec<String>,
    /// Maximum entries retained in the in-memory `valid_secrets_cache` before
    /// LRU eviction kicks in. Bounded to prevent unbounded growth as new
    /// devices connect over the lifetime of a long-running relay (see #109).
    ///
    /// At roughly 100 bytes per entry, the default cap of 10_000 costs about
    /// 1 MB. Evicted entries simply re-read from Firestore on the next hit,
    /// so the only cost of a smaller cap is slightly more Firestore traffic
    /// for long-idle devices.
    pub cache_capacity: usize,
}

impl ServerConfig {
    /// Resolve the effective base domain. Falls back to stripping `"relay."` from
    /// `relay_hostname` when `base_domain` is empty.
    pub fn resolved_base_domain(&self) -> String {
        if !self.base_domain.is_empty() {
            return self.base_domain.clone();
        }
        self.relay_hostname
            .strip_prefix("relay.")
            .unwrap_or(&self.relay_hostname)
            .to_string()
    }

    /// Return the full list of base domains available to the release valve.
    /// The primary resolved base domain is always first.
    pub fn all_base_domains(&self) -> Vec<String> {
        let mut domains = vec![self.resolved_base_domain()];
        for extra in &self.additional_base_domains {
            if !extra.is_empty() && !domains.contains(extra) {
                domains.push(extra.clone());
            }
        }
        domains
    }
}

#[derive(Debug, Clone, Default, Deserialize)]
#[serde(default)]
pub struct TlsConfig {
    pub cert_path: String,
    pub key_path: String,
    /// Path to the CA certificate used to verify device client certificates (mTLS).
    /// If empty, client certificate verification is disabled.
    pub ca_cert_path: String,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(default)]
pub struct FirebaseConfig {
    pub project_id: String,
    pub service_account_path: String,
    /// If `false`, the relay accepts any Firebase ID token without verifying
    /// its signature, deriving the `uid` claim from the token string itself.
    /// This is ONLY intended for integration tests that run the real relay
    /// binary but cannot mint real Firebase tokens. It must NEVER be disabled
    /// in production: doing so lets anyone register a device under any UID.
    ///
    /// Defaults to `true`. Settable only from the config file; there is no
    /// HTTP surface that can flip this at runtime. A loud WARN is emitted
    /// at startup when disabled so it can't be missed in logs.
    pub verify_tokens: bool,
}

impl Default for FirebaseConfig {
    fn default() -> Self {
        Self {
            project_id: String::new(),
            service_account_path: String::new(),
            verify_tokens: true,
        }
    }
}

#[derive(Debug, Clone, Deserialize)]
#[serde(default)]
pub struct CloudflareConfig {
    pub zone_id: String,
    pub api_token_env: String,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(default)]
pub struct LimitsConfig {
    pub max_streams_per_device: u32,
    pub wake_rate_limit: u32,
    pub subdomain_rotation_cooldown_days: Option<u32>,
    pub fcm_wakeup_timeout_secs: Option<u64>,
    /// How often to send WebSocket Ping frames to detect stale device connections (seconds).
    pub ws_ping_interval_secs: u64,
    /// If no WebSocket message (including Pong) is received within this many seconds,
    /// consider the device connection dead and tear down the session.
    pub ws_read_timeout_secs: u64,
    /// Days since `registered_at` before a device is considered stale and swept
    /// from Firestore and DNS. Default: 180.
    pub stale_device_sweep_days: u64,
    /// How long a subdomain reservation remains valid after `POST /request-subdomain`.
    /// Must be long enough for the device to generate a CSR and call
    /// `/register/certs`. Default: 600 (10 minutes).
    pub subdomain_reservation_ttl_secs: u64,
    /// Per-Firebase-UID burst size for `POST /request-subdomain`. Default: 3.
    /// Prevents pool enumeration.
    pub request_subdomain_rate_burst: u32,
    /// Refill interval (seconds) for the per-UID request-subdomain limiter.
    /// Default: 20 (one token every 20s, so 3/min sustained).
    pub request_subdomain_rate_refill_secs: u64,
    /// Per-(source IP, subdomain) sliding-window connection cap for
    /// passthrough connections. Default: 200. Note that legitimate AI-client
    /// traffic for a given user typically arrives from a single backend IP
    /// (e.g. Anthropic's MCP gateway), so this limit is effectively per-user
    /// rather than per-attacker. Claude integration setup can fire 10+
    /// connections within a second; 200/min is comfortably above realistic
    /// bursts while still blocking naive scanners.
    pub conn_rate_limit_max: u32,
    /// Window size (seconds) for the per-(source IP, subdomain) connection
    /// limiter. Default: 60.
    pub conn_rate_limit_window_secs: u64,
    /// If true, `handle_ws` will auto-create a minimal Firestore device record
    /// when a mTLS-authenticated device connects with no existing record.
    ///
    /// MUST remain `false` in production. The auto-create path silently
    /// resurrects a Firestore record with empty `firebase_uid` and no
    /// `valid_secrets` based on the client certificate alone -- if a device's
    /// mTLS cert is ever compromised or revoked, an attacker could use it to
    /// recreate a record under that subdomain. Genuine devices register via
    /// `/register` -> `/register/certs` before ever connecting to `/ws`.
    ///
    /// The flag exists only to keep the test harness convenient: tests that
    /// construct `InMemoryFirestore` and connect a simulated device without
    /// first calling `/register` rely on this behaviour.
    #[serde(default)]
    pub allow_ws_auto_create_device: bool,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(default)]
pub struct AcmeConfig {
    /// ACME directory URL.
    ///
    /// Defaults to Google Trust Services (GTS) production:
    /// `https://dv.acme-v02.api.pki.goog/directory`. GTS offers 100 new orders
    /// per hour (~16,800/week) vs Let's Encrypt's 50/week/registered-domain,
    /// and is already in every browser/OS root store.
    ///
    /// Override with the LE URL (`https://acme-v02.api.letsencrypt.org/directory`)
    /// if you want to keep issuing from Let's Encrypt. Both use the same ACME
    /// protocol; GTS additionally requires `externalAccountBinding` on
    /// account registration (see `external_account_binding_*_env`).
    pub directory_url: String,
    /// Maximum seconds to wait for DNS TXT record propagation before giving up.
    pub dns_propagation_timeout_secs: u64,
    /// Interval in seconds between DNS propagation checks.
    pub dns_poll_interval_secs: u64,
    /// Path to persist the ACME account private key (PEM format).
    /// If the file exists, the key is loaded from it; otherwise a new key is
    /// generated and saved here.
    ///
    /// Defaults to a GTS-specific filename (`gts_acme_account_key.pem`) so
    /// switching between ACME providers does not reuse the same account —
    /// ACME accounts are bound to a specific directory and not portable.
    pub account_key_path: String,
    /// If true, startup fails when `account_key_path` does not exist instead of
    /// silently generating a new key. Enable in production to guard against
    /// deploy misconfigurations that would rotate the ACME account and burn
    /// rate-limit headroom. Default: false, so fresh installs can bootstrap
    /// without manual setup.
    pub require_existing_account: bool,
    /// Name of the environment variable holding the External Account Binding
    /// (EAB) `kid` (key identifier) issued by the ACME provider.
    ///
    /// Required by GTS (`publicca.googleapis.com`) on account registration:
    /// the EAB proves the ACME account is owned by an authenticated GCP
    /// project. Not required by Let's Encrypt — leave unset (empty) to skip
    /// the EAB branch entirely.
    ///
    /// See RFC 8555 §7.3.4.
    pub external_account_binding_kid_env: String,
    /// Name of the environment variable holding the base64url-encoded
    /// HMAC-SHA256 key for the External Account Binding. Paired with
    /// `external_account_binding_kid_env`.
    pub external_account_binding_hmac_env: String,
    /// If true, the relay exits with a non-zero status when the startup CAA
    /// check finds the configured ACME provider missing from the base
    /// domain's CAA `issue` allowlist. When false (default), a mismatch is
    /// logged as an ERROR but the relay continues to run.
    ///
    /// A CAA mismatch is a latent cert-renewal failure: the cert in use is
    /// fine, but the next renewal at +60-90 days will be refused by the CA.
    /// Strict mode is opt-in because a transient DNS glitch at startup
    /// should not crash-loop production — see issue #322.
    #[serde(default)]
    pub fail_on_caa_mismatch: bool,
}

impl Default for AcmeConfig {
    fn default() -> Self {
        Self {
            // Empty means "use GTS production" (resolved at startup). Kept as
            // empty-string default so env var and config-file overrides both
            // take precedence without special-casing.
            directory_url: String::new(),
            dns_propagation_timeout_secs: 60,
            dns_poll_interval_secs: 5,
            account_key_path: "/etc/rouse-relay/gts_acme_account_key.pem".to_string(),
            require_existing_account: false,
            external_account_binding_kid_env: "GTS_EAB_KID".to_string(),
            external_account_binding_hmac_env: "GTS_EAB_HMAC".to_string(),
            fail_on_caa_mismatch: false,
        }
    }
}

impl AcmeConfig {
    /// Resolve the External Account Binding credentials from the configured
    /// environment variables. Returns `Ok(None)` when either env var is unset
    /// or empty — this is the correct behaviour for ACME providers (like
    /// Let's Encrypt) that do not require EAB. Returns `Err` when the HMAC
    /// env var is set but not valid base64url.
    ///
    /// The HMAC is base64url-decoded here; ACME providers issue it in that
    /// encoding (both GTS's `publicca.googleapis.com` and ZeroSSL).
    pub fn resolve_eab(&self) -> Result<Option<(String, Vec<u8>)>, ConfigError> {
        if self.external_account_binding_kid_env.is_empty()
            || self.external_account_binding_hmac_env.is_empty()
        {
            return Ok(None);
        }
        let kid = match std::env::var(&self.external_account_binding_kid_env) {
            Ok(v) if !v.is_empty() => v,
            _ => return Ok(None),
        };
        let hmac_b64 = match std::env::var(&self.external_account_binding_hmac_env) {
            Ok(v) if !v.is_empty() => v,
            _ => return Ok(None),
        };
        use base64::Engine as _;
        let hmac_key = base64::engine::general_purpose::URL_SAFE_NO_PAD
            .decode(hmac_b64.trim())
            .map_err(|e| ConfigError::InvalidEabHmac(e.to_string()))?;
        Ok(Some((kid, hmac_key)))
    }
}

#[derive(Debug, Clone, Deserialize)]
#[serde(default)]
pub struct DeviceCaConfig {
    /// Path to the device CA private key (PEM format).
    pub ca_key_path: String,
    /// Path to the device CA certificate (PEM format).
    pub ca_cert_path: String,
}

impl Default for DeviceCaConfig {
    fn default() -> Self {
        Self {
            ca_key_path: "/etc/rouse-relay/device_ca_key.pem".to_string(),
            ca_cert_path: "/etc/rouse-relay/device_ca_cert.pem".to_string(),
        }
    }
}

impl Default for ServerConfig {
    fn default() -> Self {
        Self {
            bind_addr: "0.0.0.0:443".to_string(),
            relay_hostname: "relay.rousecontext.com".to_string(),
            base_domain: String::new(),
            additional_base_domains: Vec::new(),
            cache_capacity: 10_000,
        }
    }
}

impl Default for CloudflareConfig {
    fn default() -> Self {
        Self {
            zone_id: String::new(),
            api_token_env: "CF_API_TOKEN".to_string(),
        }
    }
}

impl Default for LimitsConfig {
    fn default() -> Self {
        Self {
            max_streams_per_device: 32,
            wake_rate_limit: 6,
            subdomain_rotation_cooldown_days: Some(30),
            fcm_wakeup_timeout_secs: Some(30),
            ws_ping_interval_secs: 30,
            ws_read_timeout_secs: 60,
            stale_device_sweep_days: 180,
            subdomain_reservation_ttl_secs: 600,
            request_subdomain_rate_burst: 3,
            request_subdomain_rate_refill_secs: 20,
            conn_rate_limit_max: 200,
            conn_rate_limit_window_secs: 60,
            allow_ws_auto_create_device: false,
        }
    }
}

impl RelayConfig {
    /// Load config from a TOML file. Missing fields use defaults.
    pub fn from_file(path: &Path) -> Result<Self, ConfigError> {
        let contents = std::fs::read_to_string(path)?;
        let config: RelayConfig = toml::from_str(&contents)?;
        Ok(config)
    }

    /// Load config from a TOML file, then apply environment variable overrides.
    pub fn from_file_with_env(path: &Path) -> Result<Self, ConfigError> {
        let mut config = Self::from_file(path)?;
        config.apply_env_overrides();
        Ok(config)
    }

    /// Apply environment variable overrides to the config.
    pub fn apply_env_overrides(&mut self) {
        if let Ok(val) = std::env::var("RELAY_BIND_ADDR") {
            self.server.bind_addr = val;
        }
        if let Ok(val) = std::env::var("RELAY_HOSTNAME") {
            self.server.relay_hostname = val;
        }
        if let Ok(val) = std::env::var("RELAY_BASE_DOMAIN") {
            self.server.base_domain = val;
        }
        if let Ok(val) = std::env::var("RELAY_TLS_CERT_PATH") {
            self.tls.cert_path = val;
        }
        if let Ok(val) = std::env::var("RELAY_TLS_KEY_PATH") {
            self.tls.key_path = val;
        }
        if let Ok(val) = std::env::var("RELAY_FIREBASE_PROJECT_ID") {
            self.firebase.project_id = val;
        }
        if let Ok(val) = std::env::var("RELAY_CF_ZONE_ID") {
            self.cloudflare.zone_id = val;
        }
        if let Ok(val) = std::env::var("ACME_DIRECTORY_URL") {
            self.acme.directory_url = val;
        }
        if let Ok(val) = std::env::var("ACME_ACCOUNT_KEY_PATH") {
            self.acme.account_key_path = val;
        }
        if let Ok(val) = std::env::var("ACME_REQUIRE_EXISTING_ACCOUNT") {
            // Accept "1", "true", "yes" (case-insensitive) as true.
            let v = val.trim().to_ascii_lowercase();
            self.acme.require_existing_account = matches!(v.as_str(), "1" | "true" | "yes");
        }
        if let Ok(val) = std::env::var("ACME_EAB_KID_ENV") {
            self.acme.external_account_binding_kid_env = val;
        }
        if let Ok(val) = std::env::var("ACME_EAB_HMAC_ENV") {
            self.acme.external_account_binding_hmac_env = val;
        }
        if let Ok(val) = std::env::var("DEVICE_CA_KEY_PATH") {
            self.device_ca.ca_key_path = val;
        }
        if let Ok(val) = std::env::var("DEVICE_CA_CERT_PATH") {
            self.device_ca.ca_cert_path = val;
        }
    }
}

impl CloudflareConfig {
    /// Resolve the API token by reading the environment variable named in `api_token_env`.
    pub fn resolve_api_token(&self) -> Result<String, ConfigError> {
        std::env::var(&self.api_token_env)
            .map_err(|_| ConfigError::MissingEnvVar(self.api_token_env.clone()))
    }
}
