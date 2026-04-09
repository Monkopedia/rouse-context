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

#[derive(Debug, Clone, Default, Deserialize)]
#[serde(default)]
pub struct FirebaseConfig {
    pub project_id: String,
    pub service_account_path: String,
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
}

#[derive(Debug, Clone, Deserialize)]
#[serde(default)]
pub struct AcmeConfig {
    /// ACME directory URL. Use the staging URL for development/testing.
    pub directory_url: String,
    /// Maximum seconds to wait for DNS TXT record propagation before giving up.
    pub dns_propagation_timeout_secs: u64,
    /// Interval in seconds between DNS propagation checks.
    pub dns_poll_interval_secs: u64,
    /// Path to persist the ACME account private key (PEM format).
    /// If the file exists, the key is loaded from it; otherwise a new key is
    /// generated and saved here.
    pub account_key_path: String,
}

impl Default for AcmeConfig {
    fn default() -> Self {
        Self {
            directory_url: String::new(), // empty means use ACME_DIRECTORY_URL env or LE production
            dns_propagation_timeout_secs: 60,
            dns_poll_interval_secs: 5,
            account_key_path: "/etc/rouse-relay/acme_account_key.pem".to_string(),
        }
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
            max_streams_per_device: 8,
            wake_rate_limit: 6,
            subdomain_rotation_cooldown_days: Some(30),
            fcm_wakeup_timeout_secs: Some(30),
            ws_ping_interval_secs: 30,
            ws_read_timeout_secs: 60,
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
