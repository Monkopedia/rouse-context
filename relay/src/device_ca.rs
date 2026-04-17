//! Relay's private CA for issuing device client certificates (mTLS).
//!
//! Each device gets a client certificate signed by this CA. The certificate
//! contains the device's own public key (extracted from the registration CSR)
//! so the device's private key never leaves the device.
//!
//! The relay's mTLS verifier trusts this CA cert, meaning only devices that
//! registered through the relay can establish WebSocket connections back.

use rcgen::{
    BasicConstraints, CertificateParams, DistinguishedName, DnType, ExtendedKeyUsagePurpose, IsCa,
    KeyPair, KeyUsagePurpose, SanType, SubjectPublicKeyInfo,
};
use std::io;
use std::path::Path;
use tracing::info;

/// Bundle returned after signing a client cert. Contains only the cert PEM
/// because the device already holds the private key.
#[derive(Debug, Clone)]
pub struct ClientCertBundle {
    /// PEM-encoded client certificate.
    pub cert_pem: String,
}

/// The relay's device CA: a self-signed CA that issues client certificates
/// for device mTLS authentication.
pub struct DeviceCa {
    /// The CA's own keypair (used to sign device client certs).
    ca_key: KeyPair,
    /// The CA certificate parameters (used as issuer).
    ca_cert: rcgen::Certificate,
    /// PEM-encoded CA certificate (for distribution to verifiers).
    ca_cert_pem: String,
}

impl DeviceCa {
    /// Load an existing CA from disk, or create a new self-signed CA and
    /// persist it. The CA key and cert are stored as PEM files.
    pub fn load_or_create(ca_key_path: &Path, ca_cert_path: &Path) -> io::Result<Self> {
        if ca_key_path.exists() && ca_cert_path.exists() {
            Self::load(ca_key_path, ca_cert_path)
        } else {
            Self::create(ca_key_path, ca_cert_path)
        }
    }

    fn load(ca_key_path: &Path, ca_cert_path: &Path) -> io::Result<Self> {
        let key_pem = std::fs::read_to_string(ca_key_path).map_err(|e| {
            io::Error::new(
                io::ErrorKind::NotFound,
                format!("failed to read CA key at {}: {e}", ca_key_path.display()),
            )
        })?;
        let cert_pem = std::fs::read_to_string(ca_cert_path).map_err(|e| {
            io::Error::new(
                io::ErrorKind::NotFound,
                format!("failed to read CA cert at {}: {e}", ca_cert_path.display()),
            )
        })?;

        let ca_key = KeyPair::from_pem(&key_pem).map_err(|e| {
            io::Error::new(
                io::ErrorKind::InvalidData,
                format!("failed to parse CA key PEM: {e}"),
            )
        })?;

        let params = CertificateParams::from_ca_cert_pem(&cert_pem).map_err(|e| {
            io::Error::new(
                io::ErrorKind::InvalidData,
                format!("failed to parse CA cert PEM: {e}"),
            )
        })?;

        let ca_cert = params.self_signed(&ca_key).map_err(|e| {
            io::Error::new(
                io::ErrorKind::InvalidData,
                format!("failed to re-create CA cert from params: {e}"),
            )
        })?;

        info!(?ca_key_path, ?ca_cert_path, "Loaded existing device CA");
        Ok(Self {
            ca_key,
            ca_cert,
            ca_cert_pem: cert_pem,
        })
    }

    fn create(ca_key_path: &Path, ca_cert_path: &Path) -> io::Result<Self> {
        let ca_key = KeyPair::generate()
            .map_err(|e| io::Error::other(format!("failed to generate CA keypair: {e}")))?;

        let mut params = CertificateParams::default();
        params.is_ca = IsCa::Ca(BasicConstraints::Unconstrained);
        params.key_usages = vec![KeyUsagePurpose::KeyCertSign, KeyUsagePurpose::CrlSign];
        params.distinguished_name = DistinguishedName::new();
        params
            .distinguished_name
            .push(DnType::CommonName, "Rouse Relay Device CA");
        params
            .distinguished_name
            .push(DnType::OrganizationName, "Rouse Context");
        // 10-year validity for the CA
        params.not_before = time::OffsetDateTime::now_utc();
        params.not_after = time::OffsetDateTime::now_utc() + time::Duration::days(3650);

        let ca_cert = params
            .self_signed(&ca_key)
            .map_err(|e| io::Error::other(format!("failed to self-sign CA cert: {e}")))?;

        let ca_cert_pem = ca_cert.pem();
        let ca_key_pem = ca_key.serialize_pem();

        // Ensure parent directories exist
        if let Some(parent) = ca_key_path.parent() {
            std::fs::create_dir_all(parent)?;
        }
        if let Some(parent) = ca_cert_path.parent() {
            std::fs::create_dir_all(parent)?;
        }

        std::fs::write(ca_key_path, ca_key_pem.as_bytes())?;
        std::fs::write(ca_cert_path, ca_cert_pem.as_bytes())?;

        info!(?ca_key_path, ?ca_cert_path, "Generated new device CA");
        Ok(Self {
            ca_key,
            ca_cert,
            ca_cert_pem,
        })
    }

    /// Return the PEM-encoded CA certificate (for configuring the mTLS verifier).
    pub fn ca_cert_pem(&self) -> &str {
        &self.ca_cert_pem
    }

    /// Sign a client certificate using the device's public key.
    ///
    /// The resulting cert has:
    /// - CN = `{subdomain}.{base_domain}`
    /// - SAN = DNS:`{subdomain}.{base_domain}`
    /// - EKU = clientAuth
    /// - Signed by the relay's device CA
    /// - Contains the DEVICE's public key (from `device_public_key_der`)
    ///
    /// `device_public_key_der` is the SubjectPublicKeyInfo in DER format,
    /// extracted from the device's CSR.
    pub fn sign_client_cert(
        &self,
        device_public_key_der: &[u8],
        subdomain: &str,
        base_domain: &str,
    ) -> io::Result<ClientCertBundle> {
        let fqdn = format!("{subdomain}.{base_domain}");

        let device_key = SubjectPublicKeyInfo::from_der(device_public_key_der).map_err(|e| {
            io::Error::new(
                io::ErrorKind::InvalidData,
                format!("failed to parse device public key DER: {e}"),
            )
        })?;

        let mut params = CertificateParams::default();
        params.distinguished_name = DistinguishedName::new();
        params.distinguished_name.push(DnType::CommonName, &fqdn);
        params.subject_alt_names = vec![SanType::DnsName(fqdn.try_into().map_err(|e| {
            io::Error::new(
                io::ErrorKind::InvalidData,
                format!("invalid FQDN for SAN: {e}"),
            )
        })?)];
        params.extended_key_usages = vec![ExtendedKeyUsagePurpose::ClientAuth];
        params.key_usages = vec![KeyUsagePurpose::DigitalSignature];
        // 90-day validity for client certs (matches ACME cert lifetime)
        params.not_before = time::OffsetDateTime::now_utc();
        params.not_after =
            time::OffsetDateTime::now_utc() + time::Duration::seconds(90 * 24 * 3600);

        let cert = params
            .signed_by(&device_key, &self.ca_cert, &self.ca_key)
            .map_err(|e| io::Error::other(format!("failed to sign client cert: {e}")))?;

        Ok(ClientCertBundle {
            cert_pem: cert.pem(),
        })
    }
}

/// Extract the SubjectPublicKeyInfo (SPKI) in DER format from a PKCS#10 CSR.
///
/// Verifies the CSR's self-signature before returning. A well-formed DER
/// shell with a mismatched or forged signature is rejected here — this is
/// defense-in-depth so we never hand an unverified SPKI to the relay CA
/// issuer, regardless of whether downstream ACME issuance would have caught
/// it later.
pub fn extract_public_key_from_csr_der(csr_der: &[u8]) -> io::Result<Vec<u8>> {
    use x509_parser::prelude::FromDer;

    let (_, csr) = x509_parser::certification_request::X509CertificationRequest::from_der(csr_der)
        .map_err(|e| {
            io::Error::new(
                io::ErrorKind::InvalidData,
                format!("failed to parse CSR DER: {e}"),
            )
        })?;

    csr.verify_signature().map_err(|e| {
        io::Error::new(
            io::ErrorKind::InvalidData,
            format!("CSR self-signature verification failed: {e}"),
        )
    })?;

    let spki = csr.certification_request_info.subject_pki.raw;

    Ok(spki.to_vec())
}

#[cfg(test)]
mod tests {
    use super::*;
    use x509_parser::prelude::FromDer;

    fn generate_device_csr() -> (Vec<u8>, KeyPair) {
        let device_key = KeyPair::generate().unwrap();
        let mut params =
            CertificateParams::new(vec!["placeholder.example.com".to_string()]).unwrap();
        params.distinguished_name = DistinguishedName::new();
        params
            .distinguished_name
            .push(DnType::CommonName, "placeholder");

        let csr = params.serialize_request(&device_key).unwrap();
        (csr.der().to_vec(), device_key)
    }

    #[test]
    fn create_and_load_ca() {
        let dir = tempfile::tempdir().unwrap();
        let key_path = dir.path().join("ca_key.pem");
        let cert_path = dir.path().join("ca_cert.pem");

        // Create new CA
        let ca = DeviceCa::load_or_create(&key_path, &cert_path).unwrap();
        assert!(key_path.exists());
        assert!(cert_path.exists());
        let original_cert = ca.ca_cert_pem().to_string();

        // Load existing CA
        let ca2 = DeviceCa::load_or_create(&key_path, &cert_path).unwrap();
        assert_eq!(ca2.ca_cert_pem(), original_cert);
    }

    #[test]
    fn ca_cert_is_valid_ca() {
        let dir = tempfile::tempdir().unwrap();
        let key_path = dir.path().join("ca_key.pem");
        let cert_path = dir.path().join("ca_cert.pem");
        let ca = DeviceCa::load_or_create(&key_path, &cert_path).unwrap();

        // Parse the CA cert and verify it
        let pem = pem::parse(ca.ca_cert_pem()).unwrap();
        let (_, cert) =
            x509_parser::certificate::X509Certificate::from_der(pem.contents()).unwrap();

        // Should be a CA
        let bc = cert.basic_constraints().unwrap().unwrap();
        assert!(bc.value.ca);

        // CN should match
        let cn = cert
            .subject()
            .iter_common_name()
            .next()
            .unwrap()
            .as_str()
            .unwrap();
        assert_eq!(cn, "Rouse Relay Device CA");
    }

    #[test]
    fn sign_client_cert_has_correct_eku() {
        let dir = tempfile::tempdir().unwrap();
        let ca = DeviceCa::load_or_create(
            &dir.path().join("ca_key.pem"),
            &dir.path().join("ca_cert.pem"),
        )
        .unwrap();

        let (csr_der, _device_key) = generate_device_csr();
        let spki_der = extract_public_key_from_csr_der(&csr_der).unwrap();

        let bundle = ca
            .sign_client_cert(&spki_der, "test-device", "rousecontext.com")
            .unwrap();

        // Parse the client cert
        let pem = pem::parse(&bundle.cert_pem).unwrap();
        let (_, cert) =
            x509_parser::certificate::X509Certificate::from_der(pem.contents()).unwrap();

        // EKU should contain clientAuth (OID 1.3.6.1.5.5.7.3.2)
        let eku = cert.extended_key_usage().unwrap().unwrap();
        assert!(eku.value.client_auth);
        assert!(!eku.value.server_auth);
    }

    #[test]
    fn sign_client_cert_has_correct_cn_and_san() {
        let dir = tempfile::tempdir().unwrap();
        let ca = DeviceCa::load_or_create(
            &dir.path().join("ca_key.pem"),
            &dir.path().join("ca_cert.pem"),
        )
        .unwrap();

        let (csr_der, _device_key) = generate_device_csr();
        let spki_der = extract_public_key_from_csr_der(&csr_der).unwrap();

        let bundle = ca
            .sign_client_cert(&spki_der, "misty-otter", "rousecontext.com")
            .unwrap();

        let pem = pem::parse(&bundle.cert_pem).unwrap();
        let (_, cert) =
            x509_parser::certificate::X509Certificate::from_der(pem.contents()).unwrap();

        // CN
        let cn = cert
            .subject()
            .iter_common_name()
            .next()
            .unwrap()
            .as_str()
            .unwrap();
        assert_eq!(cn, "misty-otter.rousecontext.com");

        // SAN
        let san = cert.subject_alternative_name().unwrap().unwrap();
        let dns_names: Vec<&str> = san
            .value
            .general_names
            .iter()
            .filter_map(|n| {
                if let x509_parser::extensions::GeneralName::DNSName(dns) = n {
                    Some(*dns)
                } else {
                    None
                }
            })
            .collect();
        assert_eq!(dns_names, vec!["misty-otter.rousecontext.com"]);
    }

    #[test]
    fn client_cert_public_key_matches_csr() {
        let dir = tempfile::tempdir().unwrap();
        let ca = DeviceCa::load_or_create(
            &dir.path().join("ca_key.pem"),
            &dir.path().join("ca_cert.pem"),
        )
        .unwrap();

        let (csr_der, _device_key) = generate_device_csr();
        let spki_der = extract_public_key_from_csr_der(&csr_der).unwrap();

        let bundle = ca
            .sign_client_cert(&spki_der, "test-device", "rousecontext.com")
            .unwrap();

        // Parse the client cert and extract its public key
        let pem = pem::parse(&bundle.cert_pem).unwrap();
        let (_, cert) =
            x509_parser::certificate::X509Certificate::from_der(pem.contents()).unwrap();
        let cert_spki = cert.public_key().raw.to_vec();

        // The public key in the cert should match what we extracted from the CSR
        assert_eq!(cert_spki, spki_der);
    }

    #[test]
    fn extract_public_key_from_csr_works() {
        let (csr_der, device_key) = generate_device_csr();
        let spki_der = extract_public_key_from_csr_der(&csr_der).unwrap();

        // The SPKI should be non-empty and match the device key's public key DER
        assert!(!spki_der.is_empty());

        // Verify it can be parsed back into a SubjectPublicKeyInfo
        let reconstructed = SubjectPublicKeyInfo::from_der(&spki_der).unwrap();
        // The raw DER from the device key should match
        assert_eq!(spki_der, device_key.public_key_der());
        // And the reconstructed SPKI should also be usable
        drop(reconstructed);
    }

    #[test]
    fn extract_public_key_rejects_tampered_csr_signature() {
        // Flip a byte inside the signature value so the CSR's self-signature
        // no longer verifies. `extract_public_key_from_csr_der` must reject.
        let (csr_der, _device_key) = generate_device_csr();
        let mut tampered = csr_der.clone();
        // The signature BIT STRING sits at the tail of the DER CSR; flip a
        // byte near the end so we know we're corrupting the signature, not
        // the tbs structure (which would fail DER parsing instead).
        let last = tampered.len() - 1;
        tampered[last] ^= 0x01;

        let err = extract_public_key_from_csr_der(&tampered)
            .expect_err("tampered CSR signature must be rejected");
        assert_eq!(err.kind(), io::ErrorKind::InvalidData);
    }

    #[test]
    fn creates_parent_directories() {
        let dir = tempfile::tempdir().unwrap();
        let key_path = dir.path().join("nested").join("dir").join("ca_key.pem");
        let cert_path = dir.path().join("nested").join("dir").join("ca_cert.pem");

        let _ca = DeviceCa::load_or_create(&key_path, &cert_path).unwrap();
        assert!(key_path.exists());
        assert!(cert_path.exists());
    }
}
