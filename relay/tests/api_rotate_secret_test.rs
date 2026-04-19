//! Tests for POST /rotate-secret -- Replace-wholesale rotation (#285).
//!
//! Prior to #285 this handler was merge-missing: integrations in the
//! request were added or kept, and entries already in the stored map were
//! never dropped. That left the "disable integration" path as a no-op
//! against the relay: the per-integration URL stayed live after the user
//! flipped it off. Fixed by making the request's `integrations` list the
//! authoritative set — entries no longer present are removed from both
//! Firestore and the in-memory cache.

mod test_helpers;

use rouse_relay::api::build_router;
use rouse_relay::api::ws::DeviceIdentity;
use rouse_relay::api::AppState;
use rouse_relay::firestore::DeviceRecord;
use std::collections::HashMap;
use std::sync::Arc;
use std::time::{Duration, SystemTime};
use test_helpers::*;

/// Build a router with a `DeviceIdentity` extension layered in, simulating
/// an mTLS-authenticated request for `subdomain`.
fn make_app_with_ca_as(
    firestore: MockFirestore,
    firebase_auth: MockFirebaseAuth,
    identity_subdomain: &str,
) -> (axum::Router, Arc<MockFirestore>, Arc<AppState>) {
    let firestore = Arc::new(firestore);
    let state = build_test_state_with_ca(
        firestore.clone(),
        Arc::new(MockFcm::new()),
        Arc::new(MockAcme::new("test-cert")),
        Arc::new(firebase_auth),
    );
    let router = build_router(state.clone()).layer(axum::Extension(DeviceIdentity {
        subdomain: identity_subdomain.to_string(),
    }));
    (router, firestore, state)
}

/// Build a router WITHOUT any `DeviceIdentity` extension, simulating an
/// unauthenticated (no-client-cert) request.
fn make_app_without_identity(
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
    let (app, fs, _state) = make_app_with_ca_as(firestore, MockFirebaseAuth::new(), "cool-penguin");

    let resp = axum::http::Request::builder()
        .method("POST")
        .uri("/rotate-secret")
        .header("content-type", "application/json")
        .body(axum::body::Body::from(
            serde_json::json!({
                "integrations": ["outreach", "health"]
            })
            .to_string(),
        ))
        .unwrap();

    // Identity is supplied via a layered Extension (simulating the mTLS
    // layer in production).
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
    // the integration map (authoritative source for future replace-
    // wholesale rotations).
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
    // unchanged (replace-wholesale is idempotent for the unchanged-set
    // case; #285).
    let mut device = make_device("unused");
    device.integration_secrets = HashMap::from([
        ("outreach".to_string(), "brave-outreach".to_string()),
        ("health".to_string(), "swift-health".to_string()),
    ]);
    device.valid_secrets = device.integration_secrets.values().cloned().collect();
    let firestore = MockFirestore::new().with_device("cool-penguin", device);
    let (app, fs, _state) = make_app_with_ca_as(firestore, MockFirebaseAuth::new(), "cool-penguin");

    let resp = axum::http::Request::builder()
        .method("POST")
        .uri("/rotate-secret")
        .header("content-type", "application/json")
        .body(axum::body::Body::from(
            serde_json::json!({
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
    let (app, fs, _state) = make_app_with_ca_as(firestore, MockFirebaseAuth::new(), "cool-penguin");

    let resp = axum::http::Request::builder()
        .method("POST")
        .uri("/rotate-secret")
        .header("content-type", "application/json")
        .body(axum::body::Body::from(
            serde_json::json!({
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
    // for this one call. After, the map is populated for future rotations.
    let device = make_device("unused"); // integration_secrets = HashMap::new()
    let firestore = MockFirestore::new().with_device("cool-penguin", device);
    let (app, fs, _state) = make_app_with_ca_as(firestore, MockFirebaseAuth::new(), "cool-penguin");

    let resp = axum::http::Request::builder()
        .method("POST")
        .uri("/rotate-secret")
        .header("content-type", "application/json")
        .body(axum::body::Body::from(
            serde_json::json!({
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
                                          // mTLS layer presents an identity for a subdomain that no device record
                                          // exists for (e.g. cert was revoked or Firestore lost the row).
    let (app, _, _) = make_app_with_ca_as(firestore, MockFirebaseAuth::new(), "nonexistent");

    let resp = axum::http::Request::builder()
        .method("POST")
        .uri("/rotate-secret")
        .header("content-type", "application/json")
        .body(axum::body::Body::from(
            serde_json::json!({
                "integrations": ["outreach"]
            })
            .to_string(),
        ))
        .unwrap();

    let resp = tower::ServiceExt::oneshot(app, resp).await.unwrap();
    assert_eq!(resp.status(), 404);
}

#[tokio::test]
async fn rotate_secret_without_mtls_identity_returns_401() {
    // Regression test for issue #202: a request that reaches the handler
    // without a DeviceIdentity extension (no mTLS client cert) must be
    // rejected with 401, even if the body names a real subdomain. Before
    // the fix, the handler trusted the body's `subdomain` field and would
    // happily rotate the victim's secrets.
    let firestore = MockFirestore::new().with_device("cool-penguin", make_device("old-secret"));
    let (app, fs) = make_app_without_identity(firestore, MockFirebaseAuth::new());

    let resp = axum::http::Request::builder()
        .method("POST")
        .uri("/rotate-secret")
        .header("content-type", "application/json")
        .body(axum::body::Body::from(
            serde_json::json!({
                // Attacker-supplied subdomain — must be ignored now.
                "subdomain": "cool-penguin",
                "integrations": ["outreach", "health"]
            })
            .to_string(),
        ))
        .unwrap();

    let resp = tower::ServiceExt::oneshot(app, resp).await.unwrap();
    assert_eq!(resp.status(), 401, "unauthenticated rotate must be 401");

    // And Firestore must NOT have been touched.
    let devices = fs.devices.lock().unwrap();
    let stored = devices.get("cool-penguin").unwrap();
    assert!(
        stored.valid_secrets.is_empty(),
        "valid_secrets must not have been populated by an unauthenticated caller"
    );
    assert!(
        stored.integration_secrets.is_empty(),
        "integration_secrets must not have been populated by an unauthenticated caller"
    );
}

#[tokio::test]
async fn rotate_secret_ignores_body_subdomain_when_identity_present() {
    // Documented behavior: if a caller with a valid mTLS identity for
    // subdomain A includes a different subdomain B in the JSON body, the
    // body field is simply ignored (the request struct does not declare
    // it). The cert-derived identity is authoritative. This pins that
    // behavior so future refactors don't accidentally reintroduce the
    // body-trust bug.
    let firestore = MockFirestore::new()
        .with_device("cool-penguin", make_device("owned-by-victim"))
        .with_device("attacker-sub", make_device("owned-by-attacker"));
    let (app, fs, _state) = make_app_with_ca_as(firestore, MockFirebaseAuth::new(), "attacker-sub");

    let resp = axum::http::Request::builder()
        .method("POST")
        .uri("/rotate-secret")
        .header("content-type", "application/json")
        .body(axum::body::Body::from(
            serde_json::json!({
                // Attacker tries to target the victim in the body.
                "subdomain": "cool-penguin",
                "integrations": ["outreach"]
            })
            .to_string(),
        ))
        .unwrap();

    let resp = tower::ServiceExt::oneshot(app, resp).await.unwrap();
    assert_eq!(resp.status(), 200);

    let devices = fs.devices.lock().unwrap();
    let victim = devices.get("cool-penguin").unwrap();
    assert!(
        victim.valid_secrets.is_empty(),
        "victim subdomain must remain untouched when attacker names it in the body"
    );
    assert!(
        victim.integration_secrets.is_empty(),
        "victim integration_secrets must remain untouched"
    );
    let attacker = devices.get("attacker-sub").unwrap();
    assert_eq!(
        attacker.integration_secrets.len(),
        1,
        "attacker's own subdomain (from mTLS identity) is the one that gets rotated"
    );
}

/// #285 regression: starting with [A, B, C], POST /rotate-secret with
/// [A, C] must DROP B from both the Firestore valid-secrets map and the
/// in-memory cache. Before the fix, the handler was merge-missing and B
/// would have stayed valid forever.
#[tokio::test]
async fn rotate_secret_drops_integrations_not_in_request() {
    let mut device = make_device("unused");
    device.integration_secrets = HashMap::from([
        ("a".to_string(), "brave-a".to_string()),
        ("b".to_string(), "clever-b".to_string()),
        ("c".to_string(), "swift-c".to_string()),
    ]);
    device.valid_secrets = device.integration_secrets.values().cloned().collect();
    let firestore = MockFirestore::new().with_device("cool-penguin", device);
    let (app, fs, state) = make_app_with_ca_as(firestore, MockFirebaseAuth::new(), "cool-penguin");

    // Prime the in-memory cache to mirror the Firestore state, simulating
    // the state after a prior SNI lookup. The test then asserts the cache
    // is rewritten to exclude B.
    state.relay_state.set_valid_secrets_cache(
        "cool-penguin",
        vec![
            "brave-a".to_string(),
            "clever-b".to_string(),
            "swift-c".to_string(),
        ],
    );

    let req = axum::http::Request::builder()
        .method("POST")
        .uri("/rotate-secret")
        .header("content-type", "application/json")
        .body(axum::body::Body::from(
            serde_json::json!({ "integrations": ["a", "c"] }).to_string(),
        ))
        .unwrap();
    let resp = tower::ServiceExt::oneshot(app, req).await.unwrap();
    assert_eq!(resp.status(), 200);

    let body = axum::body::to_bytes(resp.into_body(), 1024 * 1024)
        .await
        .unwrap();
    let json: serde_json::Value = serde_json::from_slice(&body).unwrap();
    let secrets = json["secrets"].as_object().expect("secrets map missing");
    assert_eq!(secrets.len(), 2, "response must contain exactly [a, c]");
    assert_eq!(secrets["a"].as_str().unwrap(), "brave-a");
    assert_eq!(secrets["c"].as_str().unwrap(), "swift-c");
    assert!(secrets.get("b").is_none(), "b must be absent from response");

    // Firestore: B is gone from both the map and the flat list.
    let devices = fs.devices.lock().unwrap();
    let stored = devices.get("cool-penguin").unwrap();
    assert_eq!(stored.integration_secrets.len(), 2);
    assert!(!stored.integration_secrets.contains_key("b"));
    assert!(stored.valid_secrets.contains(&"brave-a".to_string()));
    assert!(stored.valid_secrets.contains(&"swift-c".to_string()));
    assert!(
        !stored.valid_secrets.contains(&"clever-b".to_string()),
        "B's secret must be dropped from Firestore valid_secrets"
    );
    drop(devices);

    // In-memory cache: B's secret must no longer be a valid member.
    let cached = state
        .relay_state
        .get_valid_secrets_cache("cool-penguin")
        .expect("cache entry should exist for non-empty set");
    assert_eq!(cached.len(), 2);
    assert!(cached.contains(&"brave-a".to_string()));
    assert!(cached.contains(&"swift-c".to_string()));
    assert!(
        !cached.contains(&"clever-b".to_string()),
        "B's secret must be evicted from the valid-secrets cache"
    );
}

/// #285 regression: starting with [A], POST /rotate-secret with [B] must
/// invalidate A and mint a fresh secret for B. Both sides of the
/// replace-wholesale semantics exercised in a single call.
#[tokio::test]
async fn rotate_secret_swaps_integration_set() {
    let mut device = make_device("unused");
    device.integration_secrets = HashMap::from([("a".to_string(), "brave-a".to_string())]);
    device.valid_secrets = device.integration_secrets.values().cloned().collect();
    let firestore = MockFirestore::new().with_device("cool-penguin", device);
    let (app, fs, state) = make_app_with_ca_as(firestore, MockFirebaseAuth::new(), "cool-penguin");
    state
        .relay_state
        .set_valid_secrets_cache("cool-penguin", vec!["brave-a".to_string()]);

    let req = axum::http::Request::builder()
        .method("POST")
        .uri("/rotate-secret")
        .header("content-type", "application/json")
        .body(axum::body::Body::from(
            serde_json::json!({ "integrations": ["b"] }).to_string(),
        ))
        .unwrap();
    let resp = tower::ServiceExt::oneshot(app, req).await.unwrap();
    assert_eq!(resp.status(), 200);

    let body = axum::body::to_bytes(resp.into_body(), 1024 * 1024)
        .await
        .unwrap();
    let json: serde_json::Value = serde_json::from_slice(&body).unwrap();
    let secrets = json["secrets"].as_object().expect("secrets map missing");
    assert_eq!(secrets.len(), 1);
    let b_secret = secrets["b"].as_str().unwrap().to_string();
    assert!(b_secret.ends_with("-b"));

    // Firestore: A is gone, B is present and freshly minted.
    let devices = fs.devices.lock().unwrap();
    let stored = devices.get("cool-penguin").unwrap();
    assert_eq!(stored.integration_secrets.len(), 1);
    assert!(!stored.integration_secrets.contains_key("a"));
    assert_eq!(stored.integration_secrets.get("b").unwrap(), &b_secret);
    assert_eq!(stored.valid_secrets, vec![b_secret.clone()]);
    drop(devices);

    // In-memory cache: only B's secret is valid now.
    let cached = state
        .relay_state
        .get_valid_secrets_cache("cool-penguin")
        .expect("cache entry should exist for non-empty set");
    assert_eq!(cached, vec![b_secret]);
}

/// #285 regression: empty integrations list legitimately invalidates
/// every per-integration URL for the device — but the device itself stays
/// onboarded (subdomain, account, public key, cert all untouched). The
/// in-memory cache entry is fully evicted so the passthrough path returns
/// a miss.
#[tokio::test]
async fn rotate_secret_empty_list_clears_all_secrets() {
    let mut device = make_device("keep-me");
    device.integration_secrets = HashMap::from([
        ("a".to_string(), "brave-a".to_string()),
        ("b".to_string(), "clever-b".to_string()),
    ]);
    device.valid_secrets = device.integration_secrets.values().cloned().collect();
    let original_fcm = device.fcm_token.clone();
    let original_uid = device.firebase_uid.clone();
    let original_pubkey = device.public_key.clone();
    let original_cert_expires = device.cert_expires;
    let original_registered_at = device.registered_at;
    let original_prefix = device.secret_prefix.clone();

    let firestore = MockFirestore::new().with_device("cool-penguin", device);
    let (app, fs, state) = make_app_with_ca_as(firestore, MockFirebaseAuth::new(), "cool-penguin");
    state.relay_state.set_valid_secrets_cache(
        "cool-penguin",
        vec!["brave-a".to_string(), "clever-b".to_string()],
    );

    let req = axum::http::Request::builder()
        .method("POST")
        .uri("/rotate-secret")
        .header("content-type", "application/json")
        .body(axum::body::Body::from(
            serde_json::json!({ "integrations": [] }).to_string(),
        ))
        .unwrap();
    let resp = tower::ServiceExt::oneshot(app, req).await.unwrap();
    assert_eq!(resp.status(), 200);

    let body = axum::body::to_bytes(resp.into_body(), 1024 * 1024)
        .await
        .unwrap();
    let json: serde_json::Value = serde_json::from_slice(&body).unwrap();
    let secrets = json["secrets"].as_object().expect("secrets map missing");
    assert!(
        secrets.is_empty(),
        "response map must be empty after wholesale-to-empty rotate"
    );

    // Firestore: secrets are gone but everything else about the device
    // record is preserved. Device stays onboarded; the user can still
    // re-enable integrations later without re-registering.
    let devices = fs.devices.lock().unwrap();
    let stored = devices.get("cool-penguin").unwrap();
    assert!(stored.integration_secrets.is_empty());
    assert!(stored.valid_secrets.is_empty());
    assert_eq!(stored.fcm_token, original_fcm);
    assert_eq!(stored.firebase_uid, original_uid);
    assert_eq!(stored.public_key, original_pubkey);
    assert_eq!(stored.cert_expires, original_cert_expires);
    assert_eq!(stored.registered_at, original_registered_at);
    assert_eq!(stored.secret_prefix, original_prefix);
    drop(devices);

    // Cache is evicted (not just emptied) so the passthrough path falls
    // back to Firestore and sees no valid secrets.
    assert_eq!(
        state.relay_state.get_valid_secrets_cache("cool-penguin"),
        None,
        "cache must be evicted when valid_secrets becomes empty"
    );
}

/// #285 regression guard: the additions-still-work case. Starting with
/// [A], POST /rotate-secret with [A, B] leaves A untouched and mints B.
/// This is the behavior that pre-#285 tests covered; we keep it green to
/// ensure replace-wholesale didn't accidentally break additive rotates.
#[tokio::test]
async fn rotate_secret_additions_still_work() {
    let mut device = make_device("unused");
    device.integration_secrets = HashMap::from([("a".to_string(), "brave-a".to_string())]);
    device.valid_secrets = device.integration_secrets.values().cloned().collect();
    let firestore = MockFirestore::new().with_device("cool-penguin", device);
    let (app, fs, _state) = make_app_with_ca_as(firestore, MockFirebaseAuth::new(), "cool-penguin");

    let req = axum::http::Request::builder()
        .method("POST")
        .uri("/rotate-secret")
        .header("content-type", "application/json")
        .body(axum::body::Body::from(
            serde_json::json!({ "integrations": ["a", "b"] }).to_string(),
        ))
        .unwrap();
    let resp = tower::ServiceExt::oneshot(app, req).await.unwrap();
    assert_eq!(resp.status(), 200);

    let body = axum::body::to_bytes(resp.into_body(), 1024 * 1024)
        .await
        .unwrap();
    let json: serde_json::Value = serde_json::from_slice(&body).unwrap();
    let secrets = json["secrets"].as_object().expect("secrets map missing");
    assert_eq!(secrets.len(), 2);
    assert_eq!(secrets["a"].as_str().unwrap(), "brave-a");
    let b_secret = secrets["b"].as_str().unwrap();
    assert!(b_secret.ends_with("-b"));

    let devices = fs.devices.lock().unwrap();
    let stored = devices.get("cool-penguin").unwrap();
    assert_eq!(stored.integration_secrets.len(), 2);
    assert_eq!(stored.integration_secrets.get("a").unwrap(), "brave-a");
    assert_eq!(stored.integration_secrets.get("b").unwrap(), b_secret);
}
