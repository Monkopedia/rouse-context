//! Tests for POST /rotate-secret -- Merge-missing rotation.

mod test_helpers;

use rouse_relay::api::build_router;
use rouse_relay::firestore::DeviceRecord;
use std::collections::HashMap;
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
        integration_secrets: std::collections::HashMap::new(),
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

    // Verify Firestore was updated with both the flat list (SNI path) and
    // the integration map (authoritative source for future merge-missing).
    let devices = fs.devices.lock().unwrap();
    let updated = devices.get("cool-penguin").unwrap();
    assert_eq!(updated.valid_secrets.len(), 2);
    assert_eq!(updated.integration_secrets.len(), 2);
    for (id, expected) in secrets {
        let expected = expected.as_str().unwrap().to_string();
        assert!(
            updated.valid_secrets.contains(&expected),
            "Firestore valid_secrets {:?} missing {expected}",
            updated.valid_secrets
        );
        assert_eq!(
            updated.integration_secrets.get(id).map(|s| s.as_str()),
            Some(expected.as_str()),
            "Firestore integration_secrets {:?} should map {id} -> {expected}",
            updated.integration_secrets
        );
    }
}

#[tokio::test]
async fn rotate_secret_preserves_existing_secrets_when_all_present() {
    // Device already has two integrations with known secrets — rotate-secret
    // called with the same two IDs should return exactly those values
    // unchanged (merge-missing-of-zero).
    let mut device = make_device("unused");
    device.integration_secrets = HashMap::from([
        ("outreach".to_string(), "brave-outreach".to_string()),
        ("health".to_string(), "swift-health".to_string()),
    ]);
    device.valid_secrets = device.integration_secrets.values().cloned().collect();
    let firestore = MockFirestore::new().with_device("cool-penguin", device);
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

    let resp = tower::ServiceExt::oneshot(app, resp).await.unwrap();
    assert_eq!(resp.status(), 200);

    let body = axum::body::to_bytes(resp.into_body(), 1024 * 1024)
        .await
        .unwrap();
    let json: serde_json::Value = serde_json::from_slice(&body).unwrap();

    let secrets = json["secrets"].as_object().expect("secrets map missing");
    assert_eq!(secrets["outreach"].as_str().unwrap(), "brave-outreach");
    assert_eq!(secrets["health"].as_str().unwrap(), "swift-health");

    // Firestore state is unchanged.
    let devices = fs.devices.lock().unwrap();
    let stored = devices.get("cool-penguin").unwrap();
    assert_eq!(
        stored.integration_secrets.get("outreach").unwrap(),
        "brave-outreach"
    );
    assert_eq!(
        stored.integration_secrets.get("health").unwrap(),
        "swift-health"
    );
}

#[tokio::test]
async fn rotate_secret_mix_of_new_and_existing_returns_full_map() {
    // Device already has "outreach"; request adds "health". The response
    // must return the FULL mapping (preserved + new) so the client can
    // treat its local store as a server-delivered mirror.
    let mut device = make_device("unused");
    device.integration_secrets =
        HashMap::from([("outreach".to_string(), "brave-outreach".to_string())]);
    device.valid_secrets = device.integration_secrets.values().cloned().collect();
    let firestore = MockFirestore::new().with_device("cool-penguin", device);
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

    let resp = tower::ServiceExt::oneshot(app, resp).await.unwrap();
    assert_eq!(resp.status(), 200);

    let body = axum::body::to_bytes(resp.into_body(), 1024 * 1024)
        .await
        .unwrap();
    let json: serde_json::Value = serde_json::from_slice(&body).unwrap();

    let secrets = json["secrets"].as_object().expect("secrets map missing");
    assert_eq!(secrets.len(), 2);
    // outreach unchanged
    assert_eq!(secrets["outreach"].as_str().unwrap(), "brave-outreach");
    // health freshly generated with the required shape
    let health = secrets["health"].as_str().unwrap();
    assert!(
        health.ends_with("-health"),
        "new health secret {health:?} should end with -health"
    );
    let adjective = health.strip_suffix("-health").unwrap();
    assert!(
        !adjective.is_empty() && adjective.chars().all(|c| c.is_ascii_lowercase()),
        "new adjective {adjective:?} is not lowercase ascii letters"
    );
    assert_ne!(health, "brave-outreach");

    // Firestore: valid_secrets rebuilt to match the merged mapping.
    let devices = fs.devices.lock().unwrap();
    let stored = devices.get("cool-penguin").unwrap();
    assert_eq!(stored.integration_secrets.len(), 2);
    assert_eq!(
        stored.integration_secrets.get("outreach").unwrap(),
        "brave-outreach"
    );
    assert_eq!(stored.integration_secrets.get("health").unwrap(), health);
    assert_eq!(stored.valid_secrets.len(), 2);
    assert!(stored.valid_secrets.contains(&"brave-outreach".to_string()));
    assert!(stored.valid_secrets.contains(&health.to_string()));
}

#[tokio::test]
async fn rotate_secret_legacy_device_populates_map() {
    // Transitional: device registered before #148 has an empty
    // integration_secrets map. Every requested integration looks "missing"
    // and gets a fresh secret — equivalent to pre-#148 wholesale rotation
    // for this one call. After, the map is populated for future merges.
    let device = make_device("unused"); // integration_secrets = HashMap::new()
    let firestore = MockFirestore::new().with_device("cool-penguin", device);
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

    let resp = tower::ServiceExt::oneshot(app, resp).await.unwrap();
    assert_eq!(resp.status(), 200);

    let body = axum::body::to_bytes(resp.into_body(), 1024 * 1024)
        .await
        .unwrap();
    let json: serde_json::Value = serde_json::from_slice(&body).unwrap();
    let secrets = json["secrets"].as_object().expect("secrets map missing");
    assert_eq!(secrets.len(), 2);
    for id in ["outreach", "health"] {
        let value = secrets[id].as_str().unwrap();
        assert!(value.ends_with(&format!("-{id}")));
    }

    // Firestore map is now populated; next rotation will merge-missing.
    let devices = fs.devices.lock().unwrap();
    let stored = devices.get("cool-penguin").unwrap();
    assert_eq!(stored.integration_secrets.len(), 2);
    for id in ["outreach", "health"] {
        let expected = secrets[id].as_str().unwrap();
        assert_eq!(
            stored.integration_secrets.get(id).map(|s| s.as_str()),
            Some(expected)
        );
        assert!(stored.valid_secrets.contains(&expected.to_string()));
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
