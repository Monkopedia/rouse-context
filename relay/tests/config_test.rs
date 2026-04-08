use rouse_relay::config::RelayConfig;
use std::io::Write;
use tempfile::NamedTempFile;

fn write_toml(content: &str) -> NamedTempFile {
    let mut f = NamedTempFile::new().unwrap();
    f.write_all(content.as_bytes()).unwrap();
    f
}

const VALID_TOML: &str = r#"
[server]
bind_addr = "0.0.0.0:8443"
relay_hostname = "relay.rousecontext.com"

[tls]
cert_path = "/etc/relay/cert.pem"
key_path = "/etc/relay/key.pem"

[firebase]
project_id = "rouse-context"
service_account_path = "/etc/relay/firebase-sa.json"

[cloudflare]
zone_id = "test-zone-id"
api_token_env = "CF_API_TOKEN"

[limits]
max_streams_per_device = 8
wake_rate_limit = 6
"#;

#[test]
fn parse_valid_toml() {
    let f = write_toml(VALID_TOML);
    let cfg = RelayConfig::from_file(f.path()).unwrap();
    assert_eq!(cfg.server.bind_addr, "0.0.0.0:8443");
    assert_eq!(cfg.server.relay_hostname, "relay.rousecontext.com");
    assert_eq!(cfg.tls.cert_path, "/etc/relay/cert.pem");
    assert_eq!(cfg.tls.key_path, "/etc/relay/key.pem");
    assert_eq!(cfg.firebase.project_id, "rouse-context");
    assert_eq!(cfg.cloudflare.zone_id, "test-zone-id");
    assert_eq!(cfg.cloudflare.api_token_env, "CF_API_TOKEN");
    assert_eq!(cfg.limits.max_streams_per_device, 8);
    assert_eq!(cfg.limits.wake_rate_limit, 6);
}

#[test]
fn missing_file_uses_defaults() {
    let cfg = RelayConfig::default();
    assert_eq!(cfg.server.bind_addr, "0.0.0.0:443");
    assert_eq!(cfg.limits.max_streams_per_device, 8);
    assert_eq!(cfg.limits.wake_rate_limit, 6);
    // Default integrations
    assert_eq!(
        cfg.integrations,
        vec!["health", "outreach", "notifications", "usage"]
    );
}

#[test]
fn integrations_from_toml() {
    let toml = r#"
integrations = ["health", "calendar"]
"#;
    let f = write_toml(toml);
    let cfg = RelayConfig::from_file(f.path()).unwrap();
    assert_eq!(cfg.integrations, vec!["health", "calendar"]);
}

#[test]
fn env_var_overrides_toml() {
    let f = write_toml(VALID_TOML);
    // Set env var that should override bind_addr
    std::env::set_var("RELAY_BIND_ADDR", "127.0.0.1:9443");
    let cfg = RelayConfig::from_file_with_env(f.path()).unwrap();
    assert_eq!(cfg.server.bind_addr, "127.0.0.1:9443");
    std::env::remove_var("RELAY_BIND_ADDR");
}

#[test]
fn env_var_overrides_relay_hostname() {
    let f = write_toml(VALID_TOML);
    std::env::set_var("RELAY_HOSTNAME", "test.example.com");
    let cfg = RelayConfig::from_file_with_env(f.path()).unwrap();
    assert_eq!(cfg.server.relay_hostname, "test.example.com");
    std::env::remove_var("RELAY_HOSTNAME");
}

#[test]
fn missing_required_cloudflare_token_env_var_fails() {
    let f = write_toml(VALID_TOML);
    let cfg = RelayConfig::from_file(f.path()).unwrap();
    // The api_token_env field names an env var. Resolving it should fail
    // if that env var is not set.
    std::env::remove_var("CF_API_TOKEN");
    let result = cfg.cloudflare.resolve_api_token();
    assert!(result.is_err());
    let err_msg = result.unwrap_err().to_string();
    assert!(
        err_msg.contains("CF_API_TOKEN"),
        "Error should mention the missing env var name, got: {err_msg}"
    );
}

#[test]
fn resolve_cloudflare_token_succeeds_when_set() {
    let f = write_toml(VALID_TOML);
    let cfg = RelayConfig::from_file(f.path()).unwrap();
    std::env::set_var("CF_API_TOKEN", "test-token-value");
    let token = cfg.cloudflare.resolve_api_token().unwrap();
    assert_eq!(token, "test-token-value");
    std::env::remove_var("CF_API_TOKEN");
}

#[test]
fn partial_toml_fills_defaults() {
    let partial = r#"
[server]
relay_hostname = "custom.example.com"
"#;
    let f = write_toml(partial);
    let cfg = RelayConfig::from_file(f.path()).unwrap();
    // relay_hostname from file
    assert_eq!(cfg.server.relay_hostname, "custom.example.com");
    // bind_addr should be default
    assert_eq!(cfg.server.bind_addr, "0.0.0.0:443");
    // limits should be defaults
    assert_eq!(cfg.limits.max_streams_per_device, 8);
    assert_eq!(cfg.limits.wake_rate_limit, 6);
}
