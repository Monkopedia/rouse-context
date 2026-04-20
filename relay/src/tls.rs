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
    use rcgen::{CertificateParams, DistinguishedName, DnType, KeyPair};
    use std::path::PathBuf;
    use std::sync::Once;

    /// Ensures the process-level rustls CryptoProvider is installed exactly
    /// once per test binary. `rustls::ServerConfig::builder()` panics without
    /// a provider, so any test that calls `build_relay_tls_config` must call
    /// this first.
    fn install_crypto_provider() {
        static INIT: Once = Once::new();
        INIT.call_once(|| {
            // Ignore the result: concurrent test binaries may race, and
            // install_default is only allowed to succeed once per process.
            let _ = rustls::crypto::ring::default_provider().install_default();
        });
    }

    // --- extract_subdomain_part ---------------------------------------

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

    #[test]
    fn extract_subdomain_part_empty_subdomain_rejected() {
        // ".rousecontext.com" strips to empty — must be None.
        assert_eq!(
            extract_subdomain_part(".rousecontext.com", "rousecontext.com"),
            None
        );
    }

    // --- Helpers: cert / key file generation --------------------------

    struct GeneratedPair {
        cert_pem: String,
        key_pem: String,
        ca_cert_pem: String,
    }

    /// Build a self-signed CA and a server cert signed by it.
    fn generate_self_signed_pair() -> GeneratedPair {
        let ca_key = KeyPair::generate().unwrap();
        let mut ca_params = CertificateParams::default();
        ca_params.is_ca = rcgen::IsCa::Ca(rcgen::BasicConstraints::Unconstrained);
        let mut ca_dn = DistinguishedName::new();
        ca_dn.push(DnType::CommonName, "tls.rs test CA");
        ca_params.distinguished_name = ca_dn;
        let ca_cert = ca_params.self_signed(&ca_key).unwrap();

        let key = KeyPair::generate().unwrap();
        let mut params = CertificateParams::new(vec!["localhost".to_string()]).unwrap();
        let mut dn = DistinguishedName::new();
        dn.push(DnType::CommonName, "localhost");
        params.distinguished_name = dn;
        let cert = params.signed_by(&key, &ca_cert, &ca_key).unwrap();

        GeneratedPair {
            cert_pem: cert.pem(),
            key_pem: key.serialize_pem(),
            ca_cert_pem: ca_cert.pem(),
        }
    }

    /// Build a client cert for a given device FQDN with a DNS SAN.
    fn generate_client_cert_der_with_san(fqdn: &str) -> CertificateDer<'static> {
        let ca_key = KeyPair::generate().unwrap();
        let mut ca_params = CertificateParams::default();
        ca_params.is_ca = rcgen::IsCa::Ca(rcgen::BasicConstraints::Unconstrained);
        let ca_cert = ca_params.self_signed(&ca_key).unwrap();

        let key = KeyPair::generate().unwrap();
        let mut params = CertificateParams::new(vec![fqdn.to_string()]).unwrap();
        let mut dn = DistinguishedName::new();
        // CN is something else so tests exercise the SAN path, not CN fallback.
        dn.push(DnType::CommonName, "irrelevant-cn");
        params.distinguished_name = dn;
        let cert = params.signed_by(&key, &ca_cert, &ca_key).unwrap();
        CertificateDer::from(cert.der().to_vec())
    }

    /// Build a client cert with CN only, no SANs (so we exercise CN fallback).
    fn generate_client_cert_der_cn_only(cn: &str) -> CertificateDer<'static> {
        let ca_key = KeyPair::generate().unwrap();
        let mut ca_params = CertificateParams::default();
        ca_params.is_ca = rcgen::IsCa::Ca(rcgen::BasicConstraints::Unconstrained);
        let ca_cert = ca_params.self_signed(&ca_key).unwrap();

        let key = KeyPair::generate().unwrap();
        // Empty SAN list.
        let mut params = CertificateParams::new(Vec::<String>::new()).unwrap();
        let mut dn = DistinguishedName::new();
        dn.push(DnType::CommonName, cn);
        params.distinguished_name = dn;
        let cert = params.signed_by(&key, &ca_cert, &ca_key).unwrap();
        CertificateDer::from(cert.der().to_vec())
    }

    fn write_file(dir: &std::path::Path, name: &str, body: &str) -> PathBuf {
        let p = dir.join(name);
        std::fs::write(&p, body).unwrap();
        p
    }

    // --- load_cert_chain ----------------------------------------------

    #[test]
    fn load_cert_chain_ok() {
        let dir = tempfile::tempdir().unwrap();
        let pair = generate_self_signed_pair();
        let path = write_file(dir.path(), "cert.pem", &pair.cert_pem);

        let chain = load_cert_chain(path.to_str().unwrap()).unwrap();
        assert!(!chain.is_empty(), "at least one cert expected");
    }

    #[test]
    fn load_cert_chain_missing_file() {
        let err = load_cert_chain("/definitely/does/not/exist.pem").unwrap_err();
        assert_eq!(err.kind(), io::ErrorKind::NotFound);
    }

    #[test]
    fn load_cert_chain_malformed_pem() {
        let dir = tempfile::tempdir().unwrap();
        // Bad base64 inside a CERTIFICATE block => decode error from rustls_pemfile.
        let bad = "-----BEGIN CERTIFICATE-----\n@@@not-base64@@@\n-----END CERTIFICATE-----\n";
        let path = write_file(dir.path(), "cert.pem", bad);

        let err = load_cert_chain(path.to_str().unwrap()).unwrap_err();
        assert_eq!(err.kind(), io::ErrorKind::InvalidData);
        assert!(err.to_string().contains("bad cert PEM"));
    }

    #[test]
    fn load_cert_chain_empty_file_yields_empty_chain() {
        let dir = tempfile::tempdir().unwrap();
        let path = write_file(dir.path(), "cert.pem", "");
        // An empty file parses to an empty chain (no error from rustls_pemfile).
        let chain = load_cert_chain(path.to_str().unwrap()).unwrap();
        assert!(chain.is_empty());
    }

    // --- load_private_key ---------------------------------------------

    #[test]
    fn load_private_key_ok() {
        let dir = tempfile::tempdir().unwrap();
        let pair = generate_self_signed_pair();
        let path = write_file(dir.path(), "key.pem", &pair.key_pem);

        let _key = load_private_key(path.to_str().unwrap()).unwrap();
    }

    #[test]
    fn load_private_key_missing_file() {
        let err = load_private_key("/nope/missing-key.pem").unwrap_err();
        assert_eq!(err.kind(), io::ErrorKind::NotFound);
    }

    #[test]
    fn load_private_key_malformed_pem() {
        let dir = tempfile::tempdir().unwrap();
        let bad = "-----BEGIN PRIVATE KEY-----\n!!!nope!!!\n-----END PRIVATE KEY-----\n";
        let path = write_file(dir.path(), "key.pem", bad);

        let err = load_private_key(path.to_str().unwrap()).unwrap_err();
        assert_eq!(err.kind(), io::ErrorKind::InvalidData);
        assert!(err.to_string().contains("bad key PEM"));
    }

    #[test]
    fn load_private_key_no_key_in_pem() {
        // A PEM file that parses fine but contains no private key block must
        // surface the "no private key found" error variant.
        let dir = tempfile::tempdir().unwrap();
        let pair = generate_self_signed_pair();
        let path = write_file(dir.path(), "just-a-cert.pem", &pair.cert_pem);

        let err = load_private_key(path.to_str().unwrap()).unwrap_err();
        assert_eq!(err.kind(), io::ErrorKind::InvalidData);
        assert!(err.to_string().contains("no private key"));
    }

    // --- load_ca_certs / build_relay_tls_config -----------------------

    #[test]
    fn build_relay_tls_config_no_client_auth_ok() {
        install_crypto_provider();
        let dir = tempfile::tempdir().unwrap();
        let pair = generate_self_signed_pair();
        let cert = write_file(dir.path(), "cert.pem", &pair.cert_pem);
        let key = write_file(dir.path(), "key.pem", &pair.key_pem);

        let cfg = TlsConfig {
            cert_path: cert.to_string_lossy().into_owned(),
            key_path: key.to_string_lossy().into_owned(),
            ca_cert_path: String::new(),
        };

        let _server_cfg = build_relay_tls_config(&cfg).unwrap();
    }

    #[test]
    fn build_relay_tls_config_with_client_auth_ok() {
        install_crypto_provider();
        let dir = tempfile::tempdir().unwrap();
        let pair = generate_self_signed_pair();
        let cert = write_file(dir.path(), "cert.pem", &pair.cert_pem);
        let key = write_file(dir.path(), "key.pem", &pair.key_pem);
        // Reuse the CA that signed the server cert as the client-cert CA.
        let ca = write_file(dir.path(), "ca.pem", &pair.ca_cert_pem);

        let cfg = TlsConfig {
            cert_path: cert.to_string_lossy().into_owned(),
            key_path: key.to_string_lossy().into_owned(),
            ca_cert_path: ca.to_string_lossy().into_owned(),
        };

        let _server_cfg = build_relay_tls_config(&cfg).unwrap();
    }

    #[test]
    fn build_relay_tls_config_missing_cert() {
        install_crypto_provider();
        let dir = tempfile::tempdir().unwrap();
        let pair = generate_self_signed_pair();
        let key = write_file(dir.path(), "key.pem", &pair.key_pem);

        let cfg = TlsConfig {
            cert_path: dir.path().join("missing-cert.pem").to_string_lossy().into(),
            key_path: key.to_string_lossy().into_owned(),
            ca_cert_path: String::new(),
        };

        let err = build_relay_tls_config(&cfg).unwrap_err();
        assert_eq!(err.kind(), io::ErrorKind::NotFound);
    }

    #[test]
    fn build_relay_tls_config_missing_key() {
        install_crypto_provider();
        let dir = tempfile::tempdir().unwrap();
        let pair = generate_self_signed_pair();
        let cert = write_file(dir.path(), "cert.pem", &pair.cert_pem);

        let cfg = TlsConfig {
            cert_path: cert.to_string_lossy().into_owned(),
            key_path: dir.path().join("missing-key.pem").to_string_lossy().into(),
            ca_cert_path: String::new(),
        };

        let err = build_relay_tls_config(&cfg).unwrap_err();
        assert_eq!(err.kind(), io::ErrorKind::NotFound);
    }

    #[test]
    fn build_relay_tls_config_missing_ca() {
        install_crypto_provider();
        let dir = tempfile::tempdir().unwrap();
        let pair = generate_self_signed_pair();
        let cert = write_file(dir.path(), "cert.pem", &pair.cert_pem);
        let key = write_file(dir.path(), "key.pem", &pair.key_pem);

        let cfg = TlsConfig {
            cert_path: cert.to_string_lossy().into_owned(),
            key_path: key.to_string_lossy().into_owned(),
            ca_cert_path: dir.path().join("missing-ca.pem").to_string_lossy().into(),
        };

        let err = build_relay_tls_config(&cfg).unwrap_err();
        assert_eq!(err.kind(), io::ErrorKind::NotFound);
    }

    #[test]
    fn build_relay_tls_config_malformed_ca_pem() {
        install_crypto_provider();
        let dir = tempfile::tempdir().unwrap();
        let pair = generate_self_signed_pair();
        let cert = write_file(dir.path(), "cert.pem", &pair.cert_pem);
        let key = write_file(dir.path(), "key.pem", &pair.key_pem);
        let bad_ca = "-----BEGIN CERTIFICATE-----\nnot-base64-@@@\n-----END CERTIFICATE-----\n";
        let ca = write_file(dir.path(), "ca.pem", bad_ca);

        let cfg = TlsConfig {
            cert_path: cert.to_string_lossy().into_owned(),
            key_path: key.to_string_lossy().into_owned(),
            ca_cert_path: ca.to_string_lossy().into_owned(),
        };

        let err = build_relay_tls_config(&cfg).unwrap_err();
        assert_eq!(err.kind(), io::ErrorKind::InvalidData);
        assert!(err.to_string().contains("bad CA cert PEM"));
    }

    #[test]
    fn build_relay_tls_config_empty_ca_file_fails_verifier_build() {
        // A CA file that parses but contains no certs produces an empty root
        // store. `WebPkiClientVerifier::build` rejects this, surfacing our
        // "failed to build client verifier" error branch.
        install_crypto_provider();
        let dir = tempfile::tempdir().unwrap();
        let pair = generate_self_signed_pair();
        let cert = write_file(dir.path(), "cert.pem", &pair.cert_pem);
        let key = write_file(dir.path(), "key.pem", &pair.key_pem);
        let ca = write_file(dir.path(), "ca.pem", "");

        let cfg = TlsConfig {
            cert_path: cert.to_string_lossy().into_owned(),
            key_path: key.to_string_lossy().into_owned(),
            ca_cert_path: ca.to_string_lossy().into_owned(),
        };

        let err = build_relay_tls_config(&cfg).unwrap_err();
        assert_eq!(err.kind(), io::ErrorKind::InvalidData);
        assert!(err.to_string().contains("failed to build client verifier"));
    }

    #[test]
    fn build_relay_tls_config_mismatched_key_fails() {
        // A valid cert PEM paired with a freshly generated (unrelated) key
        // makes `with_single_cert` reject the pair. Surfaces the "TLS config"
        // error branch inside both the mTLS and no-auth arms.
        install_crypto_provider();
        let dir = tempfile::tempdir().unwrap();
        let pair = generate_self_signed_pair();
        let unrelated_key = KeyPair::generate().unwrap().serialize_pem();
        let cert = write_file(dir.path(), "cert.pem", &pair.cert_pem);
        let key = write_file(dir.path(), "key.pem", &unrelated_key);

        let cfg = TlsConfig {
            cert_path: cert.to_string_lossy().into_owned(),
            key_path: key.to_string_lossy().into_owned(),
            ca_cert_path: String::new(),
        };

        let err = build_relay_tls_config(&cfg).unwrap_err();
        assert_eq!(err.kind(), io::ErrorKind::InvalidData);
        assert!(err.to_string().contains("TLS config"));
    }

    // --- extract_subdomain_from_peer_cert ----------------------------

    #[test]
    fn extract_subdomain_empty_peer_certs_is_none() {
        assert_eq!(
            extract_subdomain_from_peer_cert(&[], "rousecontext.com"),
            None
        );
    }

    #[test]
    fn extract_subdomain_from_malformed_der_is_none() {
        // Garbage DER — parse fails, we return None rather than panicking.
        let bogus = CertificateDer::from(vec![0x30, 0x82, 0x00, 0x00]);
        assert_eq!(
            extract_subdomain_from_peer_cert(&[bogus], "rousecontext.com"),
            None
        );
    }

    #[test]
    fn extract_subdomain_via_san() {
        let cert = generate_client_cert_der_with_san("misty-otter.rousecontext.com");
        assert_eq!(
            extract_subdomain_from_peer_cert(&[cert], "rousecontext.com"),
            Some("misty-otter".to_string())
        );
    }

    #[test]
    fn extract_subdomain_via_cn_fqdn() {
        // No SAN; CN contains the FQDN.
        let cert = generate_client_cert_der_cn_only("brave-falcon.rousecontext.com");
        assert_eq!(
            extract_subdomain_from_peer_cert(&[cert], "rousecontext.com"),
            Some("brave-falcon".to_string())
        );
    }

    #[test]
    fn extract_subdomain_via_cn_bare_label() {
        // No SAN; CN is a bare label (no dots) — treated as the subdomain itself.
        let cert = generate_client_cert_der_cn_only("silent-badger");
        assert_eq!(
            extract_subdomain_from_peer_cert(&[cert], "rousecontext.com"),
            Some("silent-badger".to_string())
        );
    }

    #[test]
    fn extract_subdomain_cn_for_wrong_base_domain() {
        // CN is a dotted name for a different base domain; no SAN. The CN
        // doesn't strip against the expected suffix and it contains a dot,
        // so the "bare label" fallback also doesn't fire — result None.
        let cert = generate_client_cert_der_cn_only("thing.other.example.com");
        assert_eq!(
            extract_subdomain_from_peer_cert(&[cert], "rousecontext.com"),
            None
        );
    }

    #[test]
    fn extract_subdomain_san_preferred_over_cn() {
        // Cert has a SAN for the right base and a CN that's a bare label.
        // SAN path should win (first hit).
        let ca_key = KeyPair::generate().unwrap();
        let mut ca_params = CertificateParams::default();
        ca_params.is_ca = rcgen::IsCa::Ca(rcgen::BasicConstraints::Unconstrained);
        let ca_cert = ca_params.self_signed(&ca_key).unwrap();

        let key = KeyPair::generate().unwrap();
        let mut params =
            CertificateParams::new(vec!["real-sub.rousecontext.com".to_string()]).unwrap();
        let mut dn = DistinguishedName::new();
        dn.push(DnType::CommonName, "different-cn");
        params.distinguished_name = dn;
        let cert = params.signed_by(&key, &ca_cert, &ca_key).unwrap();
        let der = CertificateDer::from(cert.der().to_vec());

        assert_eq!(
            extract_subdomain_from_peer_cert(&[der], "rousecontext.com"),
            Some("real-sub".to_string())
        );
    }
}
