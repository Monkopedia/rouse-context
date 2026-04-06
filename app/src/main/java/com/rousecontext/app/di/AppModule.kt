package com.rousecontext.app.di

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.rousecontext.api.IntegrationStateStore
import com.rousecontext.api.McpIntegration
import com.rousecontext.api.NotificationSettingsProvider
import com.rousecontext.app.BuildConfig
import com.rousecontext.app.MainActivity
import com.rousecontext.app.cert.FileCertificateStore
import com.rousecontext.app.cert.MtlsWebSocketFactory
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
import com.rousecontext.app.token.RoomTokenStore
import com.rousecontext.app.token.TokenDatabase
import com.rousecontext.app.ui.viewmodels.AddClientViewModel
import com.rousecontext.app.ui.viewmodels.AddIntegrationViewModel
import com.rousecontext.app.ui.viewmodels.AuditHistoryViewModel
import com.rousecontext.app.ui.viewmodels.AuthorizationApprovalViewModel
import com.rousecontext.app.ui.viewmodels.DeviceCodeApprovalViewModel
import com.rousecontext.app.ui.viewmodels.HealthConnectSetupViewModel
import com.rousecontext.app.ui.viewmodels.IntegrationManageViewModel
import com.rousecontext.app.ui.viewmodels.IntegrationSetupViewModel
import com.rousecontext.app.ui.viewmodels.MainDashboardViewModel
import com.rousecontext.app.ui.viewmodels.OnboardingViewModel
import com.rousecontext.app.ui.viewmodels.SettingsViewModel
import com.rousecontext.mcp.core.AuditListener
import com.rousecontext.mcp.core.McpSession
import com.rousecontext.mcp.core.ProviderRegistry
import com.rousecontext.mcp.core.TokenStore
import com.rousecontext.mcp.health.HealthConnectRepository
import com.rousecontext.notifications.NotificationChannels
import com.rousecontext.notifications.audit.AuditDatabase
import com.rousecontext.notifications.audit.RoomAuditListener
import com.rousecontext.notifications.capture.NotificationDatabase
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

    // --- Certificate store ---
    single { FileCertificateStore(androidContext()) } bind CertificateStore::class

    // --- Onboarding ---
    single { CsrGenerator() }
    single {
        val httpScheme = if (BuildConfig.RELAY_SCHEME == "wss") "https" else "http"
        RelayApiClient("$httpScheme://${BuildConfig.RELAY_HOST}")
    }
    single { OnboardingFlow(get(), get(), get()) }

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
    single<McpIntegration>(named("outreach")) { OutreachIntegration(androidContext()) }
    single<McpIntegration>(named("notifications")) {
        NotificationIntegration(androidContext(), get())
    }
    single<McpIntegration>(named("usage")) { UsageIntegration(androidContext()) }

    single<List<McpIntegration>> {
        buildList {
            add(get(named("health")))
            add(get(named("outreach")))
            add(get(named("notifications")))
            add(get(named("usage")))
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
            scope = get(named("appScope"))
        )
    }

    // --- MCP session ---
    single {
        val subdomainFile = java.io.File(androidContext().filesDir, "rouse_subdomain.txt")
        val subdomain = if (subdomainFile.exists()) {
            subdomainFile.readText().trim()
        } else {
            null
        }
        val baseDomain = BuildConfig.RELAY_HOST.removePrefix("relay.")
        val hostname = subdomain?.let { "$it.$baseDomain" } ?: "localhost"
        McpSession(
            registry = get(),
            tokenStore = get(),
            auditListener = get(),
            hostname = hostname
        ).also { session ->
            session.start(port = 0)
            session.authorizationCodeManager.onNewRequest = { displayCode, integration ->
                postAuthRequestNotification(androidContext(), displayCode, integration)
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

    single<TunnelClient> {
        TunnelClientImpl(
            scope = get(named("appScope")),
            webSocketFactory = MtlsWebSocketFactory.create(androidContext())
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
            get(),
            get(),
            get(),
            get(),
            get(),
            get<McpSession>().authorizationCodeManager
        )
    }
    viewModel { AddClientViewModel(get(), get(), get(), get()) }
    viewModel { AddIntegrationViewModel(get(), get(), get()) }
    viewModel { IntegrationManageViewModel(get(), get(), get(), get()) }
    viewModel { AuditHistoryViewModel(get()) }
    viewModel { SettingsViewModel(get()) }
    viewModel { DeviceCodeApprovalViewModel(get()) }
    viewModel { AuthorizationApprovalViewModel(get<McpSession>().authorizationCodeManager) }
    viewModel { HealthConnectSetupViewModel(get()) }
    viewModel { IntegrationSetupViewModel(get()) }
    viewModel { OnboardingViewModel(get(), get()) }
}

/** Notification ID offset for auth request notifications. */
private const val AUTH_NOTIFICATION_BASE_ID = 5000

private var authNotificationCounter = 0

/**
 * Posts a high-priority notification for a new authorization request.
 * Includes Approve and Deny actions that are handled by [AuthApprovalReceiver].
 */
private fun postAuthRequestNotification(
    context: Context,
    displayCode: String,
    integration: String
) {
    val notificationId = AUTH_NOTIFICATION_BASE_ID + authNotificationCounter++

    val contentIntent = PendingIntent.getActivity(
        context,
        notificationId,
        Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    val approveIntent = PendingIntent.getBroadcast(
        context,
        notificationId * 2,
        Intent(context, AuthApprovalReceiver::class.java).apply {
            action = AuthApprovalReceiver.ACTION_APPROVE
            putExtra(AuthApprovalReceiver.EXTRA_DISPLAY_CODE, displayCode)
            putExtra(AuthApprovalReceiver.EXTRA_NOTIFICATION_ID, notificationId)
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    val denyIntent = PendingIntent.getBroadcast(
        context,
        notificationId * 2 + 1,
        Intent(context, AuthApprovalReceiver::class.java).apply {
            action = AuthApprovalReceiver.ACTION_DENY
            putExtra(AuthApprovalReceiver.EXTRA_DISPLAY_CODE, displayCode)
            putExtra(AuthApprovalReceiver.EXTRA_NOTIFICATION_ID, notificationId)
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    val notification = NotificationCompat.Builder(
        context,
        NotificationChannels.AUTH_REQUEST_CHANNEL_ID
    )
        .setSmallIcon(android.R.drawable.ic_dialog_alert)
        .setContentTitle("Approval Required")
        .setContentText("Code: $displayCode — Tap to approve or deny")
        .setSubText(integration)
        .setPriority(NotificationCompat.PRIORITY_HIGH)
        .setAutoCancel(true)
        .setContentIntent(contentIntent)
        .addAction(0, "Approve", approveIntent)
        .addAction(0, "Deny", denyIntent)
        .build()

    val manager = context.getSystemService(NotificationManager::class.java)
    manager.notify(notificationId, notification)
}
