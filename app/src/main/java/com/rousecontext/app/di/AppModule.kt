package com.rousecontext.app.di

import com.rousecontext.api.IntegrationStateStore
import com.rousecontext.api.McpIntegration
import com.rousecontext.api.NotificationSettingsProvider
import com.rousecontext.app.BuildConfig
import com.rousecontext.app.MainActivity
import com.rousecontext.app.McpUrlProvider
import com.rousecontext.app.cert.FileCertificateStore
import com.rousecontext.app.cert.LazyWebSocketFactory
import com.rousecontext.app.health.RealHealthConnectRepository
import com.rousecontext.app.receivers.AuthApprovalReceiver
import com.rousecontext.app.registry.HealthConnectIntegration
import com.rousecontext.app.registry.IntegrationProviderRegistry
import com.rousecontext.app.registry.NotificationIntegration
import com.rousecontext.app.registry.OutreachIntegration
import com.rousecontext.app.registry.UsageIntegration
import com.rousecontext.app.session.McpSessionBridge
import com.rousecontext.app.state.DataStoreIntegrationStateStore
import com.rousecontext.app.state.DataStoreNotificationSettingsProvider
import com.rousecontext.app.state.DeviceRegistrationStatus
import com.rousecontext.app.state.IntegrationSettingsStore
import com.rousecontext.app.state.NotificationPermissionRefresher
import com.rousecontext.app.state.ThemePreference
import com.rousecontext.app.state.notificationPermissionFlow
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
import com.rousecontext.mcp.core.AuditListener
import com.rousecontext.mcp.core.McpSession
import com.rousecontext.mcp.core.ProviderRegistry
import com.rousecontext.mcp.core.TokenStore
import com.rousecontext.mcp.health.HealthConnectRepository
import com.rousecontext.notifications.AuthRequestNotifier
import com.rousecontext.notifications.SessionSummaryPoster
import com.rousecontext.notifications.audit.AuditDatabase
import com.rousecontext.notifications.audit.RoomAuditListener
import com.rousecontext.notifications.capture.FieldEncryptor
import com.rousecontext.notifications.capture.NotificationDatabase
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
import com.rousecontext.work.CertRenewalFlowRenewer
import com.rousecontext.work.CertRenewalWorker
import com.rousecontext.work.CertRenewer
import com.rousecontext.work.CtLogMonitorSource
import com.rousecontext.work.FcmTokenRegistrar
import com.rousecontext.work.FirebaseRenewalAuthProvider
import com.rousecontext.work.IdleTimeoutManager
import com.rousecontext.work.RealWakeLockHandle
import com.rousecontext.work.RenewalAuthProvider
import com.rousecontext.work.SecurityCheckSource
import com.rousecontext.work.SessionHandler
import com.rousecontext.work.SharedPreferencesSpuriousWakeRecorder
import com.rousecontext.work.SpuriousWakeRecorder
import com.rousecontext.work.StoredCertVerifierSource
import com.rousecontext.work.WakelockManager
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
    single { NotificationDatabase.create(androidContext()) }
    single { get<NotificationDatabase>().notificationDao() }

    // --- Field encryption ---
    single { FieldEncryptor(androidContext()) }

    // --- Notification permission refresher ---
    single { NotificationPermissionRefresher() }

    // --- Certificate store ---
    single { FileCertificateStore(androidContext()) } bind CertificateStore::class

    // --- URL provider ---
    single { McpUrlProvider(get(), BuildConfig.BASE_DOMAIN) }

    // --- Device registration status ---
    single {
        val certStore: CertificateStore = get()
        val alreadyRegistered = kotlinx.coroutines.runBlocking {
            certStore.getSubdomain() != null
        }
        DeviceRegistrationStatus(initiallyRegistered = alreadyRegistered)
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
    single<RenewalAuthProvider> { FirebaseRenewalAuthProvider() }

    // --- Token store ---
    singleOf(::RoomTokenStore) bind TokenStore::class

    // --- Integration state ---
    single<IntegrationStateStore> { DataStoreIntegrationStateStore(androidContext()) }

    // --- Integration settings (per-integration preferences) ---
    single { IntegrationSettingsStore(androidContext()) }

    // --- Theme preference ---
    single { ThemePreference(androidContext()) }

    // --- Notification settings ---
    single<NotificationSettingsProvider> { DataStoreNotificationSettingsProvider(androidContext()) }

    // --- Health Connect repository ---
    single<HealthConnectRepository> { RealHealthConnectRepository(androidContext()) }

    // --- Integrations ---
    single<McpIntegration>(named("health")) { HealthConnectIntegration(androidContext()) }
    single<McpIntegration>(named("outreach")) { OutreachIntegration(androidContext()) }
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
            stateStore = get()
        )
    }

    // --- Audit listener ---
    single<AuditListener> {
        RoomAuditListener(
            dao = get(),
            scope = get(named("appScope")),
            fieldEncryptor = get()
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
        SessionSummaryPoster(
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

    // --- MCP session ---
    // TODO: With per-integration hostnames, each integration needs its own McpSession.
    // For now, create a single session for the first enabled integration.
    single {
        val certStore: CertificateStore = get()
        val baseDomain = BuildConfig.BASE_DOMAIN
        val notifier: AuthRequestNotifier = get()
        val integrations: List<McpIntegration> = get()
        // Default to first integration id
        val defaultIntegration = integrations.firstOrNull()?.id ?: "health"
        // Build hostname from cert store for OAuth metadata URLs.
        // With single-session, use the first available integration secret.
        val hostname = kotlinx.coroutines.runBlocking {
            val subdomain = certStore.getSubdomain()
            val secret = certStore.getSecretForIntegration(defaultIntegration)
            if (subdomain != null && secret != null) {
                "$secret.$subdomain.$baseDomain"
            } else {
                "localhost"
            }
        }
        McpSession(
            registry = get(),
            tokenStore = get(),
            auditListener = get(),
            hostname = hostname,
            integration = defaultIntegration,
            securityAlertCheck = {
                val prefs = androidContext().getSharedPreferences(
                    com.rousecontext.work.SecurityCheckWorker.PREFS_NAME,
                    android.content.Context.MODE_PRIVATE
                )
                val self = prefs.getString(
                    com.rousecontext.work.SecurityCheckWorker.KEY_SELF_CERT_RESULT,
                    ""
                ) ?: ""
                val ct = prefs.getString(
                    com.rousecontext.work.SecurityCheckWorker.KEY_CT_LOG_RESULT,
                    ""
                ) ?: ""
                self == "alert" || ct == "alert"
            }
        ).also { session ->
            session.start(port = 0)
            session.authorizationCodeManager.onNewRequest = { displayCode, integration ->
                notifier.post(displayCode, integration)
            }
        }
    }

    // --- Session handler (TLS accept + bridge to MCP) ---
    single<SessionHandler> {
        McpSessionBridge(
            mcpSession = get(),
            certStore = get()
        )
    }

    // --- FCM token registration ---
    single { FcmTokenRegistrar() }

    // --- Tunnel & work ---
    single<String>(named("relayUrl")) {
        "${BuildConfig.RELAY_SCHEME}://${BuildConfig.RELAY_HOST}:${BuildConfig.RELAY_PORT}/ws"
    }

    single { LazyWebSocketFactory(androidContext()) }

    single<TunnelClient> {
        TunnelClientImpl(
            scope = get(named("appScope")),
            webSocketFactory = get<LazyWebSocketFactory>()
        )
    }

    single {
        val pm = androidContext().getSystemService(android.os.PowerManager::class.java)
        val wakeLock = pm.newWakeLock(
            android.os.PowerManager.PARTIAL_WAKE_LOCK,
            "rousecontext:tunnel"
        )
        WakelockManager(RealWakeLockHandle(wakeLock))
    }

    single<SpuriousWakeRecorder> {
        SharedPreferencesSpuriousWakeRecorder.create(androidContext())
    }

    single {
        val tunnelClient: TunnelClient = get()
        val recorder: SpuriousWakeRecorder = get()
        IdleTimeoutManager(
            timeoutMillis = IDLE_TIMEOUT_MS,
            batteryExempt = false,
            onTimeout = { tunnelClient.disconnect() },
            recorder = recorder
        )
    }

    // --- ViewModels ---
    viewModel {
        val certRenewalPrefs = androidContext().getSharedPreferences(
            com.rousecontext.work.CertRenewalWorker.PREFS_NAME,
            android.content.Context.MODE_PRIVATE
        )
        val spuriousWakePrefs = androidContext().getSharedPreferences(
            SharedPreferencesSpuriousWakeRecorder.PREFS_NAME,
            android.content.Context.MODE_PRIVATE
        )
        val refresher: NotificationPermissionRefresher = get()
        MainDashboardViewModel(
            integrations = get(),
            stateStore = get(),
            tokenStore = get(),
            auditDao = get(),
            urlProvider = get(),
            tunnelClient = get(),
            certRenewalBanner = com.rousecontext.app.cert.certRenewalBannerFlow(certRenewalPrefs),
            notificationsEnabled = notificationPermissionFlow(
                context = androidContext(),
                triggers = refresher.ticks
            ),
            spuriousWakesFlow = SettingsViewModel.spuriousWakeStatsFlow(spuriousWakePrefs)
        )
    }
    viewModel { AddIntegrationViewModel(get(), get(), get()) }
    viewModel { IntegrationManageViewModel(get(), get(), get(), get(), get()) }
    viewModel { AuditHistoryViewModel(get(), get()) }
    viewModel {
        val spuriousWakePrefs = androidContext().getSharedPreferences(
            SharedPreferencesSpuriousWakeRecorder.PREFS_NAME,
            android.content.Context.MODE_PRIVATE
        )
        SettingsViewModel(
            notificationSettingsProvider = get(),
            themePreference = get(),
            relayApiClient = get(),
            certStore = get(),
            integrations = get(),
            securityCheckPrefs = androidContext().getSharedPreferences(
                com.rousecontext.work.SecurityCheckWorker.PREFS_NAME,
                android.content.Context.MODE_PRIVATE
            ),
            settingsPrefs = androidContext().getSharedPreferences(
                com.rousecontext.app.RouseApplication.PREFS_NAME,
                android.content.Context.MODE_PRIVATE
            ),
            spuriousWakesFlow = SettingsViewModel.spuriousWakeStatsFlow(spuriousWakePrefs)
        )
    }
    viewModel {
        AuthorizationApprovalViewModel(
            get<McpSession>().authorizationCodeManager,
            androidContext().getSystemService(android.app.NotificationManager::class.java)
        )
    }
    viewModel { HealthConnectSetupViewModel(get(), get()) }
    viewModel { NotificationSetupViewModel(androidContext(), get(), get()) }
    viewModel { OutreachSetupViewModel(androidContext(), get(), get()) }
    viewModel { UsageSetupViewModel(androidContext(), get()) }
    viewModel { IntegrationSetupViewModel(get(), get(), get(), get()) }
    viewModel { OnboardingViewModel(get(), get(), get()) }
    viewModel { NotificationPreferencesViewModel(get()) }
}
