//! Rouse relay server entry point.
//!
//! Accepts TLS connections on a single TCP port and routes by SNI:
//! - `relay.rousecontext.com` -> TLS terminate, serve axum HTTP/WS API
//! - `{subdomain}.rousecontext.com` -> TLS passthrough to device via mux

use rouse_relay::api::ws::DeviceIdentity;
use rouse_relay::api::{self, AppState};
use rouse_relay::config::RelayConfig;
use rouse_relay::maintenance::{self, MaintenanceConfig};
use rouse_relay::passthrough::{self, PassthroughContext, SessionRegistry};
use rouse_relay::router::route_connection;
use rouse_relay::shutdown::ShutdownController;
use rouse_relay::sni::RouteDecision;
use rouse_relay::state::RelayState;
use rouse_relay::tls::{build_relay_tls_config, extract_subdomain_from_peer_cert};
use std::path::Path;
use std::sync::Arc;
use std::time::Duration;
use tokio::io::AsyncReadExt;
use tokio::net::TcpListener;
use tokio_rustls::TlsAcceptor;
use tracing::{debug, error, info, warn};

#[tokio::main]
async fn main() {
    // Install ring as the default crypto provider for rustls.
    // This is needed because both ring and aws-lc-rs features are enabled
    // (via transitive dependencies), and rustls cannot auto-detect which to use.
    rustls::crypto::ring::default_provider()
        .install_default()
        .expect("Failed to install rustls crypto provider");

    tracing_subscriber::fmt()
        .with_env_filter(
            tracing_subscriber::EnvFilter::try_from_default_env()
                .unwrap_or_else(|_| tracing_subscriber::EnvFilter::new("info")),
        )
        .init();

    let config_path = std::env::args()
        .nth(1)
        .unwrap_or_else(|| "relay.toml".to_string());

    let config = if Path::new(&config_path).exists() {
        match RelayConfig::from_file_with_env(Path::new(&config_path)) {
            Ok(cfg) => cfg,
            Err(e) => {
                error!("Failed to load config from {config_path}: {e}");
                std::process::exit(1);
            }
        }
    } else {
        info!("No config file found at {config_path}, using defaults");
        let mut cfg = RelayConfig::default();
        cfg.apply_env_overrides();
        cfg
    };

    // Build TLS config for relay API (with optional mTLS)
    let tls_config = if !config.tls.cert_path.is_empty() {
        match build_relay_tls_config(&config.tls) {
            Ok(cfg) => Some(cfg),
            Err(e) => {
                error!("Failed to build TLS config: {e}");
                std::process::exit(1);
            }
        }
    } else {
        info!("No TLS cert configured, relay API will run without TLS");
        None
    };

    // Bind TCP listener
    let listener = match TcpListener::bind(&config.server.bind_addr).await {
        Ok(l) => l,
        Err(e) => {
            error!(bind_addr = %config.server.bind_addr, "Failed to bind: {e}");
            std::process::exit(1);
        }
    };

    info!(
        bind_addr = %config.server.bind_addr,
        relay_hostname = %config.server.relay_hostname,
        "Rouse relay starting"
    );

    // Build shared state
    let relay_state = Arc::new(RelayState::new());
    let session_registry = Arc::new(SessionRegistry::new());

    // Stub external service clients for now (real implementations will be wired later)
    let firestore: Arc<dyn rouse_relay::firestore::FirestoreClient> = Arc::new(StubFirestore);
    let fcm: Arc<dyn rouse_relay::fcm::FcmClient> = Arc::new(StubFcm);
    let acme: Arc<dyn rouse_relay::acme::AcmeClient> = Arc::new(StubAcme);
    let firebase_auth: Arc<dyn rouse_relay::firebase_auth::FirebaseAuth> =
        Arc::new(StubFirebaseAuth);

    let app_state = Arc::new(AppState {
        relay_state: relay_state.clone(),
        session_registry: session_registry.clone(),
        firestore: firestore.clone(),
        fcm: fcm.clone(),
        acme: acme.clone(),
        firebase_auth,
        subdomain_generator: rouse_relay::subdomain::SubdomainGenerator::new(),
        rate_limiter: rouse_relay::rate_limit::RateLimiter::new(
            rouse_relay::rate_limit::RateLimitConfig::default(),
        ),
        config: config.clone(),
    });

    // Shutdown controller
    let shutdown = ShutdownController::new();

    // Spawn signal handler
    let signal_shutdown = shutdown.clone();
    tokio::spawn(async move {
        signal_shutdown.listen_for_signals().await;
    });

    // Spawn maintenance loop
    let maint_config = MaintenanceConfig {
        relay_hostname: config.server.relay_hostname.clone(),
        ..Default::default()
    };
    let maint_shutdown = shutdown.clone();
    tokio::spawn(maintenance::run_maintenance_loop(
        maint_config,
        firestore.clone(),
        fcm.clone(),
        acme.clone(),
        maint_shutdown,
    ));

    let relay_hostname = config.server.relay_hostname.clone();
    let base_domain = relay_hostname
        .strip_prefix("relay.")
        .unwrap_or(&relay_hostname)
        .to_string();
    let fcm_timeout = Duration::from_secs(config.limits.fcm_wakeup_timeout_secs.unwrap_or(20));

    // Accept loop
    info!("Accept loop running");
    loop {
        tokio::select! {
            accept_result = listener.accept() => {
                let (stream, peer_addr) = match accept_result {
                    Ok(v) => v,
                    Err(e) => {
                        warn!("Accept error: {e}");
                        continue;
                    }
                };

                if shutdown.is_shutting_down() {
                    debug!("Rejecting connection during shutdown");
                    drop(stream);
                    continue;
                }

                let conn_ctx = ConnectionContext {
                    tls_config: tls_config.clone(),
                    relay_hostname: relay_hostname.clone(),
                    base_domain: base_domain.clone(),
                    app_state: app_state.clone(),
                    session_registry: session_registry.clone(),
                    relay_state: relay_state.clone(),
                    firestore: firestore.clone(),
                    fcm: fcm.clone(),
                    fcm_timeout,
                };

                tokio::spawn(async move {
                    if let Err(e) = handle_connection(stream, peer_addr, conn_ctx).await {
                        debug!(peer = %peer_addr, "Connection error: {e}");
                    }
                });
            }
            _ = shutdown.wait_for_shutdown() => {
                info!("Shutdown signal received, stopping accept loop");
                break;
            }
        }
    }

    // Graceful shutdown: drain existing connections
    info!("Draining connections...");
    rouse_relay::shutdown::execute_shutdown(
        &shutdown,
        Duration::from_secs(5),
        || {
            let mux_count = relay_state.active_mux_count();
            let stream_count = relay_state.active_stream_count();
            (mux_count, stream_count)
        },
        async {
            // Wait briefly for in-flight work
            tokio::time::sleep(Duration::from_millis(500)).await;
        },
    )
    .await;

    info!("Relay stopped");
}

/// All the shared state a connection handler needs.
struct ConnectionContext {
    tls_config: Option<Arc<rustls::ServerConfig>>,
    relay_hostname: String,
    base_domain: String,
    app_state: Arc<AppState>,
    session_registry: Arc<SessionRegistry>,
    relay_state: Arc<RelayState>,
    firestore: Arc<dyn rouse_relay::firestore::FirestoreClient>,
    fcm: Arc<dyn rouse_relay::fcm::FcmClient>,
    fcm_timeout: Duration,
}

async fn handle_connection(
    mut stream: tokio::net::TcpStream,
    peer_addr: std::net::SocketAddr,
    ctx: ConnectionContext,
) -> Result<(), Box<dyn std::error::Error>> {
    // Peek at the initial bytes to extract SNI
    let mut buf = vec![0u8; 4096];
    let n = stream.peek(&mut buf).await?;
    let initial_bytes = &buf[..n];

    let decision = route_connection(initial_bytes, &ctx.relay_hostname);
    debug!(peer = %peer_addr, ?decision, "Routing decision");

    match decision {
        RouteDecision::RelayApi => {
            handle_relay_api(
                stream,
                peer_addr,
                ctx.tls_config,
                &ctx.base_domain,
                ctx.app_state,
            )
            .await
        }
        RouteDecision::DevicePassthrough { subdomain } => {
            // Read the ClientHello bytes (consume them from the socket)
            let mut client_hello = vec![0u8; n];
            let n_read = stream.read(&mut client_hello).await?;
            client_hello.truncate(n_read);

            // Build the SNI hostname for the OPEN frame
            let sni_hostname = format!("{subdomain}.{}", ctx.base_domain);

            let pt_ctx = PassthroughContext {
                relay_state: ctx.relay_state,
                session_registry: ctx.session_registry,
                firestore: ctx.firestore,
                fcm: ctx.fcm,
                relay_hostname: ctx.relay_hostname,
                fcm_wakeup_timeout: ctx.fcm_timeout,
            };

            let resolved = passthrough::resolve_device_stream(&pt_ctx, &subdomain, &sni_hostname)
                .await
                .map_err(|e| format!("passthrough resolve failed: {e}"))?;

            passthrough::splice_stream(
                stream,
                &client_hello,
                resolved.stream_id,
                &resolved.handle,
                resolved.stream_rx,
            )
            .await
            .map_err(|e| format!("splice failed: {e}"))?;

            Ok(())
        }
        RouteDecision::Reject => {
            debug!(peer = %peer_addr, "Rejected: no valid SNI");
            Ok(())
        }
    }
}

async fn handle_relay_api(
    stream: tokio::net::TcpStream,
    _peer_addr: std::net::SocketAddr,
    tls_config: Option<Arc<rustls::ServerConfig>>,
    base_domain: &str,
    app_state: Arc<AppState>,
) -> Result<(), Box<dyn std::error::Error>> {
    let router = api::build_router(app_state);

    match tls_config {
        Some(tls_cfg) => {
            let acceptor = TlsAcceptor::from(tls_cfg);
            let tls_stream = acceptor.accept(stream).await?;

            // Extract subdomain from client cert if present
            let (_, server_conn) = tls_stream.get_ref();
            let mut device_identity = None;
            if let Some(peer_certs) = server_conn.peer_certificates() {
                if let Some(sub) = extract_subdomain_from_peer_cert(peer_certs, base_domain) {
                    device_identity = Some(DeviceIdentity { subdomain: sub });
                }
            }

            // Add device identity as a layer if present
            let router = if let Some(identity) = device_identity {
                router.layer(axum::Extension(identity))
            } else {
                router
            };

            serve_http(hyper_util::rt::TokioIo::new(tls_stream), router).await
        }
        None => {
            // No TLS - serve plain HTTP (for testing/development)
            serve_http(hyper_util::rt::TokioIo::new(stream), router).await
        }
    }
}

async fn serve_http<I>(io: I, router: axum::Router) -> Result<(), Box<dyn std::error::Error>>
where
    I: hyper::rt::Read + hyper::rt::Write + Unpin + Send + 'static,
{
    let service = hyper::service::service_fn(move |req: hyper::Request<hyper::body::Incoming>| {
        let router = router.clone();
        async move {
            let resp = tower::ServiceExt::oneshot(router, req).await;
            resp
        }
    });

    hyper_util::server::conn::auto::Builder::new(hyper_util::rt::TokioExecutor::new())
        .serve_connection_with_upgrades(io, service)
        .await
        .map_err(|e| format!("HTTP serve error: {e}"))?;

    Ok(())
}

// --- Stub implementations for external services ---
// These will be replaced with real implementations when the services are wired.

struct StubFirestore;

#[async_trait::async_trait]
impl rouse_relay::firestore::FirestoreClient for StubFirestore {
    async fn get_device(
        &self,
        subdomain: &str,
    ) -> Result<rouse_relay::firestore::DeviceRecord, rouse_relay::firestore::FirestoreError> {
        Err(rouse_relay::firestore::FirestoreError::NotFound(
            subdomain.to_string(),
        ))
    }

    async fn find_device_by_uid(
        &self,
        _firebase_uid: &str,
    ) -> Result<
        Option<(String, rouse_relay::firestore::DeviceRecord)>,
        rouse_relay::firestore::FirestoreError,
    > {
        Ok(None)
    }

    async fn put_device(
        &self,
        _subdomain: &str,
        _record: &rouse_relay::firestore::DeviceRecord,
    ) -> Result<(), rouse_relay::firestore::FirestoreError> {
        Ok(())
    }

    async fn delete_device(
        &self,
        _subdomain: &str,
    ) -> Result<(), rouse_relay::firestore::FirestoreError> {
        Ok(())
    }

    async fn put_pending_cert(
        &self,
        _subdomain: &str,
        _pending: &rouse_relay::firestore::PendingCert,
    ) -> Result<(), rouse_relay::firestore::FirestoreError> {
        Ok(())
    }

    async fn get_pending_cert(
        &self,
        subdomain: &str,
    ) -> Result<rouse_relay::firestore::PendingCert, rouse_relay::firestore::FirestoreError> {
        Err(rouse_relay::firestore::FirestoreError::NotFound(
            subdomain.to_string(),
        ))
    }

    async fn delete_pending_cert(
        &self,
        _subdomain: &str,
    ) -> Result<(), rouse_relay::firestore::FirestoreError> {
        Ok(())
    }

    async fn list_devices(
        &self,
    ) -> Result<
        Vec<(String, rouse_relay::firestore::DeviceRecord)>,
        rouse_relay::firestore::FirestoreError,
    > {
        Ok(Vec::new())
    }

    async fn list_pending_certs(
        &self,
    ) -> Result<
        Vec<(String, rouse_relay::firestore::PendingCert)>,
        rouse_relay::firestore::FirestoreError,
    > {
        Ok(Vec::new())
    }
}

struct StubFcm;

#[async_trait::async_trait]
impl rouse_relay::fcm::FcmClient for StubFcm {
    async fn send_data_message(
        &self,
        _fcm_token: &str,
        _data: &rouse_relay::fcm::FcmData,
        _high_priority: bool,
    ) -> Result<(), rouse_relay::fcm::FcmError> {
        Ok(())
    }
}

struct StubAcme;

#[async_trait::async_trait]
impl rouse_relay::acme::AcmeClient for StubAcme {
    async fn issue_certificate(
        &self,
        _subdomain: &str,
        _csr_der: &[u8],
    ) -> Result<String, rouse_relay::acme::AcmeError> {
        Ok("stub-cert".to_string())
    }
}

struct StubFirebaseAuth;

#[async_trait::async_trait]
impl rouse_relay::firebase_auth::FirebaseAuth for StubFirebaseAuth {
    async fn verify_id_token(
        &self,
        _token: &str,
    ) -> Result<
        rouse_relay::firebase_auth::FirebaseClaims,
        rouse_relay::firebase_auth::FirebaseAuthError,
    > {
        Err(rouse_relay::firebase_auth::FirebaseAuthError::InvalidToken(
            "stub".to_string(),
        ))
    }
}
