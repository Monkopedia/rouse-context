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
import com.rousecontext.app.state.ThemePreference
import com.rousecontext.app.token.RoomTokenStore
import com.rousecontext.app.token.TokenDatabase
import com.rousecontext.app.ui.viewmodels.AddIntegrationViewModel
import com.rousecontext.app.ui.viewmodels.AuditHistoryViewModel
import com.rousecontext.app.ui.viewmodels.AuthorizationApprovalViewModel
import com.rousecontext.app.ui.viewmodels.DeviceCodeApprovalViewModel
import com.rousecontext.app.ui.viewmodels.HealthConnectSetupViewModel
import com.rousecontext.app.ui.viewmodels.IntegrationManageViewModel
import com.rousecontext.app.ui.viewmodels.IntegrationSetupViewModel
import com.rousecontext.app.ui.viewmodels.MainDashboardViewModel
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
import com.rousecontext.notifications.audit.AuditDatabase
import com.rousecontext.notifications.audit.RoomAuditListener
import com.rousecontext.notifications.capture.FieldEncryptor
import com.rousecontext.notifications.capture.NotificationDatabase
import com.rousecontext.tunnel.CertProvisioningFlow
import com.rousecontext.tunnel.CertificateStore
import com.rousecontext.tunnel.CsrGenerator
import com.rousecontext.tunnel.OnboardingFlow
import com.rousecontext.tunnel.RelayApiClient
import com.rousecontext.tunnel.TunnelClient
import com.rousecontext.tunnel.TunnelClientImpl
import com.rousecontext.work.FcmTokenRegistrar
import com.rousecontext.work.IdleTimeoutManager
import com.rousecontext.work.RealWakeLockHandle
import com.rousecontext.work.SessionHandler
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

    // --- Certificate store ---
    single { FileCertificateStore(androidContext()) } bind CertificateStore::class

    // --- URL provider ---
    single { McpUrlProvider(get()) }

    // --- Device registration status ---
    single { DeviceRegistrationStatus() }

    // --- Onboarding ---
    single { CsrGenerator() }
    single {
        val httpScheme = if (BuildConfig.RELAY_SCHEME == "wss") "https" else "http"
        RelayApiClient("$httpScheme://${BuildConfig.RELAY_HOST}")
    }
    single { OnboardingFlow(get<RelayApiClient>(), get<CertificateStore>()) }
    single { CertProvisioningFlow(get(), get(), get()) }

    // --- Token store ---
    singleOf(::RoomTokenStore) bind TokenStore::class

    // --- Integration state ---
    single<IntegrationStateStore> { DataStoreIntegrationStateStore(androidContext()) }

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

    // --- MCP session ---
    // TODO: With per-integration hostnames, each integration needs its own McpSession.
    // For now, create a single session for the first enabled integration.
    single {
        val certStore: CertificateStore = get()
        val baseDomain = BuildConfig.RELAY_HOST.removePrefix("relay.")
        val notifier: AuthRequestNotifier = get()
        val integrations: List<McpIntegration> = get()
        // Default to first integration id
        val defaultIntegration = integrations.firstOrNull()?.id ?: "health"
        McpSession(
            registry = get(),
            tokenStore = get(),
            auditListener = get(),
            hostname = "localhost",
            integration = defaultIntegration
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
    single { FcmTokenRegistrar(get()) }

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

    single {
        val tunnelClient: TunnelClient = get()
        IdleTimeoutManager(
            timeoutMillis = IDLE_TIMEOUT_MS,
            batteryExempt = false,
            onTimeout = { tunnelClient.disconnect() }
        )
    }

    // --- ViewModels ---
    viewModel {
        MainDashboardViewModel(
            integrations = get(),
            stateStore = get(),
            tokenStore = get(),
            auditDao = get(),
            urlProvider = get()
        )
    }
    viewModel { AddIntegrationViewModel(get(), get(), get()) }
    viewModel { IntegrationManageViewModel(get(), get(), get(), get(), get()) }
    viewModel { AuditHistoryViewModel(get(), get()) }
    viewModel { SettingsViewModel(get(), get(), get(), get()) }
    viewModel { DeviceCodeApprovalViewModel(get()) }
    viewModel {
        AuthorizationApprovalViewModel(
            get<McpSession>().authorizationCodeManager,
            androidContext().getSystemService(android.app.NotificationManager::class.java)
        )
    }
    viewModel { HealthConnectSetupViewModel(get()) }
    viewModel { NotificationSetupViewModel(androidContext(), get()) }
    viewModel { OutreachSetupViewModel(androidContext(), get()) }
    viewModel { UsageSetupViewModel(androidContext(), get()) }
    viewModel { IntegrationSetupViewModel(get(), get(), get(), get()) }
    viewModel { OnboardingViewModel(get(), get(), get()) }
}
