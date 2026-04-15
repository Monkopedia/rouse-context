package com.rousecontext.app.registry

import android.app.NotificationManager
import android.content.Context
import android.os.Build
import com.rousecontext.api.LaunchRequestNotifierApi
import com.rousecontext.api.McpIntegration
import com.rousecontext.app.state.IntegrationSettingsStore
import com.rousecontext.mcp.core.McpServerProvider
import com.rousecontext.outreach.OutreachMcpProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * [McpIntegration] for Outreach actions (launch apps, open links, clipboard, notifications, DND).
 *
 * Basic tools are always available. DND tools require ACCESS_NOTIFICATION_POLICY permission,
 * checked at construction time and re-evaluated via [isAvailable].
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
     * Live view of the user's direct-launch opt-in. Read synchronously by
     * [OutreachMcpProvider]'s canLaunchDirectly predicate on every tool call.
     */
    private val _directLaunchEnabled = MutableStateFlow(false)
    val directLaunchEnabled: StateFlow<Boolean> = _directLaunchEnabled.asStateFlow()

    init {
        appScope.launch {
            settingsStore.observeBoolean(
                id,
                IntegrationSettingsStore.KEY_DIRECT_LAUNCH_ENABLED
            ).collect { _directLaunchEnabled.value = it }
        }
    }

    override val provider: McpServerProvider = OutreachMcpProvider(
        context = context,
        dndEnabled = isDndPermissionGranted(),
        canLaunchDirectly = {
            // Re-checked every tool-call. Pre-Android-14 has no BAL restriction,
            // so the PendingIntent path always works. On Android 14+ require both
            // the user opt-in AND the OS overlay permission.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                true
            } else {
                OutreachMcpProvider.defaultCanLaunchDirectly(context) &&
                    _directLaunchEnabled.value
            }
        },
        launchNotifier = launchNotifier
    )

    override suspend fun isAvailable(): Boolean = true

    private fun isDndPermissionGranted(): Boolean {
        val nm = context.getSystemService(NotificationManager::class.java)
        return nm.isNotificationPolicyAccessGranted
    }
}
