//! Shared mock implementations and helpers for API tests.

#[allow(unused_imports)]
use async_trait::async_trait;
#[allow(unused_imports)]
use rouse_relay::acme::{AcmeClient, AcmeError, CertificateBundle};
#[allow(unused_imports)]
use rouse_relay::dns::{DnsClient, DnsError};
#[allow(unused_imports)]
use rouse_relay::fcm::{FcmClient, FcmData, FcmError};
#[allow(unused_imports)]
use rouse_relay::firebase_auth::{FirebaseAuth, FirebaseAuthError, FirebaseClaims};
#[allow(unused_imports)]
use rouse_relay::firestore::{
    DeviceRecord, FirestoreClient, FirestoreError, PendingCert, SubdomainReservation,
};
#[allow(unused_imports)]
use std::collections::HashMap;
#[allow(unused_imports)]
use std::sync::{Arc, Mutex};

/// Mock Firestore client backed by in-memory HashMaps.
#[allow(dead_code)]
pub struct MockFirestore {
    pub devices: Mutex<HashMap<String, DeviceRecord>>,
    pub pending_certs: Mutex<HashMap<String, PendingCert>>,
    pub reservations: Mutex<HashMap<String, SubdomainReservation>>,
}

impl Default for MockFirestore {
    fn default() -> Self {
        Self::new()
    }
}

#[allow(dead_code)]
impl MockFirestore {
    pub fn new() -> Self {
        Self {
            devices: Mutex::new(HashMap::new()),
            pending_certs: Mutex::new(HashMap::new()),
            reservations: Mutex::new(HashMap::new()),
        }
    }

    pub fn with_device(self, subdomain: &str, record: DeviceRecord) -> Self {
        self.devices
            .lock()
            .unwrap()
            .insert(subdomain.to_string(), record);
        self
    }

    pub fn with_reservation(self, subdomain: &str, reservation: SubdomainReservation) -> Self {
        self.reservations
            .lock()
            .unwrap()
            .insert(subdomain.to_string(), reservation);
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

    async fn list_devices(&self) -> Result<Vec<(String, DeviceRecord)>, FirestoreError> {
        let devices = self.devices.lock().unwrap();
        Ok(devices
            .iter()
            .map(|(k, v)| (k.clone(), v.clone()))
            .collect())
    }

    async fn list_pending_certs(&self) -> Result<Vec<(String, PendingCert)>, FirestoreError> {
        let certs = self.pending_certs.lock().unwrap();
        Ok(certs.iter().map(|(k, v)| (k.clone(), v.clone())).collect())
    }

    async fn put_reservation(
        &self,
        subdomain: &str,
        reservation: &SubdomainReservation,
    ) -> Result<(), FirestoreError> {
        self.reservations
            .lock()
            .unwrap()
            .insert(subdomain.to_string(), reservation.clone());
        Ok(())
    }

    async fn get_reservation(
        &self,
        subdomain: &str,
    ) -> Result<SubdomainReservation, FirestoreError> {
        self.reservations
            .lock()
            .unwrap()
            .get(subdomain)
            .cloned()
            .ok_or_else(|| FirestoreError::NotFound(subdomain.to_string()))
    }

    async fn find_reservation_by_uid(
        &self,
        firebase_uid: &str,
    ) -> Result<Option<(String, SubdomainReservation)>, FirestoreError> {
        let map = self.reservations.lock().unwrap();
        Ok(map
            .iter()
            .find(|(_, r)| r.firebase_uid == firebase_uid)
            .map(|(k, v)| (k.clone(), v.clone())))
    }

    async fn delete_reservation(&self, subdomain: &str) -> Result<(), FirestoreError> {
        self.reservations.lock().unwrap().remove(subdomain);
        Ok(())
    }

    async fn list_reservations(
        &self,
    ) -> Result<Vec<(String, SubdomainReservation)>, FirestoreError> {
        let map = self.reservations.lock().unwrap();
        Ok(map.iter().map(|(k, v)| (k.clone(), v.clone())).collect())
    }
}

/// Mock FCM client that records sent messages.
#[allow(dead_code)]
pub struct MockFcm {
    pub sent: Mutex<Vec<(String, FcmData, bool)>>,
    pub should_fail: Mutex<bool>,
}

impl Default for MockFcm {
    fn default() -> Self {
        Self::new()
    }
}

#[allow(dead_code)]
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
#[allow(dead_code)]
pub struct MockAcme {
    pub cert: Mutex<String>,
    pub should_fail: Mutex<Option<AcmeError>>,
}

#[allow(dead_code)]
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
        _csr_der: Option<&[u8]>,
    ) -> Result<CertificateBundle, AcmeError> {
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
        Ok(CertificateBundle {
            cert_pem: self.cert.lock().unwrap().clone(),
            private_key_pem: None,
        })
    }
}

/// Mock DNS client that records deletion calls.
#[allow(dead_code)]
pub struct MockDns {
    pub deleted_subdomains: Mutex<Vec<String>>,
    pub should_fail: Mutex<bool>,
}

impl Default for MockDns {
    fn default() -> Self {
        Self::new()
    }
}

#[allow(dead_code)]
impl MockDns {
    pub fn new() -> Self {
        Self {
            deleted_subdomains: Mutex::new(Vec::new()),
            should_fail: Mutex::new(false),
        }
    }

    pub fn failing() -> Self {
        Self {
            deleted_subdomains: Mutex::new(Vec::new()),
            should_fail: Mutex::new(true),
        }
    }
}

#[async_trait]
impl DnsClient for MockDns {
    async fn delete_subdomain_records(&self, subdomain: &str) -> Result<(), DnsError> {
        if *self.should_fail.lock().unwrap() {
            return Err(DnsError::Http("mock DNS failure".to_string()));
        }
        self.deleted_subdomains
            .lock()
            .unwrap()
            .push(subdomain.to_string());
        Ok(())
    }
}

/// Mock Firebase auth that accepts specific tokens.
#[allow(dead_code)]
pub struct MockFirebaseAuth {
    /// Map from token string to UID.
    pub valid_tokens: Mutex<HashMap<String, String>>,
}

impl Default for MockFirebaseAuth {
    fn default() -> Self {
        Self::new()
    }
}

#[allow(dead_code)]
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
#[allow(dead_code)]
pub fn build_test_state(
    firestore: Arc<dyn FirestoreClient>,
    fcm: Arc<dyn FcmClient>,
    acme: Arc<dyn AcmeClient>,
    firebase_auth: Arc<dyn FirebaseAuth>,
) -> Arc<rouse_relay::api::AppState> {
    build_test_state_with_dns(
        firestore,
        fcm,
        acme,
        firebase_auth,
        Arc::new(MockDns::new()),
    )
}

/// Build test AppState with the given mocks, including a custom DNS client.
#[allow(dead_code)]
pub fn build_test_state_with_dns(
    firestore: Arc<dyn FirestoreClient>,
    fcm: Arc<dyn FcmClient>,
    acme: Arc<dyn AcmeClient>,
    firebase_auth: Arc<dyn FirebaseAuth>,
    dns: Arc<dyn DnsClient>,
) -> Arc<rouse_relay::api::AppState> {
    Arc::new(rouse_relay::api::AppState {
        relay_state: Arc::new(rouse_relay::state::RelayState::new()),
        session_registry: Arc::new(rouse_relay::passthrough::SessionRegistry::new()),
        firestore,
        fcm,
        acme,
        dns,
        firebase_auth,
        subdomain_generator: rouse_relay::subdomain::SubdomainGenerator::new(),
        rate_limiter: rouse_relay::rate_limit::RateLimiter::new(
            rouse_relay::rate_limit::RateLimitConfig::default(),
        ),
        request_subdomain_rate_limiter: rouse_relay::rate_limit::RateLimiter::new(
            rouse_relay::rate_limit::RateLimitConfig {
                max_tokens: 3,
                refill_interval: std::time::Duration::from_secs(20),
            },
        ),
        config: rouse_relay::config::RelayConfig::default(),
        device_ca: None,
        #[cfg(feature = "test-mode")]
        test_metrics: None,
    })
}

/// Build test AppState with a DeviceCa included.
#[allow(dead_code)]
pub fn build_test_state_with_ca(
    firestore: Arc<dyn FirestoreClient>,
    fcm: Arc<dyn FcmClient>,
    acme: Arc<dyn AcmeClient>,
    firebase_auth: Arc<dyn FirebaseAuth>,
) -> Arc<rouse_relay::api::AppState> {
    let tmp = tempfile::tempdir().expect("create tempdir for test CA");
    let ca = rouse_relay::device_ca::DeviceCa::load_or_create(
        &tmp.path().join("ca_key.pem"),
        &tmp.path().join("ca_cert.pem"),
    )
    .expect("create test device CA");
    // Leak the tempdir so it lives for the test duration
    std::mem::forget(tmp);

    Arc::new(rouse_relay::api::AppState {
        relay_state: Arc::new(rouse_relay::state::RelayState::new()),
        session_registry: Arc::new(rouse_relay::passthrough::SessionRegistry::new()),
        firestore,
        fcm,
        acme,
        dns: Arc::new(MockDns::new()),
        firebase_auth,
        subdomain_generator: rouse_relay::subdomain::SubdomainGenerator::new(),
        rate_limiter: rouse_relay::rate_limit::RateLimiter::new(
            rouse_relay::rate_limit::RateLimitConfig::default(),
        ),
        request_subdomain_rate_limiter: rouse_relay::rate_limit::RateLimiter::new(
            rouse_relay::rate_limit::RateLimitConfig {
                max_tokens: 3,
                refill_interval: std::time::Duration::from_secs(20),
            },
        ),
        config: rouse_relay::config::RelayConfig::default(),
        device_ca: Some(ca),
        #[cfg(feature = "test-mode")]
        test_metrics: None,
    })
}
