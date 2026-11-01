//! `/ws` session registration records `last_seen` so the stale-device sweep
//! keeps actively used devices (#787), throttled to one write per device per
//! day.

mod test_helpers;

use rouse_relay::api::ws::DeviceIdentity;
use rouse_relay::api::{self, AppState};
use rouse_relay::firestore::DeviceRecord;
use std::sync::Arc;
use std::time::{Duration, SystemTime};
use test_helpers::*;
use tokio::net::TcpListener;

const DAY: Duration = Duration::from_secs(86400);

fn record(registered_ago: Duration, last_seen_ago: Option<Duration>) -> DeviceRecord {
    let now = SystemTime::now();
    DeviceRecord {
        fcm_token: "tok".to_string(),
        firebase_uid: "uid".to_string(),
        key_thumbprint: None,
        public_key: String::new(),
        key_binding: rouse_relay::firestore::KeyBinding::Bound,
        cert_expires: now + 30 * DAY,
        registered_at: now - registered_ago,
        last_seen: last_seen_ago.map(|ago| now - ago),
        last_rotation: None,
        renewal_nudge_sent: None,
        secret_prefix: None,
        push_kind: Default::default(),
        push_endpoint: String::new(),
        valid_secrets: Vec::new(),
        integration_secrets: std::collections::HashMap::new(),
        retired_secrets: std::collections::HashMap::new(),
        stale_secrets: Vec::new(),
    }
}

/// Serve the API router with `subdomain` injected as the mTLS identity.
async fn serve(firestore: Arc<MockFirestore>, subdomain: &str) -> (String, Arc<AppState>) {
    let state = build_test_state(
        firestore,
        Arc::new(MockFcm::new()),
        Arc::new(MockAcme::new("cert")),
        Arc::new(MockFirebaseAuth::new()),
    );
    let router = api::build_router(state.clone()).layer(axum::Extension(DeviceIdentity {
        subdomain: subdomain.to_string(),
    }));
    let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let addr = listener.local_addr().unwrap();
    tokio::spawn(async move { axum::serve(listener, router).await.unwrap() });
    (format!("ws://{addr}/ws"), state)
}

/// Open a mux session, wait until it is registered, hold it briefly so any
/// `last_seen` write it triggers lands, then close it.
async fn one_session(url: &str, state: &AppState, subdomain: &str) {
    let (mut ws, _) = tokio_tungstenite::connect_async(url).await.unwrap();
    tokio::time::timeout(Duration::from_secs(5), async {
        while !state.relay_state.is_device_online(subdomain) {
            tokio::time::sleep(Duration::from_millis(10)).await;
        }
    })
    .await
    .expect("mux session never registered");
    tokio::time::sleep(Duration::from_millis(100)).await;
    ws.close(None).await.unwrap();
    tokio::time::timeout(Duration::from_secs(5), async {
        while state.relay_state.is_device_online(subdomain) {
            tokio::time::sleep(Duration::from_millis(10)).await;
        }
    })
    .await
    .expect("mux session never unregistered");
}

#[tokio::test]
async fn repeated_sessions_within_a_day_write_last_seen_once() {
    let firestore = Arc::new(MockFirestore::new().with_device("dev", record(200 * DAY, None)));
    let (url, state) = serve(firestore.clone(), "dev").await;
    let before = SystemTime::now();

    for _ in 0..3 {
        one_session(&url, &state, "dev").await;
    }

    let writes = firestore.last_seen_writes.lock().unwrap().clone();
    assert_eq!(writes.len(), 1, "{writes:?}");
    assert!(writes[0].1 >= before);
    let stored = firestore.devices.lock().unwrap()["dev"].last_seen;
    assert_eq!(stored, Some(writes[0].1));
}

#[tokio::test]
async fn session_writes_last_seen_once_the_stored_one_is_a_day_old() {
    let firestore = Arc::new(MockFirestore::new().with_device(
        "dev",
        record(200 * DAY, Some(DAY + 60 * Duration::from_secs(60))),
    ));
    let (url, state) = serve(firestore.clone(), "dev").await;

    one_session(&url, &state, "dev").await;

    assert_eq!(firestore.last_seen_writes.lock().unwrap().len(), 1);
}

#[tokio::test]
async fn session_skips_write_when_last_seen_is_under_a_day_old() {
    let firestore = Arc::new(
        MockFirestore::new().with_device("dev", record(200 * DAY, Some(Duration::from_secs(3600)))),
    );
    let (url, state) = serve(firestore.clone(), "dev").await;

    one_session(&url, &state, "dev").await;

    assert!(firestore.last_seen_writes.lock().unwrap().is_empty());
}
