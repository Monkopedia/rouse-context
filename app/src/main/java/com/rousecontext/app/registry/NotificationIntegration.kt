package com.rousecontext.app.registry

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.service.notification.StatusBarNotification
import com.rousecontext.api.McpIntegration
import com.rousecontext.app.state.IntegrationSettingsStore
import com.rousecontext.app.state.LiveBooleanSetting
import com.rousecontext.integrations.notifications.NotificationCaptureService
import com.rousecontext.integrations.notifications.NotificationDao
import com.rousecontext.integrations.notifications.NotificationMcpProvider
import com.rousecontext.mcp.core.McpServerProvider
import com.rousecontext.notifications.FieldEncryptor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

/**
 * [McpIntegration] for device notifications.
 *
 * Checks whether the user has granted notification listener access and
 * delegates MCP tool/resource registration to [NotificationMcpProvider].
 *
 * ### Consent gating
 *
 * "Allow AI to act on notifications" ([IntegrationSettingsStore.KEY_ALLOW_ACTIONS])
 * gates `perform_notification_action` and `dismiss_notification`. The stored
 * value is resolved per tool call rather than captured here, so revoking
 * consent applies to the next call instead of the next app start, and so a
 * call racing process spawn cannot read a default the user never chose
 * (see [LiveBooleanSetting]).
 */
class NotificationIntegration(
    private val context: Context,
    dao: NotificationDao,
    settingsStore: IntegrationSettingsStore,
    appScope: CoroutineScope,
    fieldEncryptor: FieldEncryptor? = null
) : McpIntegration {

    override val id = "notifications"
    override val displayName = "Notifications"
    override val description = "Expose device notifications, both active and searchable history"
    override val path = "/notifications"
    override val onboardingRoute = "setup"
    override val settingsRoute = "settings"

    private val allowActionsSetting = LiveBooleanSetting(
        settingsStore = settingsStore,
        integrationId = id,
        key = IntegrationSettingsStore.KEY_ALLOW_ACTIONS,
        appScope = appScope
    )

    /** Live view of the user's "allow AI to act on notifications" consent. */
    val allowActions: StateFlow<Boolean> = allowActionsSetting.value

    /**
     * Whether the action and dismiss tools may run right now. Suspends until
     * the stored consent has been loaded at least once.
     */
    suspend fun isActionsAllowed(): Boolean = allowActionsSetting.current()

    override val provider: McpServerProvider = NotificationMcpProvider(
        dao = dao,
        activeNotificationSource = ::getActiveNotifications,
        actionPerformer = ::performAction,
        notificationDismisser = ::dismissNotification,
        fieldEncryptor = fieldEncryptor,
        allowActions = { isActionsAllowed() }
    )

    override suspend fun isAvailable(): Boolean = true

    /**
     * Whether notification listener access is currently granted.
     */
    fun isPermissionGranted(): Boolean {
        val flat = Settings.Secure.getString(
            context.contentResolver,
            "enabled_notification_listeners"
        ) ?: return false

        val component = ComponentName(context, NotificationCaptureService::class.java)
        return flat.contains(component.flattenToString())
    }

    private fun getActiveNotifications(): Array<StatusBarNotification> {
        // The NotificationListenerService maintains active notifications.
        // We access them via the service instance if it's running.
        // If the service isn't connected, return empty.
        return try {
            val service = NotificationCaptureService.instance
            service?.activeNotifications ?: emptyArray()
        } catch (_: Exception) {
            emptyArray()
        }
    }

    @Suppress("ReturnCount")
    private fun performAction(key: String, actionIndex: Int): Boolean {
        return try {
            val service = NotificationCaptureService.instance
                ?: return false
            val sbn = service.activeNotifications
                ?.find { it.key == key } ?: return false
            if (NotificationCaptureService.isOwnPackage(sbn.packageName)) {
                return false
            }
            val action = sbn.notification.actions
                ?.getOrNull(actionIndex) ?: return false
            action.actionIntent.send()
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun dismissNotification(key: String): Boolean {
        return try {
            val service = NotificationCaptureService.instance ?: return false
            service.cancelNotification(key)
            true
        } catch (_: Exception) {
            false
        }
    }
}
