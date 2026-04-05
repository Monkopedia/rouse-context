//! TLS configuration for the relay's own API endpoint.
//!
//! This is used ONLY for the relay API (wake endpoint, ACME orchestration).
//! Device connections are pure TLS passthrough -- the relay never terminates
//! TLS for device traffic.

use crate::config::TlsConfig;
use std::io;
use std::sync::Arc;

/// Build a rustls `ServerConfig` for the relay API endpoint.
///
/// # Errors
/// Returns an error if the certificate or key files cannot be loaded.
pub fn build_relay_tls_config(config: &TlsConfig) -> io::Result<Arc<rustls::ServerConfig>> {
    let cert_pem = std::fs::read(&config.cert_path).map_err(|e| {
        io::Error::new(
            io::ErrorKind::NotFound,
            format!("failed to read cert at {}: {e}", config.cert_path),
        )
    })?;
    let key_pem = std::fs::read(&config.key_path).map_err(|e| {
        io::Error::new(
            io::ErrorKind::NotFound,
            format!("failed to read key at {}: {e}", config.key_path),
        )
    })?;

    let certs = rustls_pemfile::certs(&mut cert_pem.as_slice())
        .collect::<Result<Vec<_>, _>>()
        .map_err(|e| io::Error::new(io::ErrorKind::InvalidData, format!("bad cert PEM: {e}")))?;

    let key = rustls_pemfile::private_key(&mut key_pem.as_slice())
        .map_err(|e| io::Error::new(io::ErrorKind::InvalidData, format!("bad key PEM: {e}")))?
        .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidData, "no private key found in PEM"))?;

    let tls_config = rustls::ServerConfig::builder()
        .with_no_client_auth()
        .with_single_cert(certs, key)
        .map_err(|e| io::Error::new(io::ErrorKind::InvalidData, format!("TLS config: {e}")))?;

    Ok(Arc::new(tls_config))
}
