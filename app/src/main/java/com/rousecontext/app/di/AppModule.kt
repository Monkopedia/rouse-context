package com.rousecontext.app.di

import com.rousecontext.api.IntegrationStateStore
import com.rousecontext.api.McpIntegration
import com.rousecontext.api.NotificationSettingsProvider
import com.rousecontext.app.cert.FileCertificateStore
import com.rousecontext.app.registry.HealthConnectIntegration
import com.rousecontext.app.registry.IntegrationProviderRegistry
import com.rousecontext.app.state.DataStoreIntegrationStateStore
import com.rousecontext.app.state.DataStoreNotificationSettingsProvider
import com.rousecontext.app.token.RoomTokenStore
import com.rousecontext.app.token.TokenDatabase
import com.rousecontext.mcp.core.AuditListener
import com.rousecontext.mcp.core.ProviderRegistry
import com.rousecontext.mcp.core.TokenStore
import com.rousecontext.notifications.audit.AuditDatabase
import com.rousecontext.notifications.audit.RoomAuditListener
import com.rousecontext.tunnel.CertificateStore
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.singleOf
import org.koin.core.qualifier.named
import org.koin.dsl.bind
import org.koin.dsl.module

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
}
