//! ACME account lifecycle: loading or creating the persistent account key,
//! and the `newAccount` request.

use p256::ecdsa::SigningKey;
use p256::pkcs8::{DecodePrivateKey, EncodePrivateKey};
use std::path::Path;
use tracing::{debug, info, warn};

use super::jws::{acme_post, get_nonce};
use super::types::AcmeDirectory;
use super::AcmeError;

/// Load an ECDSA P-256 account key from a PEM file, or generate a new one and
/// save it. This ensures the relay reuses the same ACME account across restarts.
///
/// When `require_existing` is true and the key file is missing, this function
/// panics with a clear error instead of silently generating a fresh account.
/// Production deployments should enable this to catch deploy misconfigurations
/// (e.g. missing volume mount) that would otherwise rotate the ACME account
/// and burn the shared 50-certs/week/domain Let's Encrypt rate-limit budget.
pub(super) fn load_or_create_account_key(key_path: &Path, require_existing: bool) -> SigningKey {
    if key_path.exists() {
        let pem = std::fs::read_to_string(key_path).expect("failed to read ACME account key file");
        let key = SigningKey::from_pkcs8_pem(&pem).expect("failed to parse ACME account key PEM");
        info!(?key_path, "Loaded existing ACME account key");
        key
    } else {
        if require_existing {
            panic!(
                "ACME account key file is missing at {key_path:?} and \
                 acme.require_existing_account = true. Refusing to generate a \
                 new ACME account because doing so would rotate the relay's \
                 Let's Encrypt identity and burn shared rate-limit headroom. \
                 Restore the file from backup (see relay/scripts/backup-acme-key.sh) \
                 or set acme.require_existing_account = false to allow creation."
            );
        }
        let key = SigningKey::random(&mut rand::thread_rng());
        let pem = key
            .to_pkcs8_pem(p256::pkcs8::LineEnding::LF)
            .expect("failed to encode ACME account key as PEM");

        // Ensure parent directory exists.
        if let Some(parent) = key_path.parent() {
            std::fs::create_dir_all(parent)
                .expect("failed to create directory for ACME account key");
        }
        std::fs::write(key_path, pem.as_bytes()).expect("failed to write ACME account key to disk");
        warn!(
            ?key_path,
            acme_account_created = true,
            "Generated and saved new ACME account key"
        );
        key
    }
}

/// Create or retrieve an ACME account. Returns (account_url, nonce).
pub(super) async fn ensure_account(
    http: &reqwest::Client,
    account_key: &SigningKey,
    dir: &AcmeDirectory,
) -> Result<(String, String), AcmeError> {
    let nonce = get_nonce(http, &dir.new_nonce).await?;
    let payload = serde_json::json!({
        "termsOfServiceAgreed": true,
    })
    .to_string();

    let (resp, nonce) =
        acme_post(http, account_key, &dir.new_account, &nonce, &payload, None).await?;

    let status = resp.status();
    let account_url = resp
        .headers()
        .get("location")
        .and_then(|v| v.to_str().ok())
        .map(|s| s.to_string())
        .ok_or_else(|| {
            AcmeError::Http(format!(
                "no Location header in newAccount response (status {status})"
            ))
        })?;

    if !status.is_success() {
        let body = resp.text().await.unwrap_or_default();
        return Err(AcmeError::Http(format!(
            "newAccount failed ({status}): {body}"
        )));
    }

    debug!(account_url = %account_url, "ACME account ready");
    Ok((account_url, nonce))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn account_key_created_when_file_missing() {
        let dir = tempfile::tempdir().unwrap();
        let key_path = dir.path().join("account_key.pem");
        assert!(!key_path.exists());

        let key = load_or_create_account_key(&key_path, false);
        assert!(key_path.exists());

        // Loading again should produce the same key.
        let key2 = load_or_create_account_key(&key_path, false);
        assert_eq!(key.to_bytes(), key2.to_bytes());
    }

    #[test]
    fn account_key_loaded_from_existing_file() {
        let dir = tempfile::tempdir().unwrap();
        let key_path = dir.path().join("account_key.pem");

        // Write a key, then load it back.
        let original = SigningKey::random(&mut rand::thread_rng());
        let pem = original.to_pkcs8_pem(p256::pkcs8::LineEnding::LF).unwrap();
        std::fs::write(&key_path, pem.as_bytes()).unwrap();

        let loaded = load_or_create_account_key(&key_path, false);
        assert_eq!(original.to_bytes(), loaded.to_bytes());
    }

    #[test]
    fn account_key_creates_parent_directories() {
        let dir = tempfile::tempdir().unwrap();
        let key_path = dir.path().join("nested").join("dir").join("key.pem");
        assert!(!key_path.parent().unwrap().exists());

        let _key = load_or_create_account_key(&key_path, false);
        assert!(key_path.exists());
    }

    #[test]
    fn account_key_require_existing_false_creates_new_key() {
        let dir = tempfile::tempdir().unwrap();
        let key_path = dir.path().join("account_key.pem");
        assert!(!key_path.exists());

        let _key = load_or_create_account_key(&key_path, false);
        assert!(key_path.exists());
    }

    #[test]
    #[should_panic(expected = "acme.require_existing_account = true")]
    fn account_key_require_existing_true_panics_when_missing() {
        let dir = tempfile::tempdir().unwrap();
        let key_path = dir.path().join("account_key.pem");
        assert!(!key_path.exists());

        let _ = load_or_create_account_key(&key_path, true);
    }

    #[test]
    fn account_key_require_existing_true_loads_existing_file() {
        let dir = tempfile::tempdir().unwrap();
        let key_path = dir.path().join("account_key.pem");

        // Pre-seed an existing key on disk.
        let original = SigningKey::random(&mut rand::thread_rng());
        let pem = original.to_pkcs8_pem(p256::pkcs8::LineEnding::LF).unwrap();
        std::fs::write(&key_path, pem.as_bytes()).unwrap();

        let loaded = load_or_create_account_key(&key_path, true);
        assert_eq!(original.to_bytes(), loaded.to_bytes());
    }
}
