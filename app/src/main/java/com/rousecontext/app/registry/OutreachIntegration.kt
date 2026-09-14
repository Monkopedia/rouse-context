package com.rousecontext.app.registry

import android.app.NotificationManager
import android.content.Context
import android.os.Build
import com.rousecontext.api.LaunchRequestNotifierApi
import com.rousecontext.api.McpIntegration
import com.rousecontext.app.state.IntegrationSettingsStore
import com.rousecontext.app.state.LiveBooleanSetting
import com.rousecontext.integrations.outreach.OutreachMcpProvider
import com.rousecontext.mcp.core.McpServerProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

/**
 * [McpIntegration] for Outreach actions (launch apps, open links, clipboard, notifications, DND).
 *
 * ### Consent gating
 *
 * Two Outreach capabilities are behind a user control, and both are resolved
 * per tool call rather than captured when this object is built:
 *
 * - **Direct launch** needs the OS overlay permission *and* the user's
 *   `direct_launch_enabled` opt-in ([isDirectLaunchAllowed]).
 * - **Do Not Disturb** needs `ACCESS_NOTIFICATION_POLICY` *and* the user's
 *   `dnd_toggled` opt-in ([isDndAllowed]).
 *
 * Passing either as a plain value would freeze it at DI time: consent revoked
 * later in the process lifetime — or a permission revoked from system settings
 * — would not reach the tools until the app restarted.
 *
 * ### Cold-start readiness (issue #419 finding #2)
 *
 * Both opt-ins load asynchronously from a DataStore-backed
 * [IntegrationSettingsStore]. Until the first emission lands the in-memory
 * value is the `false` default, so a tool call that fired immediately after
 * process spawn would read a value the user never chose. [LiveBooleanSetting]
 * suspends on its readiness gate before answering.
 */
class OutreachIntegration(
    private val context: Context,
    settingsStore: IntegrationSettingsStore,
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

    private val directLaunchSetting = LiveBooleanSetting(
        settingsStore = settingsStore,
        integrationId = id,
        key = IntegrationSettingsStore.KEY_DIRECT_LAUNCH_ENABLED,
        appScope = appScope
    )

    private val dndSetting = LiveBooleanSetting(
        settingsStore = settingsStore,
        integrationId = id,
        key = IntegrationSettingsStore.KEY_DND_TOGGLED,
        appScope = appScope
    )

    /**
     * Live view of the user's direct-launch opt-in. Read by [isDirectLaunchAllowed]
     * after [awaitReady] has unblocked.
     */
    val directLaunchEnabled: StateFlow<Boolean> = directLaunchSetting.value

    /** Live view of the user's Do Not Disturb opt-in. */
    val dndToggled: StateFlow<Boolean> = dndSetting.value

    /**
     * Suspends until the user's direct-launch opt-in has been loaded from disk
     * at least once. Subsequent calls return immediately. Mirrors the
     * [com.rousecontext.mcp.core.ProviderRegistry.awaitReady] shape.
     */
    suspend fun awaitReady() {
        directLaunchSetting.awaitReady()
    }

    /**
     * Thread-blocking variant of [awaitReady]. Symmetric with
     * [com.rousecontext.mcp.core.ProviderRegistry.awaitReadyBlocking];
     * not currently exercised in production but provided for parity.
     */
    fun awaitReadyBlocking(timeoutMs: Long): Boolean =
        directLaunchSetting.awaitReadyBlocking(timeoutMs)

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
        return OutreachMcpProvider.defaultCanLaunchDirectly(context) &&
            directLaunchSetting.current()
    }

    /**
     * Returns whether the DND tools may run right now: the OS grant is
     * re-checked on every call, and the user's own toggle must also be on.
     * Granting `ACCESS_NOTIFICATION_POLICY` once is not consent to let an AI
     * client silence the phone.
     */
    suspend fun isDndAllowed(): Boolean = isDndPermissionGranted() && dndSetting.current()

    override val provider: McpServerProvider = OutreachMcpProvider(
        context = context,
        dndEnabled = { isDndAllowed() },
        canLaunchDirectly = { isDirectLaunchAllowed() },
        launchNotifier = launchNotifier
    )

    override suspend fun isAvailable(): Boolean = true

    private fun isDndPermissionGranted(): Boolean {
        val nm = context.getSystemService(NotificationManager::class.java)
        return nm.isNotificationPolicyAccessGranted
    }
}
