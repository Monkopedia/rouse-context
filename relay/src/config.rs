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

#[derive(Debug, Clone, Default, Deserialize)]
#[serde(default)]
pub struct RelayConfig {
    pub server: ServerConfig,
    pub tls: TlsConfig,
    pub firebase: FirebaseConfig,
    pub cloudflare: CloudflareConfig,
    pub limits: LimitsConfig,
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
    fn apply_env_overrides(&mut self) {
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
    }
}

impl CloudflareConfig {
    /// Resolve the API token by reading the environment variable named in `api_token_env`.
    pub fn resolve_api_token(&self) -> Result<String, ConfigError> {
        std::env::var(&self.api_token_env)
            .map_err(|_| ConfigError::MissingEnvVar(self.api_token_env.clone()))
    }
}
