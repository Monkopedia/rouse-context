package com.rousecontext.app.di

import android.app.NotificationManager
import android.os.PowerManager
import android.util.Log
import com.rousecontext.api.IntegrationStateStore
import com.rousecontext.api.LaunchRequestNotifierApi
import com.rousecontext.api.McpIntegration
import com.rousecontext.api.NotificationSettingsProvider
import com.rousecontext.app.BuildConfig
import com.rousecontext.app.MainActivity
import com.rousecontext.app.McpUrlProvider
import com.rousecontext.app.cert.FileCertificateStore
import com.rousecontext.app.cert.LazyWebSocketFactory
import com.rousecontext.app.receivers.AuthApprovalReceiver
import com.rousecontext.app.registry.HealthConnectIntegration
import com.rousecontext.app.registry.IntegrationProviderRegistry
import com.rousecontext.app.registry.NotificationIntegration
import com.rousecontext.app.registry.OutreachIntegration
import com.rousecontext.app.registry.UsageIntegration
import com.rousecontext.app.session.CertStoreTlsCertProvider
import com.rousecontext.app.session.SharedMcpSessionFactory
import com.rousecontext.app.state.AppStatePreferences
import com.rousecontext.app.state.DataStoreIntegrationStateStore
import com.rousecontext.app.state.DataStoreNotificationSettingsProvider
import com.rousecontext.app.state.DeviceRegistrationStatus
import com.rousecontext.app.state.IntegrationSettingsStore
import com.rousecontext.app.state.NotificationPermissionRefresher
import com.rousecontext.app.state.OAuthHostnameProvider
import com.rousecontext.app.state.PreferencesSnapshotHolder
import com.rousecontext.app.state.ThemePreference
import com.rousecontext.app.state.notificationPermissionFlow
import com.rousecontext.app.support.BugReportUriBuilder
import com.rousecontext.app.token.RoomTokenStore
import com.rousecontext.app.token.TokenDatabase
import com.rousecontext.app.ui.viewmodels.AddIntegrationViewModel
import com.rousecontext.app.ui.viewmodels.AuditHistoryViewModel
import com.rousecontext.app.ui.viewmodels.AuthorizationApprovalViewModel
import com.rousecontext.app.ui.viewmodels.HealthConnectSetupViewModel
import com.rousecontext.app.ui.viewmodels.IntegrationManageViewModel
import com.rousecontext.app.ui.viewmodels.IntegrationSetupViewModel
import com.rousecontext.app.ui.viewmodels.MainDashboardViewModel
import com.rousecontext.app.ui.viewmodels.NotificationPreferencesViewModel
import com.rousecontext.app.ui.viewmodels.NotificationSetupViewModel
import com.rousecontext.app.ui.viewmodels.OnboardingViewModel
import com.rousecontext.app.ui.viewmodels.OutreachSetupViewModel
import com.rousecontext.app.ui.viewmodels.SettingsViewModel
import com.rousecontext.app.ui.viewmodels.UsageSetupViewModel
import com.rousecontext.bridge.McpSessionFactory
import com.rousecontext.bridge.SessionHandler
import com.rousecontext.bridge.TlsCertProvider
import com.rousecontext.integrations.health.HealthConnectRepository
import com.rousecontext.integrations.health.RealHealthConnectRepository
import com.rousecontext.integrations.notifications.NotificationDatabase
import com.rousecontext.mcp.core.AuditListener
import com.rousecontext.mcp.core.McpSession
import com.rousecontext.mcp.core.ProviderRegistry
import com.rousecontext.mcp.core.TokenStore
import com.rousecontext.notifications.AndroidSecurityCheckNotifier
import com.rousecontext.notifications.AuthRequestNotifier
import com.rousecontext.notifications.FieldEncryptor
import com.rousecontext.notifications.LaunchRequestNotifier
import com.rousecontext.notifications.PerToolCallNotifier
import com.rousecontext.notifications.SecurityCheckNotifier
import com.rousecontext.notifications.SessionSummaryNotifier
import com.rousecontext.notifications.audit.AuditDatabase
import com.rousecontext.notifications.audit.PerCallObserver
import com.rousecontext.notifications.audit.RoomAuditListener
import com.rousecontext.tunnel.CertProvisioningFlow
import com.rousecontext.tunnel.CertRenewalFlow
import com.rousecontext.tunnel.CertificateStore
import com.rousecontext.tunnel.CsrGenerator
import com.rousecontext.tunnel.CtLogFetcher
import com.rousecontext.tunnel.CtLogMonitor
import com.rousecontext.tunnel.HttpCtLogFetcher
import com.rousecontext.tunnel.OnboardingFlow
import com.rousecontext.tunnel.RelayApiClient
import com.rousecontext.tunnel.SelfCertVerifier
import com.rousecontext.tunnel.TunnelClient
import com.rousecontext.tunnel.TunnelClientImpl
import com.rousecontext.work.AndroidKeystoreSigner
import com.rousecontext.work.CertRenewalFlowRenewer
import com.rousecontext.work.CertRenewalPreferences
import com.rousecontext.work.CertRenewalWorker
import com.rousecontext.work.CertRenewer
import com.rousecontext.work.CtLogMonitorSource
import com.rousecontext.work.DataStoreSpuriousWakeRecorder
import com.rousecontext.work.DeviceKeystoreSigner
import com.rousecontext.work.FcmTokenRegistrar
import com.rousecontext.work.FirebaseRenewalAuthProvider
import com.rousecontext.work.IdleTimeoutManager
import com.rousecontext.work.RealWakeLockHandle
import com.rousecontext.work.RenewalAuthProvider
import com.rousecontext.work.SecurityCheckPreferences
import com.rousecontext.work.SecurityCheckSource
import com.rousecontext.work.SpuriousWakePreferences
import com.rousecontext.work.SpuriousWakeRecorder
import com.rousecontext.work.StoredCertVerifierSource
import com.rousecontext.work.WakelockManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.singleOf
import org.koin.core.module.dsl.viewModel
import org.koin.core.qualifier.named
import org.koin.dsl.bind
import org.koin.dsl.module

/** Idle timeout before disconnecting the tunnel after all streams close. */
private const val IDLE_TIMEOUT_MS = 5 * 60 * 1000L

/**
 * crt.sh issuer names that are legitimate for Rouse Context certificates.
 * Any CT log entry with an issuer outside this set triggers a security alert.
 *
 * The relay uses Let's Encrypt via ACME; these are the currently-rotated
 * Let's Encrypt intermediates. Update when LE rotates intermediates.
 */
private val EXPECTED_CERT_ISSUERS: Set<String> = setOf(
    "C=US, O=Let's Encrypt, CN=R3",
    "C=US, O=Let's Encrypt, CN=R10",
    "C=US, O=Let's Encrypt, CN=R11",
    "C=US, O=Let's Encrypt, CN=R12",
    "C=US, O=Let's Encrypt, CN=R13",
    "C=US, O=Let's Encrypt, CN=R14",
    "C=US, O=Let's Encrypt, CN=E1",
    "C=US, O=Let's Encrypt, CN=E5",
    "C=US, O=Let's Encrypt, CN=E6",
    "C=US, O=Let's Encrypt, CN=E7",
    "C=US, O=Let's Encrypt, CN=E8",
    "C=US, O=Let's Encrypt, CN=E9"
)

/**
 * Root Koin module that wires all app dependencies.
 */
val appModule = module {

    // --- Databases ---
    single { TokenDatabase.create(androidContext()) }
    single { get<TokenDatabase>().tokenDao() }
    single { AuditDatabase.create(androidContext()) }
    single { get<AuditDatabase>().auditDao() }
    single { get<AuditDatabase>().mcpRequestDao() }
    single { NotificationDatabase.create(androidContext()) }
    single { get<NotificationDatabase>().notificationDao() }

    // --- Field encryption ---
    single { FieldEncryptor(androidContext()) }

    // --- Notification permission refresher ---
    single { NotificationPermissionRefresher() }

    // --- Bug report URI builder ---
    single { BugReportUriBuilder(androidContext()) }

    // --- Certificate store ---
    single { FileCertificateStore(androidContext()) } bind CertificateStore::class

    // --- URL provider ---
    single { McpUrlProvider(get(), BuildConfig.BASE_DOMAIN) }

    // --- Device registration status ---
    single {
        val certStore: CertificateStore = get()
        val appScope: CoroutineScope = get(named("appScope"))
        val status = DeviceRegistrationStatus(initiallyRegistered = false)
        appScope.launch {
            if (certStore.getSubdomain() != null) {
                status.markComplete()
            }
        }
        status
    }

    // --- Onboarding ---
    single { CsrGenerator() }
    single {
        val httpScheme = if (BuildConfig.RELAY_SCHEME == "wss") "https" else "http"
        RelayApiClient("$httpScheme://${BuildConfig.RELAY_HOST}")
    }
    single {
        OnboardingFlow(
            relayApiClient = get<RelayApiClient>(),
            certificateStore = get<CertificateStore>(),
            integrationIds = get<List<McpIntegration>>().map { it.id }
        )
    }
    single { CertProvisioningFlow(get(), get(), get(), BuildConfig.BASE_DOMAIN) }

    // --- Cert renewal (periodic worker) ---
    single<String>(named(CertRenewalWorker.KOIN_BASE_DOMAIN_NAME)) { BuildConfig.BASE_DOMAIN }
    single {
        CertRenewalFlow(
            csrGenerator = get(),
            relayApiClient = get(),
            certificateStore = get()
        )
    }
    single<CertRenewer> { CertRenewalFlowRenewer(get()) }
    single<DeviceKeystoreSigner> { AndroidKeystoreSigner() }
    single<RenewalAuthProvider> { FirebaseRenewalAuthProvider(signer = get()) }

    // --- Token store ---
    singleOf(::RoomTokenStore) bind TokenStore::class

    // --- Integration state ---
    single<IntegrationStateStore> { DataStoreIntegrationStateStore(androidContext()) }

    // --- Integration settings (per-integration preferences) ---
    single { IntegrationSettingsStore(androidContext()) }

    // --- App-level preferences (first-launch marker, security-check schedule) ---
    single { AppStatePreferences(androidContext()) }

    // --- Worker-owned preferences (DataStore-backed, replaces legacy SharedPrefs) ---
    single { SecurityCheckPreferences(androidContext()) }
    single { CertRenewalPreferences(androidContext()) }
    single { SpuriousWakePreferences(androidContext()) }

    // --- Theme preference ---
    single { ThemePreference(androidContext()) }

    // --- Notification settings ---
    single<NotificationSettingsProvider> { DataStoreNotificationSettingsProvider(androidContext()) }

    // --- Health Connect repository ---
    single<HealthConnectRepository> { RealHealthConnectRepository(androidContext()) }

    // --- Launch-request notifier (Android 14+ background-activity fallback) ---
    single<LaunchRequestNotifierApi> { LaunchRequestNotifier(androidContext()) }

    // --- Integrations ---
    single<McpIntegration>(named("health")) { HealthConnectIntegration(androidContext()) }
    single<McpIntegration>(named("outreach")) {
        OutreachIntegration(
            context = androidContext(),
            settingsStore = get(),
            launchNotifier = get(),
            appScope = get(named("appScope"))
        )
    }
    single<McpIntegration>(named("notifications")) {
        NotificationIntegration(androidContext(), get(), get())
    }
    single<McpIntegration>(named("usage")) { UsageIntegration(androidContext()) }

    single<List<McpIntegration>> {
        buildList {
            add(get(named("notifications")))
            add(get(named("outreach")))
            add(get(named("usage")))
            add(get(named("health")))
            getKoin().getOrNull<McpIntegration>(named("test"))?.let { add(it) }
        }
    }

    // --- Provider registry ---
    single<ProviderRegistry> {
        IntegrationProviderRegistry(
            integrations = get(),
            stateStore = get(),
            appScope = get(named("appScope"))
        )
    }

    // --- Preferences snapshot holder (live reactive view of all prefs) ---
    single {
        PreferencesSnapshotHolder(
            integrationStateStore = get(),
            integrationSettingsStore = get(),
            notificationSettingsProvider = get(),
            integrations = get(),
            appScope = get(named("appScope"))
        )
    }

    // --- Per-tool-call notifier (EACH_USAGE mode) ---
    single {
        val integrations: List<McpIntegration> = get()
        PerToolCallNotifier(
            context = androidContext(),
            settingsProvider = get(),
            integrationDisplayNames = integrations.associate { it.id to it.displayName },
            activityClass = MainActivity::class.java
        )
    } bind PerCallObserver::class

    // --- Audit listener ---
    single<AuditListener> {
        RoomAuditListener(
            dao = get(),
            scope = get(named("appScope")),
            fieldEncryptor = get(),
            perCallObserver = get(),
            mcpRequestDao = get()
        )
    }

    // --- Auth request notifier ---
    single {
        AuthRequestNotifier(
            context = androidContext(),
            receiverClass = AuthApprovalReceiver::class.java,
            approveAction = AuthApprovalReceiver.ACTION_APPROVE,
            denyAction = AuthApprovalReceiver.ACTION_DENY,
            activityClass = MainActivity::class.java,
            extraDisplayCode = AuthApprovalReceiver.EXTRA_DISPLAY_CODE,
            extraNotificationId = AuthApprovalReceiver.EXTRA_NOTIFICATION_ID
        )
    }

    // --- Session summary poster ---
    single {
        SessionSummaryNotifier(
            context = androidContext(),
            auditDao = get(),
            settingsProvider = get(),
            activityClass = MainActivity::class.java
        )
    }

    // --- Security checks (SecurityCheckWorker dependencies) ---
    single<CtLogFetcher> { HttpCtLogFetcher() }
    single {
        CtLogMonitor(
            certificateStore = get(),
            ctLogFetcher = get(),
            expectedIssuers = EXPECTED_CERT_ISSUERS,
            baseDomain = BuildConfig.BASE_DOMAIN
        )
    }
    single { SelfCertVerifier(certificateStore = get()) }
    single<SecurityCheckSource>(named("selfCert")) {
        StoredCertVerifierSource(certificateStore = get(), verifier = get())
    }
    single<SecurityCheckSource>(named("ctLog")) {
        CtLogMonitorSource(monitor = get())
    }
    single<SecurityCheckNotifier> { AndroidSecurityCheckNotifier(androidContext()) }

    // --- OAuth hostname provider ---
    // Resolves the public OAuth metadata hostname lazily at first use, avoiding
    // blocking DataStore reads at Koin graph construction time. In practice the
    // resolved hostname is only used as a fallback when the inbound request has
    // no Host header; real traffic overrides via the Host header.
    single {
        OAuthHostnameProvider(
            certStore = get(),
            baseDomain = BuildConfig.BASE_DOMAIN,
            integrations = get()
        )
    }

    // --- MCP session ---
    // TODO: With per-integration hostnames, each integration needs its own McpSession.
    // For now, create a single session for the first enabled integration.
    single {
        val notifier: AuthRequestNotifier = get()
        val integrations: List<McpIntegration> = get()
        val hostnameProvider: OAuthHostnameProvider = get()
        // Default to first integration id
        val defaultIntegration = integrations.firstOrNull()?.id ?: "health"
        val appScope: CoroutineScope = get(named("appScope"))
        // Start with a placeholder hostname; OAuthHostnameProvider resolves the
        // real one asynchronously and we never read it outside suspend contexts.
        val initialHostname = "localhost"
        // Kick off async resolution so logs/metrics can see the hostname if needed;
        // the fallback "localhost" is only used for responses lacking a Host header.
        appScope.launch {
            hostnameProvider.resolve()
        }
        McpSession(
            registry = get(),
            tokenStore = get(),
            auditListener = get(),
            hostname = initialHostname,
            integration = defaultIntegration,
            securityAlertCheck = run {
                val securityPrefs = get<SecurityCheckPreferences>()
                val alertFlag = combine(
                    securityPrefs.observeSelfCertResult(),
                    securityPrefs.observeCtLogResult()
                ) { self, ct -> self == "alert" || ct == "alert" }
                    .stateIn(
                        scope = appScope,
                        started = SharingStarted.Eagerly,
                        initialValue = false
                    )
                val capture: () -> Boolean = { alertFlag.value }
                capture
            },
            log = { level, msg ->
                when (level) {
                    com.rousecontext.mcp.core.LogLevel.DEBUG -> Log.d("McpRouting", msg)
                    com.rousecontext.mcp.core.LogLevel.INFO -> Log.i("McpRouting", msg)
                    com.rousecontext.mcp.core.LogLevel.WARN -> Log.w("McpRouting", msg)
                    com.rousecontext.mcp.core.LogLevel.ERROR -> Log.e("McpRouting", msg)
                }
            }
        ).also { session ->
            session.start(port = 0)
            session.authorizationCodeManager.onNewRequest = { displayCode, integration ->
                notifier.post(displayCode, integration)
            }
        }
    }

    // --- Session handler (TLS accept + bridge to MCP) ---
    // Uses the consolidated :core:bridge implementation; adapters below wire
    // the existing CertificateStore and McpSession singletons to the
    // TlsCertProvider / McpSessionFactory abstractions consumed by SessionHandler.
    single<TlsCertProvider> {
        CertStoreTlsCertProvider(certStore = get<CertificateStore>())
    }
    single<McpSessionFactory> {
        SharedMcpSessionFactory(session = get<McpSession>())
    }
    single<SessionHandler> {
        SessionHandler(
            certProvider = get(),
            mcpSessionFactory = get()
        )
    }

    // --- FCM token registration ---
    single { FcmTokenRegistrar(tunnelClient = get()) }

    // --- Tunnel & work ---
    single<String>(named("relayUrl")) {
        "${BuildConfig.RELAY_SCHEME}://${BuildConfig.RELAY_HOST}:${BuildConfig.RELAY_PORT}/ws"
    }

    single { LazyWebSocketFactory(androidContext()) }

    single<TunnelClient> {
        TunnelClientImpl(
            scope = get(named("appScope")),
            webSocketFactory = get<LazyWebSocketFactory>(),
            log = { level, msg ->
                when (level) {
                    com.rousecontext.tunnel.LogLevel.DEBUG -> Log.d("TunnelClient", msg)
                    com.rousecontext.tunnel.LogLevel.INFO -> Log.i("TunnelClient", msg)
                    com.rousecontext.tunnel.LogLevel.WARN -> Log.w("TunnelClient", msg)
                    com.rousecontext.tunnel.LogLevel.ERROR -> Log.e("TunnelClient", msg)
                }
            },
            stateMachineLog = { level, msg ->
                when (level) {
                    com.rousecontext.tunnel.LogLevel.DEBUG -> Log.d("ConnectionStateMachine", msg)
                    com.rousecontext.tunnel.LogLevel.INFO -> Log.i("ConnectionStateMachine", msg)
                    com.rousecontext.tunnel.LogLevel.WARN -> Log.w("ConnectionStateMachine", msg)
                    com.rousecontext.tunnel.LogLevel.ERROR -> Log.e("ConnectionStateMachine", msg)
                }
            },
            muxDemuxLog = { level, msg ->
                when (level) {
                    com.rousecontext.tunnel.LogLevel.DEBUG -> Log.d("MuxDemux", msg)
                    com.rousecontext.tunnel.LogLevel.INFO -> Log.i("MuxDemux", msg)
                    com.rousecontext.tunnel.LogLevel.WARN -> Log.w("MuxDemux", msg)
                    com.rousecontext.tunnel.LogLevel.ERROR -> Log.e("MuxDemux", msg)
                }
            }
        )
    }

    single {
        val pm = androidContext().getSystemService(PowerManager::class.java)
        val wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "rousecontext:tunnel"
        )
        WakelockManager(RealWakeLockHandle(wakeLock))
    }

    single<SpuriousWakeRecorder> {
        DataStoreSpuriousWakeRecorder(prefs = get())
    }

    single {
        val tunnelClient: TunnelClient = get()
        val recorder: SpuriousWakeRecorder = get()
        IdleTimeoutManager(
            timeoutMillis = IDLE_TIMEOUT_MS,
            onTimeout = { tunnelClient.disconnect() },
            recorder = recorder
        )
    }

    // --- ViewModels ---
    viewModel {
        val refresher: NotificationPermissionRefresher = get()
        MainDashboardViewModel(
            integrations = get(),
            stateStore = get(),
            tokenStore = get(),
            auditDao = get(),
            urlProvider = get(),
            tunnelClient = get(),
            certRenewalBanner = com.rousecontext.app.cert.certRenewalBannerFlow(get()),
            notificationsEnabled = notificationPermissionFlow(
                context = androidContext(),
                triggers = refresher.ticks
            ),
            spuriousWakesFlow = SettingsViewModel.spuriousWakeStatsFlow(get())
        )
    }
    viewModel { AddIntegrationViewModel(get(), get(), get()) }
    viewModel { IntegrationManageViewModel(get(), get(), get(), get(), get()) }
    viewModel { AuditHistoryViewModel(get(), get(), get(), get()) }
    viewModel {
        SettingsViewModel(
            notificationSettingsProvider = get(),
            themePreference = get(),
            relayApiClient = get(),
            certStore = get(),
            integrations = get(),
            securityCheckPreferences = get(),
            appStatePreferences = get(),
            spuriousWakesFlow = SettingsViewModel.spuriousWakeStatsFlow(get())
        )
    }
    viewModel {
        AuthorizationApprovalViewModel(
            get<McpSession>().authorizationCodeManager,
            androidContext().getSystemService(NotificationManager::class.java)
        )
    }
    viewModel { HealthConnectSetupViewModel(get(), get()) }
    viewModel { NotificationSetupViewModel(androidContext(), get(), get()) }
    viewModel { OutreachSetupViewModel(androidContext(), get(), get()) }
    viewModel { UsageSetupViewModel(androidContext(), get()) }
    viewModel {
        IntegrationSetupViewModel(
            stateStore = get(),
            certProvisioningFlow = get(),
            lazyWebSocketFactory = get(),
            registrationStatus = get(),
            relayApiClient = get(),
            certStore = get(),
            integrationIds = get<List<McpIntegration>>().map { it.id }
        )
    }
    viewModel { OnboardingViewModel(get(), get(), get(), get(named("appScope"))) }
    viewModel { NotificationPreferencesViewModel(get()) }
}
