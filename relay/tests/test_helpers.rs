//! Shared mock implementations and helpers for API tests.

use async_trait::async_trait;
use rouse_relay::acme::{AcmeClient, AcmeError};
use rouse_relay::fcm::{FcmClient, FcmData, FcmError};
use rouse_relay::firebase_auth::{FirebaseAuth, FirebaseAuthError, FirebaseClaims};
use rouse_relay::firestore::{DeviceRecord, FirestoreClient, FirestoreError, PendingCert};
use std::collections::HashMap;
use std::sync::{Arc, Mutex};

/// Mock Firestore client backed by in-memory HashMaps.
pub struct MockFirestore {
    pub devices: Mutex<HashMap<String, DeviceRecord>>,
    pub pending_certs: Mutex<HashMap<String, PendingCert>>,
}

impl MockFirestore {
    pub fn new() -> Self {
        Self {
            devices: Mutex::new(HashMap::new()),
            pending_certs: Mutex::new(HashMap::new()),
        }
    }

    pub fn with_device(self, subdomain: &str, record: DeviceRecord) -> Self {
        self.devices
            .lock()
            .unwrap()
            .insert(subdomain.to_string(), record);
        self
    }
}

#[async_trait]
impl FirestoreClient for MockFirestore {
    async fn get_device(&self, subdomain: &str) -> Result<DeviceRecord, FirestoreError> {
        self.devices
            .lock()
            .unwrap()
            .get(subdomain)
            .cloned()
            .ok_or_else(|| FirestoreError::NotFound(subdomain.to_string()))
    }

    async fn find_device_by_uid(
        &self,
        firebase_uid: &str,
    ) -> Result<Option<(String, DeviceRecord)>, FirestoreError> {
        let devices = self.devices.lock().unwrap();
        for (sub, rec) in devices.iter() {
            if rec.firebase_uid == firebase_uid {
                return Ok(Some((sub.clone(), rec.clone())));
            }
        }
        Ok(None)
    }

    async fn put_device(
        &self,
        subdomain: &str,
        record: &DeviceRecord,
    ) -> Result<(), FirestoreError> {
        self.devices
            .lock()
            .unwrap()
            .insert(subdomain.to_string(), record.clone());
        Ok(())
    }

    async fn delete_device(&self, subdomain: &str) -> Result<(), FirestoreError> {
        self.devices.lock().unwrap().remove(subdomain);
        Ok(())
    }

    async fn put_pending_cert(
        &self,
        subdomain: &str,
        pending: &PendingCert,
    ) -> Result<(), FirestoreError> {
        self.pending_certs
            .lock()
            .unwrap()
            .insert(subdomain.to_string(), pending.clone());
        Ok(())
    }

    async fn get_pending_cert(&self, subdomain: &str) -> Result<PendingCert, FirestoreError> {
        self.pending_certs
            .lock()
            .unwrap()
            .get(subdomain)
            .cloned()
            .ok_or_else(|| FirestoreError::NotFound(subdomain.to_string()))
    }

    async fn delete_pending_cert(&self, subdomain: &str) -> Result<(), FirestoreError> {
        self.pending_certs.lock().unwrap().remove(subdomain);
        Ok(())
    }
}

/// Mock FCM client that records sent messages.
pub struct MockFcm {
    pub sent: Mutex<Vec<(String, FcmData, bool)>>,
    pub should_fail: Mutex<bool>,
}

impl MockFcm {
    pub fn new() -> Self {
        Self {
            sent: Mutex::new(Vec::new()),
            should_fail: Mutex::new(false),
        }
    }

    pub fn failing() -> Self {
        Self {
            sent: Mutex::new(Vec::new()),
            should_fail: Mutex::new(true),
        }
    }
}

#[async_trait]
impl FcmClient for MockFcm {
    async fn send_data_message(
        &self,
        fcm_token: &str,
        data: &FcmData,
        high_priority: bool,
    ) -> Result<(), FcmError> {
        if *self.should_fail.lock().unwrap() {
            return Err(FcmError::Http("mock failure".to_string()));
        }
        self.sent
            .lock()
            .unwrap()
            .push((fcm_token.to_string(), data.clone(), high_priority));
        Ok(())
    }
}

/// Mock ACME client that returns a fixed cert.
pub struct MockAcme {
    pub cert: Mutex<String>,
    pub should_fail: Mutex<Option<AcmeError>>,
}

impl MockAcme {
    pub fn new(cert: &str) -> Self {
        Self {
            cert: Mutex::new(cert.to_string()),
            should_fail: Mutex::new(None),
        }
    }

    pub fn rate_limited() -> Self {
        Self {
            cert: Mutex::new(String::new()),
            should_fail: Mutex::new(Some(AcmeError::RateLimited {
                retry_after_secs: 604800,
            })),
        }
    }
}

#[async_trait]
impl AcmeClient for MockAcme {
    async fn issue_certificate(
        &self,
        _subdomain: &str,
        _csr_der: &[u8],
    ) -> Result<String, AcmeError> {
        let fail = {
            let guard = self.should_fail.lock().unwrap();
            guard.as_ref().map(|err| match err {
                AcmeError::RateLimited { retry_after_secs } => AcmeError::RateLimited {
                    retry_after_secs: *retry_after_secs,
                },
                AcmeError::ChallengeFailed(s) => AcmeError::ChallengeFailed(s.clone()),
                AcmeError::Http(s) => AcmeError::Http(s.clone()),
            })
        };
        if let Some(err) = fail {
            return Err(err);
        }
        Ok(self.cert.lock().unwrap().clone())
    }
}

/// Mock Firebase auth that accepts specific tokens.
pub struct MockFirebaseAuth {
    /// Map from token string to UID.
    pub valid_tokens: Mutex<HashMap<String, String>>,
}

impl MockFirebaseAuth {
    pub fn new() -> Self {
        Self {
            valid_tokens: Mutex::new(HashMap::new()),
        }
    }

    pub fn with_token(self, token: &str, uid: &str) -> Self {
        self.valid_tokens
            .lock()
            .unwrap()
            .insert(token.to_string(), uid.to_string());
        self
    }
}

#[async_trait]
impl FirebaseAuth for MockFirebaseAuth {
    async fn verify_id_token(&self, token: &str) -> Result<FirebaseClaims, FirebaseAuthError> {
        self.valid_tokens
            .lock()
            .unwrap()
            .get(token)
            .map(|uid| FirebaseClaims { uid: uid.clone() })
            .ok_or_else(|| FirebaseAuthError::InvalidToken("unknown token".to_string()))
    }
}

/// Build test AppState with the given mocks.
pub fn build_test_state(
    firestore: Arc<dyn FirestoreClient>,
    fcm: Arc<dyn FcmClient>,
    acme: Arc<dyn AcmeClient>,
    firebase_auth: Arc<dyn FirebaseAuth>,
) -> Arc<rouse_relay::api::AppState> {
    Arc::new(rouse_relay::api::AppState {
        relay_state: Arc::new(rouse_relay::state::RelayState::new()),
        firestore,
        fcm,
        acme,
        firebase_auth,
        subdomain_generator: rouse_relay::subdomain::SubdomainGenerator::new(),
        rate_limiter: rouse_relay::rate_limit::RateLimiter::new(
            rouse_relay::rate_limit::RateLimitConfig::default(),
        ),
        config: rouse_relay::config::RelayConfig::default(),
    })
}
