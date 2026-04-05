package com.rousecontext.app.di

import com.rousecontext.api.IntegrationStateStore
import com.rousecontext.api.McpIntegration
import com.rousecontext.api.NotificationSettingsProvider
import com.rousecontext.app.BuildConfig
import com.rousecontext.app.cert.FileCertificateStore
import com.rousecontext.app.health.RealHealthConnectRepository
import com.rousecontext.app.registry.HealthConnectIntegration
import com.rousecontext.app.registry.IntegrationProviderRegistry
import com.rousecontext.app.state.DataStoreIntegrationStateStore
import com.rousecontext.app.state.DataStoreNotificationSettingsProvider
import com.rousecontext.app.token.RoomTokenStore
import com.rousecontext.app.token.TokenDatabase
import com.rousecontext.app.ui.viewmodels.AddIntegrationViewModel
import com.rousecontext.app.ui.viewmodels.AuditHistoryViewModel
import com.rousecontext.app.ui.viewmodels.DeviceCodeApprovalViewModel
import com.rousecontext.app.ui.viewmodels.IntegrationManageViewModel
import com.rousecontext.app.ui.viewmodels.IntegrationSetupViewModel
import com.rousecontext.app.ui.viewmodels.MainDashboardViewModel
import com.rousecontext.app.ui.viewmodels.SettingsViewModel
import com.rousecontext.mcp.core.AuditListener
import com.rousecontext.mcp.core.ProviderRegistry
import com.rousecontext.mcp.core.TokenStore
import com.rousecontext.mcp.health.HealthConnectRepository
import com.rousecontext.notifications.audit.AuditDatabase
import com.rousecontext.notifications.audit.RoomAuditListener
import com.rousecontext.tunnel.CertificateStore
import com.rousecontext.tunnel.TunnelClient
import com.rousecontext.tunnel.TunnelClientImpl
import com.rousecontext.work.IdleTimeoutManager
import com.rousecontext.work.RealWakeLockHandle
import com.rousecontext.work.WakelockManager
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.singleOf
import org.koin.core.module.dsl.viewModel
import org.koin.core.qualifier.named
import org.koin.dsl.bind
import org.koin.dsl.module

/** Idle timeout before disconnecting the tunnel (5 minutes). */
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

    // --- Certificate store ---
    single { FileCertificateStore(androidContext()) } bind CertificateStore::class

    // --- Token store ---
    singleOf(::RoomTokenStore) bind TokenStore::class

    // --- Integration state ---
    single<IntegrationStateStore> { DataStoreIntegrationStateStore(androidContext()) }

    // --- Notification settings ---
    single<NotificationSettingsProvider> { DataStoreNotificationSettingsProvider(androidContext()) }

    // --- Health Connect repository ---
    single<HealthConnectRepository> { RealHealthConnectRepository(androidContext()) }

    // --- Integrations ---
    single<McpIntegration>(named("health")) { HealthConnectIntegration(androidContext()) }

    single<List<McpIntegration>> {
        listOf(get(named("health")))
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
            scope = get(named("appScope"))
        )
    }

    // --- Tunnel & work ---
    single<String>(named("relayUrl")) {
        "wss://${BuildConfig.RELAY_HOST}:${BuildConfig.RELAY_PORT}/ws"
    }

    single<TunnelClient> {
        TunnelClientImpl(scope = get(named("appScope")))
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
    viewModel { MainDashboardViewModel(get(), get(), get(), get()) }
    viewModel { AddIntegrationViewModel(get(), get(), get()) }
    viewModel { IntegrationManageViewModel(get(), get(), get(), get()) }
    viewModel { AuditHistoryViewModel(get()) }
    viewModel { SettingsViewModel(get()) }
    viewModel { DeviceCodeApprovalViewModel(get()) }
    viewModel { IntegrationSetupViewModel(get()) }
}
