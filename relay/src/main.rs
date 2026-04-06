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

    // Load or create the device CA for mTLS client certificates
    let device_ca = {
        let ca_cfg = &config.device_ca;
        let key_path = std::path::PathBuf::from(&ca_cfg.ca_key_path);
        let cert_path = std::path::PathBuf::from(&ca_cfg.ca_cert_path);
        match rouse_relay::device_ca::DeviceCa::load_or_create(&key_path, &cert_path) {
            Ok(ca) => {
                // Write the CA cert to the TLS ca_cert_path so the mTLS verifier trusts it.
                // If TLS is configured with a ca_cert_path, overwrite it with our device CA cert.
                if !config.tls.ca_cert_path.is_empty() {
                    if let Err(e) = std::fs::write(&config.tls.ca_cert_path, ca.ca_cert_pem()) {
                        warn!(
                            path = %config.tls.ca_cert_path,
                            "Failed to write device CA cert for mTLS verifier: {e}"
                        );
                    } else {
                        info!(
                            path = %config.tls.ca_cert_path,
                            "Wrote device CA cert for mTLS verifier"
                        );
                    }
                }
                Some(ca)
            }
            Err(e) => {
                warn!("Failed to load/create device CA: {e}, client cert issuance will be unavailable");
                None
            }
        }
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

    // Build external service clients — use real implementations when a service account is available
    let firestore: Arc<dyn rouse_relay::firestore::FirestoreClient> = Arc::new(InMemoryFirestore::new());
    info!("Using in-memory Firestore (data lost on restart)");
    let acme: Arc<dyn rouse_relay::acme::AcmeClient> = build_acme_client(&config);

    let (fcm, firebase_auth): (
        Arc<dyn rouse_relay::fcm::FcmClient>,
        Arc<dyn rouse_relay::firebase_auth::FirebaseAuth>,
    ) = build_service_clients(&config);

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
        device_ca,
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

    // When TLS is not configured, non-TLS traffic (plain HTTP) should be
    // routed to the relay API instead of being rejected. SNI parsing only
    // works on TLS ClientHello messages, so plain HTTP requests get Reject.
    let decision = if decision == RouteDecision::Reject && ctx.tls_config.is_none() {
        debug!(peer = %peer_addr, "No TLS configured, routing plain HTTP to relay API");
        RouteDecision::RelayApi
    } else {
        decision
    };

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
            // Inject a test device identity so /ws works without mTLS
            let router = router.layer(axum::Extension(DeviceIdentity {
                subdomain: "test-device".to_string(),
            }));
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

/// Build the ACME client from config.
///
/// When Cloudflare credentials are configured (zone_id + api_token_env pointing to
/// a set env var), this creates a `RealAcmeClient` that performs DNS-01 challenges
/// via Cloudflare and issues certs from Let's Encrypt. Falls back to `StubAcme` with
/// a warning when credentials are missing.
fn build_acme_client(config: &RelayConfig) -> Arc<dyn rouse_relay::acme::AcmeClient> {
    let cf = &config.cloudflare;

    if cf.zone_id.is_empty() {
        info!("No cloudflare.zone_id configured, using stub ACME client");
        return Arc::new(StubAcme);
    }

    let api_token = match cf.resolve_api_token() {
        Ok(token) => token,
        Err(e) => {
            warn!("Cloudflare API token not available ({e}), using stub ACME client");
            return Arc::new(StubAcme);
        }
    };

    // Derive the base domain from the relay hostname (e.g. "relay.rousecontext.com" -> "rousecontext.com")
    let base_domain = config
        .server
        .relay_hostname
        .strip_prefix("relay.")
        .unwrap_or(&config.server.relay_hostname)
        .to_string();

    // Resolve ACME directory URL: config file > env var > Let's Encrypt production
    let directory_url = if !config.acme.directory_url.is_empty() {
        config.acme.directory_url.clone()
    } else {
        rouse_relay::acme::LETS_ENCRYPT_DIRECTORY.to_string()
    };

    let account_key_path = std::path::PathBuf::from(&config.acme.account_key_path);

    info!(
        zone_id = %cf.zone_id,
        base_domain = %base_domain,
        directory_url = %directory_url,
        account_key_path = %account_key_path.display(),
        dns_propagation_timeout_secs = config.acme.dns_propagation_timeout_secs,
        dns_poll_interval_secs = config.acme.dns_poll_interval_secs,
        "Wiring real ACME client with Cloudflare DNS-01"
    );

    Arc::new(rouse_relay::acme::RealAcmeClient::with_persistent_key(
        directory_url,
        api_token,
        cf.zone_id.clone(),
        base_domain,
        config.acme.dns_propagation_timeout_secs,
        config.acme.dns_poll_interval_secs,
        &account_key_path,
    ))
}

/// Build FCM and Firebase Auth clients from config.
///
/// When `firebase.service_account_path` points to a valid service account JSON file,
/// this creates real clients that talk to Google APIs. Otherwise, falls back to stubs.
fn build_service_clients(
    config: &RelayConfig,
) -> (
    Arc<dyn rouse_relay::fcm::FcmClient>,
    Arc<dyn rouse_relay::firebase_auth::FirebaseAuth>,
) {
    let sa_path = &config.firebase.service_account_path;

    if sa_path.is_empty() || !Path::new(sa_path).exists() {
        if sa_path.is_empty() {
            info!("No firebase.service_account_path configured, using stub FCM + Firebase Auth");
        } else {
            warn!(
                path = %sa_path,
                "Service account file not found, using stub FCM + Firebase Auth"
            );
        }
        return (Arc::new(StubFcm), Arc::new(StubFirebaseAuth));
    }

    // Load service account key
    let sa_key = match rouse_relay::google_auth::ServiceAccountKey::from_file(Path::new(sa_path)) {
        Ok(key) => key,
        Err(e) => {
            error!(path = %sa_path, "Failed to load service account: {e}");
            return (Arc::new(StubFcm), Arc::new(StubFirebaseAuth));
        }
    };

    let project_id = if config.firebase.project_id.is_empty() {
        // Fall back to the project_id in the service account file
        sa_key.project_id.clone()
    } else {
        config.firebase.project_id.clone()
    };

    info!(
        project_id = %project_id,
        "Service account loaded, wiring real FCM + Firebase Auth"
    );

    // Build token provider for FCM
    let auth_manager = rouse_relay::google_auth::fcm_auth_manager(sa_key);

    // FCM base URL: allow override via env var for testing
    let fcm_base_url = std::env::var("FCM_BASE_URL")
        .unwrap_or_else(|_| rouse_relay::fcm::DEFAULT_FCM_BASE_URL.to_string());

    let fcm: Arc<dyn rouse_relay::fcm::FcmClient> = Arc::new(rouse_relay::fcm::RealFcmClient::new(
        fcm_base_url,
        project_id.clone(),
        auth_manager,
    ));

    let firebase_auth: Arc<dyn rouse_relay::firebase_auth::FirebaseAuth> = Arc::new(
        rouse_relay::firebase_auth::RealFirebaseAuth::new(project_id),
    );

    (fcm, firebase_auth)
}

// --- Stub implementations for external services ---
// Used as fallbacks when no service account is configured.

/// In-memory Firestore implementation for development/testing.
/// Persists data for the relay's lifetime but not across restarts.
/// TODO: Replace with RealFirestoreClient backed by Firestore REST API.
struct InMemoryFirestore {
    devices: std::sync::Mutex<std::collections::HashMap<String, rouse_relay::firestore::DeviceRecord>>,
    pending_certs: std::sync::Mutex<std::collections::HashMap<String, rouse_relay::firestore::PendingCert>>,
}

impl InMemoryFirestore {
    fn new() -> Self {
        Self {
            devices: std::sync::Mutex::new(std::collections::HashMap::new()),
            pending_certs: std::sync::Mutex::new(std::collections::HashMap::new()),
        }
    }
}

#[async_trait::async_trait]
impl rouse_relay::firestore::FirestoreClient for InMemoryFirestore {
    async fn get_device(
        &self,
        subdomain: &str,
    ) -> Result<rouse_relay::firestore::DeviceRecord, rouse_relay::firestore::FirestoreError> {
        let devices = self.devices.lock().unwrap();
        devices.get(subdomain).cloned().ok_or_else(|| {
            rouse_relay::firestore::FirestoreError::NotFound(subdomain.to_string())
        })
    }

    async fn find_device_by_uid(
        &self,
        firebase_uid: &str,
    ) -> Result<
        Option<(String, rouse_relay::firestore::DeviceRecord)>,
        rouse_relay::firestore::FirestoreError,
    > {
        let devices = self.devices.lock().unwrap();
        Ok(devices
            .iter()
            .find(|(_, r)| r.firebase_uid == firebase_uid)
            .map(|(k, v)| (k.clone(), v.clone())))
    }

    async fn put_device(
        &self,
        subdomain: &str,
        record: &rouse_relay::firestore::DeviceRecord,
    ) -> Result<(), rouse_relay::firestore::FirestoreError> {
        let mut devices = self.devices.lock().unwrap();
        devices.insert(subdomain.to_string(), record.clone());
        Ok(())
    }

    async fn delete_device(
        &self,
        subdomain: &str,
    ) -> Result<(), rouse_relay::firestore::FirestoreError> {
        let mut devices = self.devices.lock().unwrap();
        devices.remove(subdomain);
        Ok(())
    }

    async fn put_pending_cert(
        &self,
        subdomain: &str,
        pending: &rouse_relay::firestore::PendingCert,
    ) -> Result<(), rouse_relay::firestore::FirestoreError> {
        let mut certs = self.pending_certs.lock().unwrap();
        certs.insert(subdomain.to_string(), pending.clone());
        Ok(())
    }

    async fn get_pending_cert(
        &self,
        subdomain: &str,
    ) -> Result<rouse_relay::firestore::PendingCert, rouse_relay::firestore::FirestoreError> {
        let certs = self.pending_certs.lock().unwrap();
        certs.get(subdomain).cloned().ok_or_else(|| {
            rouse_relay::firestore::FirestoreError::NotFound(subdomain.to_string())
        })
    }

    async fn delete_pending_cert(
        &self,
        subdomain: &str,
    ) -> Result<(), rouse_relay::firestore::FirestoreError> {
        let mut certs = self.pending_certs.lock().unwrap();
        certs.remove(subdomain);
        Ok(())
    }

    async fn list_devices(
        &self,
    ) -> Result<
        Vec<(String, rouse_relay::firestore::DeviceRecord)>,
        rouse_relay::firestore::FirestoreError,
    > {
        let devices = self.devices.lock().unwrap();
        Ok(devices.iter().map(|(k, v)| (k.clone(), v.clone())).collect())
    }

    async fn list_pending_certs(
        &self,
    ) -> Result<
        Vec<(String, rouse_relay::firestore::PendingCert)>,
        rouse_relay::firestore::FirestoreError,
    > {
        let certs = self.pending_certs.lock().unwrap();
        Ok(certs.iter().map(|(k, v)| (k.clone(), v.clone())).collect())
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
        _csr_der: Option<&[u8]>,
    ) -> Result<rouse_relay::acme::CertificateBundle, rouse_relay::acme::AcmeError> {
        Ok(rouse_relay::acme::CertificateBundle {
            cert_pem: "stub-cert".to_string(),
            private_key_pem: None,
        })
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
