//! TLS configuration for the relay's own API endpoint.
//!
//! This is used ONLY for the relay API (wake endpoint, ACME orchestration,
//! device WebSocket connections). Device-to-MCP-client connections are pure
//! TLS passthrough -- the relay never terminates TLS for that traffic.
//!
//! When a CA cert is configured, the relay accepts and verifies client
//! certificates (mTLS) but does not require them at the TLS level. This
//! allows unauthenticated endpoints (/status, /register, etc.) to work
//! without a client cert, while the /ws handler enforces the cert requirement.
//! The device's subdomain is extracted from the client certificate's
//! Common Name (CN) or Subject Alternative Name (SAN).

use crate::config::TlsConfig;
use rustls::pki_types::CertificateDer;
use std::io;
use std::sync::Arc;

/// Build a rustls `ServerConfig` for the relay API endpoint.
///
/// If `ca_cert_path` is set in the config, client certificate verification
/// is enabled (mTLS). Otherwise, no client auth is required.
///
/// # Errors
/// Returns an error if the certificate or key files cannot be loaded.
pub fn build_relay_tls_config(config: &TlsConfig) -> io::Result<Arc<rustls::ServerConfig>> {
    let certs = load_cert_chain(&config.cert_path)?;
    let key = load_private_key(&config.key_path)?;

    let builder = rustls::ServerConfig::builder();

    let tls_config = if !config.ca_cert_path.is_empty() {
        let ca_certs = load_ca_certs(&config.ca_cert_path)?;
        let mut root_store = rustls::RootCertStore::empty();
        for ca in ca_certs {
            root_store.add(ca).map_err(|e| {
                io::Error::new(
                    io::ErrorKind::InvalidData,
                    format!("failed to add CA cert: {e}"),
                )
            })?;
        }
        let verifier = rustls::server::WebPkiClientVerifier::builder(Arc::new(root_store))
            .allow_unauthenticated()
            .build()
            .map_err(|e| {
                io::Error::new(
                    io::ErrorKind::InvalidData,
                    format!("failed to build client verifier: {e}"),
                )
            })?;
        builder
            .with_client_cert_verifier(verifier)
            .with_single_cert(certs, key)
            .map_err(|e| io::Error::new(io::ErrorKind::InvalidData, format!("TLS config: {e}")))?
    } else {
        builder
            .with_no_client_auth()
            .with_single_cert(certs, key)
            .map_err(|e| io::Error::new(io::ErrorKind::InvalidData, format!("TLS config: {e}")))?
    };

    Ok(Arc::new(tls_config))
}

/// Load a PEM certificate chain from a file.
pub fn load_cert_chain(path: &str) -> io::Result<Vec<CertificateDer<'static>>> {
    let pem = std::fs::read(path).map_err(|e| {
        io::Error::new(
            io::ErrorKind::NotFound,
            format!("failed to read cert at {path}: {e}"),
        )
    })?;
    rustls_pemfile::certs(&mut pem.as_slice())
        .collect::<Result<Vec<_>, _>>()
        .map_err(|e| io::Error::new(io::ErrorKind::InvalidData, format!("bad cert PEM: {e}")))
}

/// Load a PEM private key from a file.
pub fn load_private_key(path: &str) -> io::Result<rustls::pki_types::PrivateKeyDer<'static>> {
    let pem = std::fs::read(path).map_err(|e| {
        io::Error::new(
            io::ErrorKind::NotFound,
            format!("failed to read key at {path}: {e}"),
        )
    })?;
    rustls_pemfile::private_key(&mut pem.as_slice())
        .map_err(|e| io::Error::new(io::ErrorKind::InvalidData, format!("bad key PEM: {e}")))?
        .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidData, "no private key found in PEM"))
}

/// Load CA certificates from a PEM file.
fn load_ca_certs(path: &str) -> io::Result<Vec<CertificateDer<'static>>> {
    let pem = std::fs::read(path).map_err(|e| {
        io::Error::new(
            io::ErrorKind::NotFound,
            format!("failed to read CA cert at {path}: {e}"),
        )
    })?;
    rustls_pemfile::certs(&mut pem.as_slice())
        .collect::<Result<Vec<_>, _>>()
        .map_err(|e| io::Error::new(io::ErrorKind::InvalidData, format!("bad CA cert PEM: {e}")))
}

/// Extract the subdomain from a client certificate's Common Name.
///
/// Expects the CN to be in the format `{subdomain}.rousecontext.com` or just
/// the subdomain itself. Returns `None` if no peer cert is present or the CN
/// cannot be parsed.
pub fn extract_subdomain_from_peer_cert(
    peer_certs: &[CertificateDer<'_>],
    base_domain: &str,
) -> Option<String> {
    let cert_der = peer_certs.first()?;

    // Parse the DER certificate to extract subject CN / SANs.
    // We look for a DNS SAN first, then fall back to the CN.
    let (_, cert) = x509_parser::parse_x509_certificate(cert_der.as_ref()).ok()?;

    // Try DNS SANs first
    if let Ok(Some(san_ext)) = cert.subject_alternative_name() {
        for name in &san_ext.value.general_names {
            if let x509_parser::extensions::GeneralName::DNSName(dns) = name {
                if let Some(sub) = extract_subdomain_part(dns, base_domain) {
                    return Some(sub);
                }
            }
        }
    }

    // Fall back to CN
    for attr in cert.subject().iter_common_name() {
        if let Ok(cn) = attr.as_str() {
            if let Some(sub) = extract_subdomain_part(cn, base_domain) {
                return Some(sub);
            }
            // If CN doesn't contain the base domain, treat it as the subdomain itself
            if !cn.contains('.') && !cn.is_empty() {
                return Some(cn.to_string());
            }
        }
    }

    None
}

/// Given a hostname like `abc123.rousecontext.com` and base domain `rousecontext.com`,
/// extract `abc123`. Returns None if the hostname doesn't match the pattern.
fn extract_subdomain_part(hostname: &str, base_domain: &str) -> Option<String> {
    let suffix = format!(".{base_domain}");
    if let Some(sub) = hostname.strip_suffix(&suffix) {
        if !sub.is_empty() && !sub.contains('.') {
            return Some(sub.to_string());
        }
    }
    None
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn extract_subdomain_part_works() {
        assert_eq!(
            extract_subdomain_part("abc123.rousecontext.com", "rousecontext.com"),
            Some("abc123".to_string())
        );
        assert_eq!(
            extract_subdomain_part("rousecontext.com", "rousecontext.com"),
            None
        );
        assert_eq!(
            extract_subdomain_part("a.b.rousecontext.com", "rousecontext.com"),
            None
        );
        assert_eq!(
            extract_subdomain_part("other.example.com", "rousecontext.com"),
            None
        );
    }
}
