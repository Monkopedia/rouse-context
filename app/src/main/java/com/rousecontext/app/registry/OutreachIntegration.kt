package com.rousecontext.app.registry

import android.app.NotificationManager
import android.content.Context
import com.rousecontext.api.McpIntegration
import com.rousecontext.mcp.core.McpServerProvider
import com.rousecontext.outreach.OutreachMcpProvider

/**
 * [McpIntegration] for Outreach actions (launch apps, open links, clipboard, notifications, DND).
 *
 * Basic tools are always available. DND tools require ACCESS_NOTIFICATION_POLICY permission,
 * checked at construction time and re-evaluated via [isAvailable].
 */
class OutreachIntegration(
    private val context: Context
) : McpIntegration {

    override val id = "outreach"
    override val displayName = "Outreach"
    override val description =
        "Let AI launch apps, open links, copy to clipboard, and send notifications"
    override val path = "/outreach"
    override val onboardingRoute = "setup"
    override val settingsRoute = "settings"

    override val provider: McpServerProvider = OutreachMcpProvider(
        context = context,
        dndEnabled = isDndPermissionGranted()
    )

    override suspend fun isAvailable(): Boolean = true

    private fun isDndPermissionGranted(): Boolean {
        val nm = context.getSystemService(NotificationManager::class.java)
        return nm.isNotificationPolicyAccessGranted
    }
}
