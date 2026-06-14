//! Endpoint tests for `POST /crash` (issue #464).
//!
//! Unit coverage for sanitization / fingerprinting / dedup / spike-cap / global
//! cap and the per-IP limiter lives in `src/crash.rs`. Here we drive the HTTP
//! handler end-to-end through the router to verify status-code behaviour and
//! that a configured GitHub filer actually gets called.

mod test_helpers;

use async_trait::async_trait;
use rouse_relay::api::build_router;
use rouse_relay::crash::{CrashLimitsConfig, CrashService, GitHubError, GitHubIssueFiler};
use rouse_relay::rate_limit::RateLimitConfig;
use rouse_relay::state::RelayState;
use std::sync::{Arc, Mutex};
use std::time::Duration;
use test_helpers::*;

struct RecordingFiler {
    creates: Mutex<u32>,
}

impl RecordingFiler {
    fn new() -> Self {
        Self {
            creates: Mutex::new(0),
        }
    }
}

#[async_trait]
impl GitHubIssueFiler for RecordingFiler {
    async fn create_issue(
        &self,
        _title: &str,
        _body: &str,
        _labels: &[&str],
    ) -> Result<u64, GitHubError> {
        *self.creates.lock().unwrap() += 1;
        Ok(42)
    }
    async fn comment_issue(&self, _number: u64, _body: &str) -> Result<(), GitHubError> {
        Ok(())
    }
}

fn make_app(crash: Arc<CrashService>) -> axum::Router {
    let state = Arc::new(rouse_relay::api::AppState {
        relay_state: Arc::new(RelayState::new()),
        session_registry: Arc::new(rouse_relay::passthrough::SessionRegistry::new()),
        firestore: Arc::new(MockFirestore::new()),
        fcm: Arc::new(MockFcm::new()),
        acme: Arc::new(MockAcme::new("cert")),
        dns: Arc::new(MockDns::new()),
        firebase_auth: Arc::new(MockFirebaseAuth::new()),
        subdomain_generator: rouse_relay::subdomain::SubdomainGenerator::new(),
        rate_limiter: rouse_relay::rate_limit::RateLimiter::new(RateLimitConfig::default()),
        request_subdomain_rate_limiter: rouse_relay::rate_limit::RateLimiter::new(
            RateLimitConfig::default(),
        ),
        fcm_wake_throttle: Arc::new(rouse_relay::rate_limit::FcmWakeThrottle::new(
            Duration::from_secs(10),
        )),
        crash,
        config: rouse_relay::config::RelayConfig::default(),
        device_ca: None,
        #[cfg(feature = "test-mode")]
        test_metrics: None,
    });
    build_router(state)
}

fn post_crash(body: &str) -> axum::http::Request<axum::body::Body> {
    axum::http::Request::builder()
        .method("POST")
        .uri("/crash")
        .header("content-type", "application/json")
        .body(axum::body::Body::from(body.to_string()))
        .unwrap()
}

const SAMPLE: &str = r#"{"STACK_TRACE":"java.lang.IllegalStateException: boom\n\tat com.rousecontext.app.Foo.bar(Foo.kt:42)","APP_VERSION_NAME":"1.0.3"}"#;

#[tokio::test]
async fn crash_invalid_json_is_bad_request() {
    let app = make_app(Arc::new(CrashService::disabled()));
    let resp = tower::ServiceExt::oneshot(app, post_crash("not json"))
        .await
        .unwrap();
    assert_eq!(resp.status(), 400);
}

#[tokio::test]
async fn crash_missing_stack_trace_is_bad_request() {
    let app = make_app(Arc::new(CrashService::disabled()));
    let resp = tower::ServiceExt::oneshot(app, post_crash(r#"{"APP_VERSION_NAME":"1.0.3"}"#))
        .await
        .unwrap();
    assert_eq!(resp.status(), 400);
}

#[tokio::test]
async fn crash_without_sink_is_accepted() {
    let app = make_app(Arc::new(CrashService::disabled()));
    let resp = tower::ServiceExt::oneshot(app, post_crash(SAMPLE))
        .await
        .unwrap();
    assert_eq!(resp.status(), 202);
}

#[tokio::test]
async fn crash_with_filer_files_issue_and_accepts() {
    let filer = Arc::new(RecordingFiler::new());
    let svc = CrashService::with_filer(Some(filer.clone()));
    let app = make_app(Arc::new(svc));
    let resp = tower::ServiceExt::oneshot(app, post_crash(SAMPLE))
        .await
        .unwrap();
    assert_eq!(resp.status(), 202);
    assert_eq!(*filer.creates.lock().unwrap(), 1);
}

#[tokio::test]
async fn crash_per_ip_rate_limit_rejects_after_burst() {
    // Tiny bucket so the limiter trips after one request. The in-process test
    // router shares the fallback "unknown" IP key, so the second request from
    // the same (absent) source is rejected.
    let svc = CrashService::new(
        None,
        RateLimitConfig {
            max_tokens: 1,
            refill_interval: Duration::from_secs(60),
        },
        CrashLimitsConfig::default(),
    );
    let app = make_app(Arc::new(svc));

    let first = tower::ServiceExt::oneshot(app.clone(), post_crash(SAMPLE))
        .await
        .unwrap();
    assert_eq!(first.status(), 202);

    let second = tower::ServiceExt::oneshot(app, post_crash(SAMPLE))
        .await
        .unwrap();
    assert_eq!(second.status(), 429);
}
