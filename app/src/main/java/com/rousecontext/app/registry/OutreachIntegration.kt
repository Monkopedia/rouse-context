package com.rousecontext.app.registry

import android.app.NotificationManager
import android.content.Context
import android.os.Build
import com.rousecontext.api.LaunchRequestNotifierApi
import com.rousecontext.api.McpIntegration
import com.rousecontext.app.state.IntegrationSettingsStore
import com.rousecontext.integrations.outreach.OutreachMcpProvider
import com.rousecontext.mcp.core.McpServerProvider
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/**
 * [McpIntegration] for Outreach actions (launch apps, open links, clipboard, notifications, DND).
 *
 * Basic tools are always available. DND tools require ACCESS_NOTIFICATION_POLICY permission,
 * checked at construction time and re-evaluated via [isAvailable].
 *
 * ### Cold-start readiness (issue #419 finding #2)
 *
 * The user's `direct-launch` opt-in is loaded asynchronously from a DataStore-backed
 * [IntegrationSettingsStore]. Until the first emission lands, [_directLaunchEnabled]
 * holds the `false` default — so a tool call that fired immediately after process
 * spawn would route through the notification fallback even when the user had
 * opted into direct launch. Tool callers go through [isDirectLaunchAllowed], which
 * suspends on [readyDeferred] until the first emission has been collected.
 */
class OutreachIntegration(
    private val context: Context,
    private val settingsStore: IntegrationSettingsStore,
    private val launchNotifier: LaunchRequestNotifierApi,
    appScope: CoroutineScope
) : McpIntegration {

    override val id = "outreach"
    override val displayName = "Outreach"
    override val description =
        "Let AI launch apps, open links, copy to clipboard, and send notifications"
    override val path = "/outreach"
    override val onboardingRoute = "setup"
    override val settingsRoute = "settings"

    /**
     * Live view of the user's direct-launch opt-in. Read by [isDirectLaunchAllowed]
     * after [awaitReady] has unblocked.
     */
    private val _directLaunchEnabled = MutableStateFlow(false)
    val directLaunchEnabled: StateFlow<Boolean> = _directLaunchEnabled.asStateFlow()

    private val readyDeferred = CompletableDeferred<Unit>()
    private val readyLatch = CountDownLatch(1)

    init {
        appScope.launch {
            settingsStore.observeBoolean(
                id,
                IntegrationSettingsStore.KEY_DIRECT_LAUNCH_ENABLED
            )
                .onEach { _directLaunchEnabled.value = it }
                .collect { signalReady() }
        }
    }

    private fun signalReady() {
        // Idempotent: subsequent emissions just no-op on the already-completed signals.
        readyDeferred.complete(Unit)
        if (readyLatch.count > 0) {
            readyLatch.countDown()
        }
    }

    /**
     * Suspends until the user's direct-launch opt-in has been loaded from disk
     * at least once. Subsequent calls return immediately. Mirrors the
     * [com.rousecontext.mcp.core.ProviderRegistry.awaitReady] shape.
     */
    suspend fun awaitReady() {
        readyDeferred.await()
    }

    /**
     * Thread-blocking variant of [awaitReady]. Symmetric with
     * [com.rousecontext.mcp.core.ProviderRegistry.awaitReadyBlocking];
     * not currently exercised in production but provided for parity.
     */
    fun awaitReadyBlocking(timeoutMs: Long): Boolean =
        readyLatch.await(timeoutMs, TimeUnit.MILLISECONDS)

    /**
     * Returns whether direct-activity launch is allowed right now. Suspends
     * until the opt-in has been loaded so the very first tool call after
     * process spawn cannot mis-route through the notification fallback.
     *
     * Pre-Android-14 has no Background Activity Launch restriction, so the
     * PendingIntent path always works. On Android 14+ require both the user
     * opt-in AND the OS overlay permission.
     */
    suspend fun isDirectLaunchAllowed(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            return true
        }
        awaitReady()
        return OutreachMcpProvider.defaultCanLaunchDirectly(context) &&
            _directLaunchEnabled.value
    }

    override val provider: McpServerProvider = OutreachMcpProvider(
        context = context,
        dndEnabled = isDndPermissionGranted(),
        canLaunchDirectly = { isDirectLaunchAllowed() },
        launchNotifier = launchNotifier
    )

    override suspend fun isAvailable(): Boolean = true

    private fun isDndPermissionGranted(): Boolean {
        val nm = context.getSystemService(NotificationManager::class.java)
        return nm.isNotificationPolicyAccessGranted
    }
}
