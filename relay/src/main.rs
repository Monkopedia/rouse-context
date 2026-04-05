use rouse_relay::config::RelayConfig;
use std::path::Path;
use tracing::{error, info};

#[tokio::main]
async fn main() {
    tracing_subscriber::fmt::init();

    let config_path = std::env::args()
        .nth(1)
        .unwrap_or_else(|| "relay.toml".to_string());

    let config = if Path::new(&config_path).exists() {
        match RelayConfig::from_file_with_env(Path::new(&config_path)) {
            Ok(cfg) => cfg,
            Err(e) => {
                error!("Failed to load config from {config_path}: {e}");
                std::process::exit(1);
            }
        }
    } else {
        info!("No config file found at {config_path}, using defaults");
        RelayConfig::default()
    };

    info!(
        bind_addr = %config.server.bind_addr,
        relay_hostname = %config.server.relay_hostname,
        "Rouse relay starting"
    );

    // TODO: bind TCP listener, accept connections, route by SNI
}
