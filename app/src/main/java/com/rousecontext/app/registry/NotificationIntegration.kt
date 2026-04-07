package com.rousecontext.app.registry

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.service.notification.StatusBarNotification
import com.rousecontext.api.McpIntegration
import com.rousecontext.mcp.core.McpServerProvider
import com.rousecontext.notifications.capture.FieldEncryptor
import com.rousecontext.notifications.capture.NotificationCaptureService
import com.rousecontext.notifications.capture.NotificationDao
import com.rousecontext.notifications.capture.NotificationMcpProvider

/**
 * [McpIntegration] for device notifications.
 *
 * Checks whether the user has granted notification listener access and
 * delegates MCP tool/resource registration to [NotificationMcpProvider].
 */
class NotificationIntegration(
    private val context: Context,
    dao: NotificationDao,
    fieldEncryptor: FieldEncryptor? = null
) : McpIntegration {

    override val id = "notifications"
    override val displayName = "Notifications"
    override val description = "Expose device notifications — active and searchable history"
    override val path = "/notifications"
    override val onboardingRoute = "setup"
    override val settingsRoute = "settings"

    override val provider: McpServerProvider = NotificationMcpProvider(
        dao = dao,
        activeNotificationSource = ::getActiveNotifications,
        actionPerformer = ::performAction,
        notificationDismisser = ::dismissNotification,
        fieldEncryptor = fieldEncryptor
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
