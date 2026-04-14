//! Tests for POST /rotate-secret -- Secret prefix rotation.

mod test_helpers;

use rouse_relay::api::build_router;
use rouse_relay::firestore::DeviceRecord;
use std::sync::Arc;
use std::time::{Duration, SystemTime};
use test_helpers::*;

fn make_app_with_ca(
    firestore: MockFirestore,
    firebase_auth: MockFirebaseAuth,
) -> (axum::Router, Arc<MockFirestore>) {
    let firestore = Arc::new(firestore);
    let state = build_test_state_with_ca(
        firestore.clone(),
        Arc::new(MockFcm::new()),
        Arc::new(MockAcme::new("test-cert")),
        Arc::new(firebase_auth),
    );
    (build_router(state), firestore)
}

fn make_device(secret: &str) -> DeviceRecord {
    DeviceRecord {
        fcm_token: "fcm-tok".to_string(),
        firebase_uid: "uid-1".to_string(),
        public_key: "key".to_string(),
        cert_expires: SystemTime::now() + Duration::from_secs(86400),
        registered_at: SystemTime::now(),
        last_rotation: None,
        renewal_nudge_sent: None,
        secret_prefix: Some(secret.to_string()),
        valid_secrets: Vec::new(),
    }
}

#[tokio::test]
async fn rotate_secret_generates_and_returns_new_secrets() {
    let firestore = MockFirestore::new().with_device("cool-penguin", make_device("old-secret"));
    let (app, fs) = make_app_with_ca(firestore, MockFirebaseAuth::new());

    let resp = axum::http::Request::builder()
        .method("POST")
        .uri("/rotate-secret")
        .header("content-type", "application/json")
        .body(axum::body::Body::from(
            serde_json::json!({
                "subdomain": "cool-penguin",
                "integrations": ["outreach", "health"]
            })
            .to_string(),
        ))
        .unwrap();

    // Note: in production this requires mTLS auth, but in tests we use the
    // test device identity layer (no TLS). The handler should extract the
    // subdomain from the mTLS cert or request body.
    let resp = tower::ServiceExt::oneshot(app, resp).await.unwrap();
    assert_eq!(resp.status(), 200);

    let body = axum::body::to_bytes(resp.into_body(), 1024 * 1024)
        .await
        .unwrap();
    let json: serde_json::Value = serde_json::from_slice(&body).unwrap();

    assert!(json["success"].as_bool().unwrap());
    let secrets = json["secrets"].as_object().expect("secrets map missing");
    assert_eq!(secrets.len(), 2);
    for id in ["outreach", "health"] {
        let value = secrets[id].as_str().unwrap();
        let suffix = format!("-{id}");
        assert!(
            value.ends_with(&suffix),
            "secret {value:?} should end with {suffix:?}"
        );
        let adjective = value.strip_suffix(&suffix).unwrap();
        assert!(
            !adjective.is_empty() && adjective.chars().all(|c| c.is_ascii_lowercase()),
            "adjective {adjective:?} is not lowercase ascii letters"
        );
    }

    // Verify Firestore was updated with the generated secrets
    let devices = fs.devices.lock().unwrap();
    let updated = devices.get("cool-penguin").unwrap();
    assert_eq!(updated.valid_secrets.len(), 2);
    for (_, expected) in secrets {
        let expected = expected.as_str().unwrap().to_string();
        assert!(
            updated.valid_secrets.contains(&expected),
            "Firestore valid_secrets {:?} missing {expected}",
            updated.valid_secrets
        );
    }
}

#[tokio::test]
async fn rotate_secret_device_not_found_returns_404() {
    let firestore = MockFirestore::new(); // no devices
    let (app, _) = make_app_with_ca(firestore, MockFirebaseAuth::new());

    let resp = axum::http::Request::builder()
        .method("POST")
        .uri("/rotate-secret")
        .header("content-type", "application/json")
        .body(axum::body::Body::from(
            serde_json::json!({
                "subdomain": "nonexistent",
                "integrations": ["outreach"]
            })
            .to_string(),
        ))
        .unwrap();

    let resp = tower::ServiceExt::oneshot(app, resp).await.unwrap();
    assert_eq!(resp.status(), 404);
}
