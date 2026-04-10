//! Tests for graceful shutdown.
//!
//! Covers:
//! - ShutdownController state transitions
//! - Waiters are notified on shutdown
//! - execute_shutdown calls close_all and respects drain timeout
//! - Multiple calls to initiate() are idempotent
//! - is_shutting_down() reflects correct state
//! - Shutdown with maintenance loop integration

mod test_helpers;

use rouse_relay::maintenance::{self, MaintenanceConfig};
use rouse_relay::shutdown::{execute_shutdown, ShutdownController};
use std::sync::Arc;
use std::time::Duration;
use test_helpers::{MockAcme, MockDns, MockFcm, MockFirestore};

#[tokio::test]
async fn initially_not_shutting_down() {
    let ctrl = ShutdownController::new();
    assert!(!ctrl.is_shutting_down());
}

#[tokio::test]
async fn initiate_sets_flag() {
    let ctrl = ShutdownController::new();
    ctrl.initiate();
    assert!(ctrl.is_shutting_down());
}

#[tokio::test]
async fn initiate_is_idempotent() {
    let ctrl = ShutdownController::new();
    ctrl.initiate();
    ctrl.initiate(); // should not panic
    assert!(ctrl.is_shutting_down());
}

#[tokio::test]
async fn wait_for_shutdown_resolves_on_initiate() {
    let ctrl = ShutdownController::new();
    let ctrl2 = ctrl.clone();

    let waiter = tokio::spawn(async move {
        ctrl2.wait_for_shutdown().await;
        true
    });

    // Give the waiter a moment to start
    tokio::time::sleep(Duration::from_millis(10)).await;
    ctrl.initiate();

    let result = tokio::time::timeout(Duration::from_secs(1), waiter).await;
    assert!(result.is_ok());
    assert!(result.unwrap().unwrap());
}

#[tokio::test]
async fn wait_for_shutdown_returns_immediately_if_already_shutting_down() {
    let ctrl = ShutdownController::new();
    ctrl.initiate();

    // Should return immediately, not block
    let result = tokio::time::timeout(Duration::from_millis(50), ctrl.wait_for_shutdown()).await;
    assert!(result.is_ok());
}

#[tokio::test]
async fn multiple_waiters_all_notified() {
    let ctrl = ShutdownController::new();

    let mut handles = Vec::new();
    for _ in 0..5 {
        let c = ctrl.clone();
        handles.push(tokio::spawn(async move {
            c.wait_for_shutdown().await;
        }));
    }

    tokio::time::sleep(Duration::from_millis(10)).await;
    ctrl.initiate();

    for h in handles {
        let result = tokio::time::timeout(Duration::from_secs(1), h).await;
        assert!(result.is_ok());
    }
}

#[tokio::test]
async fn execute_shutdown_calls_close_all() {
    let ctrl = ShutdownController::new();

    let stats = execute_shutdown(
        &ctrl,
        Duration::from_secs(5),
        || (3, 7), // 3 mux connections, 7 streams
        async {},  // drain completes immediately
    )
    .await;

    assert!(ctrl.is_shutting_down());
    assert_eq!(stats.mux_connections_closed, 3);
    assert_eq!(stats.streams_closed, 7);
    assert!(stats.drain_completed);
}

#[tokio::test]
async fn execute_shutdown_respects_drain_timeout() {
    let ctrl = ShutdownController::new();

    let stats = execute_shutdown(&ctrl, Duration::from_millis(50), || (1, 2), async {
        // Simulate slow drain that exceeds timeout
        tokio::time::sleep(Duration::from_secs(10)).await;
    })
    .await;

    assert_eq!(stats.mux_connections_closed, 1);
    assert_eq!(stats.streams_closed, 2);
    assert!(!stats.drain_completed); // timed out
}

#[tokio::test]
async fn clone_shares_state() {
    let ctrl1 = ShutdownController::new();
    let ctrl2 = ctrl1.clone();

    ctrl1.initiate();
    assert!(ctrl2.is_shutting_down());
}

#[tokio::test]
async fn shutdown_stops_maintenance_loop() {
    let ctrl = ShutdownController::new();

    let config = MaintenanceConfig {
        interval: Duration::from_millis(10), // fast interval for test
        ..MaintenanceConfig::default()
    };

    let firestore = Arc::new(MockFirestore::new());
    let fcm = Arc::new(MockFcm::new());
    let acme = Arc::new(MockAcme::new("cert"));

    let shutdown = ctrl.clone();
    let loop_handle = tokio::spawn(async move {
        let dns: Arc<dyn rouse_relay::dns::DnsClient> = Arc::new(MockDns::new());
        maintenance::run_maintenance_loop(config, firestore, fcm, acme, dns, shutdown).await;
    });

    // Let it run for a bit
    tokio::time::sleep(Duration::from_millis(50)).await;

    // Initiate shutdown
    ctrl.initiate();

    // Loop should exit
    let result = tokio::time::timeout(Duration::from_secs(2), loop_handle).await;
    assert!(result.is_ok());
}

#[tokio::test]
async fn no_new_connections_after_shutdown() {
    // This tests the pattern: check is_shutting_down() before accepting work
    let ctrl = ShutdownController::new();

    // Before shutdown: new work is accepted
    assert!(!ctrl.is_shutting_down());

    ctrl.initiate();

    // After shutdown: new work is rejected
    assert!(ctrl.is_shutting_down());
    // In production, the accept loop would check this and break
}
