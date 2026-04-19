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
use rouse_relay::rate_limit::{ConnectionRateLimiter, FcmWakeThrottle};
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

    // Parse CLI args. Layout is positional:
    //   rouse-relay [config_path] [--test-mode <port>]
    // The `--test-mode` flag is only recognised when the binary was built with
    // the `test-mode` feature; in release builds the flag is silently ignored
    // (and warned about) so a misconfigured systemd unit can't accidentally
    // enable the admin surface.
    let (config_path, test_admin_port) = parse_cli_args();

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
    let relay_state = Arc::new(RelayState::with_cache_capacity(
        config.server.cache_capacity,
    ));
    let session_registry = Arc::new(SessionRegistry::new());

    // Start the test-mode admin server (no-op unless the binary was built
    // with `--features test-mode` AND --test-mode <port> was passed).
    let _test_metrics =
        start_test_admin_if_enabled(test_admin_port, session_registry.clone()).await;

    // Build external service clients — use real implementations when a service account is available
    let firestore: Arc<dyn rouse_relay::firestore::FirestoreClient> =
        build_firestore_client(&config);
    let acme: Arc<dyn rouse_relay::acme::AcmeClient> = build_acme_client(&config);

    let (fcm, firebase_auth): (
        Arc<dyn rouse_relay::fcm::FcmClient>,
        Arc<dyn rouse_relay::firebase_auth::FirebaseAuth>,
    ) = build_service_clients(&config);

    let dns: Arc<dyn rouse_relay::dns::DnsClient> = build_dns_client(&config);

    let request_subdomain_limiter_cfg = rouse_relay::rate_limit::RateLimitConfig {
        max_tokens: config.limits.request_subdomain_rate_burst,
        refill_interval: Duration::from_secs(config.limits.request_subdomain_rate_refill_secs),
    };

    let app_state = Arc::new(AppState {
        relay_state: relay_state.clone(),
        session_registry: session_registry.clone(),
        firestore: firestore.clone(),
        fcm: fcm.clone(),
        acme: acme.clone(),
        dns: dns.clone(),
        firebase_auth,
        subdomain_generator: rouse_relay::subdomain::SubdomainGenerator::new(),
        rate_limiter: rouse_relay::rate_limit::RateLimiter::new(
            rouse_relay::rate_limit::RateLimitConfig::default(),
        ),
        request_subdomain_rate_limiter: rouse_relay::rate_limit::RateLimiter::new(
            request_subdomain_limiter_cfg,
        ),
        config: config.clone(),
        device_ca,
        #[cfg(feature = "test-mode")]
        test_metrics: _test_metrics.clone(),
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
        stale_device_sweep_days: config.limits.stale_device_sweep_days,
        ..Default::default()
    };
    let maint_shutdown = shutdown.clone();
    tokio::spawn(maintenance::run_maintenance_loop(
        maint_config,
        firestore.clone(),
        fcm.clone(),
        acme.clone(),
        dns,
        maint_shutdown,
    ));

    // Bot protection: rate limiters for passthrough connections
    // Throttle must be shorter than fcm_wakeup_timeout so a failed wake
    // allows a retry on the next client connection.
    let fcm_wake_throttle = Arc::new(FcmWakeThrottle::new(Duration::from_secs(10)));
    // All AI-client requests for a given user come from a single backend IP
    // (e.g. Anthropic's MCP gateway), so this limit is effectively per-user,
    // not per-attacker. Claude's integration discovery/setup can fire 10+
    // connections within a second; 20/min got tripped repeatedly in prod
    // logs (ivory subdomain, 2026-04-16). 200/min is comfortably above
    // realistic bursts while still blocking naive scanners. Tunable via
    // `[limits] conn_rate_limit_max / conn_rate_limit_window_secs` (#190).
    let conn_rate_limiter = Arc::new(ConnectionRateLimiter::new(
        config.limits.conn_rate_limit_max,
        Duration::from_secs(config.limits.conn_rate_limit_window_secs),
    ));

    // Spawn rate-limiter sweep loop.
    //
    // The rate-limiter DashMaps would otherwise grow unbounded: an attacker
    // varying source IP or SNI label inserts a fresh entry on every rejected
    // connection and memory climbs until OOM (#203). Every 5 minutes we
    // evict entries whose window/cooldown has fully expired — their state is
    // indistinguishable from a never-seen key, so eviction never changes a
    // rate-limit decision. The interval is short enough to cap memory growth
    // but long enough to stay off the hot path.
    let sweep_shutdown = shutdown.clone();
    let sweep_app_state = app_state.clone();
    let sweep_fcm_throttle = fcm_wake_throttle.clone();
    let sweep_conn_limiter = conn_rate_limiter.clone();
    tokio::spawn(async move {
        rouse_relay::rate_limit::run_rate_limit_sweep_loop(
            Duration::from_secs(300),
            sweep_app_state,
            sweep_fcm_throttle,
            sweep_conn_limiter,
            sweep_shutdown,
        )
        .await;
    });

    let relay_hostname = config.server.relay_hostname.clone();
    let base_domain = config.server.resolved_base_domain();
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
                    fcm_wake_throttle: fcm_wake_throttle.clone(),
                    conn_rate_limiter: conn_rate_limiter.clone(),
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
    fcm_wake_throttle: Arc<FcmWakeThrottle>,
    conn_rate_limiter: Arc<ConnectionRateLimiter>,
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
    info!(peer = %peer_addr, ?decision, "Routing decision");

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
        RouteDecision::DevicePassthrough {
            subdomain,
            integration_secret,
        } => {
            // Per-IP rate limit: reject scanners before doing any real work
            if !ctx.conn_rate_limiter.allow(peer_addr.ip(), &subdomain) {
                debug!(
                    peer = %peer_addr,
                    subdomain = %subdomain,
                    "Connection rate limited (per-IP)"
                );
                return Ok(());
            }

            // Read the ClientHello bytes (consume them from the socket)
            let mut client_hello = vec![0u8; n];
            let n_read = stream.read(&mut client_hello).await?;
            client_hello.truncate(n_read);

            // Build the SNI hostname for the OPEN frame
            let sni_hostname = format!("{integration_secret}.{subdomain}.{}", ctx.base_domain);

            // Record the routed SNI for test-mode observers. `--test-mode` off
            // (release builds) leaves `test_metrics` as None so this is a
            // single nullable check on the hot path.
            #[cfg(feature = "test-mode")]
            if let Some(metrics) = &ctx.app_state.test_metrics {
                metrics.record_routed_passthrough(&sni_hostname);
            }

            let pt_ctx = PassthroughContext {
                relay_state: ctx.relay_state,
                session_registry: ctx.session_registry,
                firestore: ctx.firestore,
                fcm: ctx.fcm,
                relay_hostname: ctx.relay_hostname,
                fcm_wakeup_timeout: ctx.fcm_timeout,
                fcm_wake_throttle: ctx.fcm_wake_throttle,
            };

            let resolved = passthrough::resolve_device_stream(
                &pt_ctx,
                &subdomain,
                &sni_hostname,
                &integration_secret,
            )
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
/// via Cloudflare and issues certs from the configured ACME provider (default:
/// Google Trust Services, see #213). Falls back to `StubAcme` with a warning
/// when credentials are missing.
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

    // Derive the base domain from config (falls back to stripping "relay." from relay_hostname).
    let base_domain = config.server.resolved_base_domain();

    // Resolve ACME directory URL: config file > default (Google Trust Services).
    // Env var override already applied in apply_env_overrides().
    let directory_url = if !config.acme.directory_url.is_empty() {
        config.acme.directory_url.clone()
    } else {
        rouse_relay::acme::GOOGLE_TRUST_SERVICES_DIRECTORY.to_string()
    };

    // Resolve External Account Binding credentials if the provider (e.g. GTS)
    // requires them. Resolution returns None cleanly when the env vars are
    // unset, which is the correct behaviour for LE-style providers.
    let eab = match config.acme.resolve_eab() {
        Ok(eab) => eab,
        Err(e) => {
            warn!("Failed to resolve ACME EAB credentials ({e}), using stub ACME client");
            return Arc::new(StubAcme);
        }
    };

    let account_key_path = std::path::PathBuf::from(&config.acme.account_key_path);

    info!(
        zone_id = %cf.zone_id,
        base_domain = %base_domain,
        directory_url = %directory_url,
        account_key_path = %account_key_path.display(),
        require_existing_account = config.acme.require_existing_account,
        dns_propagation_timeout_secs = config.acme.dns_propagation_timeout_secs,
        dns_poll_interval_secs = config.acme.dns_poll_interval_secs,
        eab_configured = eab.is_some(),
        "Wiring real ACME client with Cloudflare DNS-01"
    );

    let dns: Arc<dyn rouse_relay::dns_challenge::DnsChallengeProvider> =
        Arc::new(rouse_relay::cloudflare_dns::CloudflareDnsProvider::new(
            api_token,
            cf.zone_id.clone(),
            base_domain.clone(),
            config.acme.dns_propagation_timeout_secs,
            config.acme.dns_poll_interval_secs,
        ));

    let mut client = rouse_relay::acme::RealAcmeClient::with_persistent_key(
        directory_url,
        dns,
        base_domain,
        &account_key_path,
        config.acme.require_existing_account,
    );
    if let Some((kid, hmac_key)) = eab {
        client = client.with_external_account_binding(rouse_relay::acme::ExternalAccountBinding {
            kid,
            hmac_key,
        });
    }
    Arc::new(client)
}

/// Build the DNS client from config.
///
/// When Cloudflare credentials are configured, creates a `CloudflareDnsClient` that
/// can manage CNAME records. Falls back to `StubDnsClient` when credentials are missing.
fn build_dns_client(config: &RelayConfig) -> Arc<dyn rouse_relay::dns::DnsClient> {
    let cf = &config.cloudflare;

    if cf.zone_id.is_empty() {
        info!("No cloudflare.zone_id configured, using stub DNS client");
        return Arc::new(rouse_relay::dns::StubDnsClient);
    }

    let api_token = match cf.resolve_api_token() {
        Ok(token) => token,
        Err(e) => {
            warn!("Cloudflare API token not available ({e}), using stub DNS client");
            return Arc::new(rouse_relay::dns::StubDnsClient);
        }
    };

    let base_domain = config.server.resolved_base_domain();

    info!(
        zone_id = %cf.zone_id,
        base_domain = %base_domain,
        "Wiring Cloudflare DNS client for subdomain management"
    );

    Arc::new(rouse_relay::dns::CloudflareDnsClient::new(
        api_token,
        cf.zone_id.clone(),
        base_domain,
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

    // TEST-ONLY: if verify_tokens is explicitly disabled, swap in a Noop
    // Firebase auth that accepts any token. This knob is only ever set in
    // test fixtures; production configs never flip it.
    let firebase_auth_override: Option<Arc<dyn rouse_relay::firebase_auth::FirebaseAuth>> =
        if !config.firebase.verify_tokens {
            warn!(
                "firebase.verify_tokens = false — accepting any Firebase ID token WITHOUT \
                 verification. This is only safe in integration tests. Do NOT enable in \
                 production: it allows anyone to register a device under any UID."
            );
            Some(Arc::new(NoopFirebaseAuth))
        } else {
            None
        };

    if sa_path.is_empty() || !Path::new(sa_path).exists() {
        if sa_path.is_empty() {
            info!("No firebase.service_account_path configured, using stub FCM + Firebase Auth");
        } else {
            warn!(
                path = %sa_path,
                "Service account file not found, using stub FCM + Firebase Auth"
            );
        }
        let firebase_auth = firebase_auth_override.unwrap_or_else(|| Arc::new(StubFirebaseAuth));
        return (Arc::new(StubFcm), firebase_auth);
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

    let firebase_auth: Arc<dyn rouse_relay::firebase_auth::FirebaseAuth> = firebase_auth_override
        .unwrap_or_else(|| {
            Arc::new(rouse_relay::firebase_auth::RealFirebaseAuth::new(
                project_id,
            ))
        });

    (fcm, firebase_auth)
}

/// Build the Firestore client from config.
///
/// When `firebase.service_account_path` points to a valid service account JSON
/// file, this creates a `RealFirestoreClient` that talks to the Firestore REST
/// API. Falls back to `InMemoryFirestore` when no service account is available.
fn build_firestore_client(
    config: &RelayConfig,
) -> Arc<dyn rouse_relay::firestore::FirestoreClient> {
    let sa_path = &config.firebase.service_account_path;

    if sa_path.is_empty() || !Path::new(sa_path).exists() {
        info!("No service account for Firestore, using in-memory store (data lost on restart)");
        return Arc::new(InMemoryFirestore::new());
    }

    let sa_key = match rouse_relay::google_auth::ServiceAccountKey::from_file(Path::new(sa_path)) {
        Ok(key) => key,
        Err(e) => {
            warn!(path = %sa_path, "Failed to load service account for Firestore: {e}, falling back to in-memory");
            return Arc::new(InMemoryFirestore::new());
        }
    };

    let project_id = if config.firebase.project_id.is_empty() {
        sa_key.project_id.clone()
    } else {
        config.firebase.project_id.clone()
    };

    let auth_manager = Arc::new(rouse_relay::google_auth::GoogleAuthManager::new(
        sa_key,
        vec![rouse_relay::firestore_client::FIRESTORE_SCOPE.to_string()],
    ));

    info!(project_id = %project_id, "Using real Firestore REST API client");
    Arc::new(rouse_relay::firestore_client::RealFirestoreClient::new(
        &project_id,
        auth_manager,
    ))
}

// --- Stub implementations for external services ---
// Used as fallbacks when no service account is configured.

/// In-memory Firestore implementation for development/testing.
/// Persists data for the relay's lifetime but not across restarts.
/// TODO: Replace with RealFirestoreClient backed by Firestore REST API.
struct InMemoryFirestore {
    devices:
        std::sync::Mutex<std::collections::HashMap<String, rouse_relay::firestore::DeviceRecord>>,
    pending_certs:
        std::sync::Mutex<std::collections::HashMap<String, rouse_relay::firestore::PendingCert>>,
    reservations: std::sync::Mutex<
        std::collections::HashMap<String, rouse_relay::firestore::SubdomainReservation>,
    >,
}

impl InMemoryFirestore {
    fn new() -> Self {
        Self {
            devices: std::sync::Mutex::new(std::collections::HashMap::new()),
            pending_certs: std::sync::Mutex::new(std::collections::HashMap::new()),
            reservations: std::sync::Mutex::new(std::collections::HashMap::new()),
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
        devices
            .get(subdomain)
            .cloned()
            .ok_or_else(|| rouse_relay::firestore::FirestoreError::NotFound(subdomain.to_string()))
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
        certs
            .get(subdomain)
            .cloned()
            .ok_or_else(|| rouse_relay::firestore::FirestoreError::NotFound(subdomain.to_string()))
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
        Ok(devices
            .iter()
            .map(|(k, v)| (k.clone(), v.clone()))
            .collect())
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

    async fn put_reservation(
        &self,
        subdomain: &str,
        reservation: &rouse_relay::firestore::SubdomainReservation,
    ) -> Result<(), rouse_relay::firestore::FirestoreError> {
        let mut map = self.reservations.lock().unwrap();
        map.insert(subdomain.to_string(), reservation.clone());
        Ok(())
    }

    async fn get_reservation(
        &self,
        subdomain: &str,
    ) -> Result<rouse_relay::firestore::SubdomainReservation, rouse_relay::firestore::FirestoreError>
    {
        let map = self.reservations.lock().unwrap();
        map.get(subdomain)
            .cloned()
            .ok_or_else(|| rouse_relay::firestore::FirestoreError::NotFound(subdomain.to_string()))
    }

    async fn find_reservation_by_uid(
        &self,
        firebase_uid: &str,
    ) -> Result<
        Option<(String, rouse_relay::firestore::SubdomainReservation)>,
        rouse_relay::firestore::FirestoreError,
    > {
        let map = self.reservations.lock().unwrap();
        Ok(map
            .iter()
            .find(|(_, r)| r.firebase_uid == firebase_uid)
            .map(|(k, v)| (k.clone(), v.clone())))
    }

    async fn delete_reservation(
        &self,
        subdomain: &str,
    ) -> Result<(), rouse_relay::firestore::FirestoreError> {
        let mut map = self.reservations.lock().unwrap();
        map.remove(subdomain);
        Ok(())
    }

    async fn list_reservations(
        &self,
    ) -> Result<
        Vec<(String, rouse_relay::firestore::SubdomainReservation)>,
        rouse_relay::firestore::FirestoreError,
    > {
        let map = self.reservations.lock().unwrap();
        Ok(map.iter().map(|(k, v)| (k.clone(), v.clone())).collect())
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

/// TEST-ONLY Firebase auth that accepts any non-empty token and derives a
/// deterministic `uid` from the token bytes. Selected via the `FirebaseConfig::
/// verify_tokens = false` config knob. See the comment on that field for
/// security notes.
struct NoopFirebaseAuth;

#[async_trait::async_trait]
impl rouse_relay::firebase_auth::FirebaseAuth for NoopFirebaseAuth {
    async fn verify_id_token(
        &self,
        token: &str,
    ) -> Result<
        rouse_relay::firebase_auth::FirebaseClaims,
        rouse_relay::firebase_auth::FirebaseAuthError,
    > {
        if token.is_empty() {
            return Err(rouse_relay::firebase_auth::FirebaseAuthError::InvalidToken(
                "empty token".to_string(),
            ));
        }
        // Derive a deterministic uid so repeated calls with the same token
        // map to the same device record, matching real Firebase behavior.
        Ok(rouse_relay::firebase_auth::FirebaseClaims {
            uid: format!("test-uid-{}", stable_uid_hash(token)),
        })
    }
}

fn stable_uid_hash(token: &str) -> String {
    use std::collections::hash_map::DefaultHasher;
    use std::hash::{Hash, Hasher};
    let mut hasher = DefaultHasher::new();
    token.hash(&mut hasher);
    format!("{:016x}", hasher.finish())
}

/// Parse CLI args into `(config_path, test_admin_port)`. See issue #249.
fn parse_cli_args() -> (String, Option<u16>) {
    let mut config_path = "relay.toml".to_string();
    let mut test_admin_port: Option<u16> = None;

    let args: Vec<String> = std::env::args().skip(1).collect();
    let mut i = 0;
    let mut config_set = false;
    while i < args.len() {
        let arg = &args[i];
        if arg == "--test-mode" {
            // Accept `--test-mode <port>` or `--test-mode=<port>`. We
            // deliberately do NOT error on unknown flags after this for
            // forward-compat.
            let port_str = args.get(i + 1).cloned();
            match port_str.as_deref().and_then(|s| s.parse::<u16>().ok()) {
                Some(port) => {
                    test_admin_port = Some(port);
                    i += 2;
                    continue;
                }
                None => {
                    error!("--test-mode requires a port number argument");
                    std::process::exit(2);
                }
            }
        }
        if let Some(value) = arg.strip_prefix("--test-mode=") {
            match value.parse::<u16>() {
                Ok(port) => test_admin_port = Some(port),
                Err(_) => {
                    error!("--test-mode=<port> could not be parsed");
                    std::process::exit(2);
                }
            }
            i += 1;
            continue;
        }
        if !arg.starts_with("--") && !config_set {
            config_path = arg.clone();
            config_set = true;
        }
        i += 1;
    }

    // Env-var fallback so CI can pass TEST_MODE_PORT without touching argv.
    if test_admin_port.is_none() {
        if let Ok(v) = std::env::var("TEST_MODE_PORT") {
            if let Ok(port) = v.parse::<u16>() {
                test_admin_port = Some(port);
            }
        }
    }

    (config_path, test_admin_port)
}

#[cfg(feature = "test-mode")]
async fn start_test_admin_if_enabled(
    port: Option<u16>,
    session_registry: Arc<SessionRegistry>,
) -> Option<Arc<rouse_relay::test_mode::TestMetrics>> {
    let port = port?;
    let metrics = Arc::new(rouse_relay::test_mode::TestMetrics::new());
    if let Err(e) =
        rouse_relay::test_mode::spawn_admin_server(port, metrics.clone(), session_registry).await
    {
        error!(port, "Failed to start test-mode admin server: {e}");
        std::process::exit(1);
    }
    warn!(
        port,
        "test-mode admin server started — DO NOT ENABLE IN PRODUCTION"
    );
    Some(metrics)
}

#[cfg(not(feature = "test-mode"))]
async fn start_test_admin_if_enabled(
    port: Option<u16>,
    _session_registry: Arc<SessionRegistry>,
) -> Option<()> {
    if port.is_some() {
        warn!(
            "--test-mode / TEST_MODE_PORT set but binary built without the \
             `test-mode` feature; ignoring"
        );
    }
    None
}
