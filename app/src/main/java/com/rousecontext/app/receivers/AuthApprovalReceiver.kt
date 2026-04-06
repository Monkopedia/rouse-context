package com.rousecontext.app.receivers

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.rousecontext.mcp.core.McpSession
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * Handles Approve/Deny actions from authorization request notifications.
 * Retrieves the [McpSession] via Koin and delegates to the authorization code manager.
 */
class AuthApprovalReceiver : BroadcastReceiver(), KoinComponent {

    private val mcpSession: McpSession by inject()

    override fun onReceive(context: Context, intent: Intent) {
        val displayCode = intent.getStringExtra(EXTRA_DISPLAY_CODE) ?: return
        val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, -1)

        when (intent.action) {
            ACTION_APPROVE -> mcpSession.authorizationCodeManager.approve(displayCode)
            ACTION_DENY -> mcpSession.authorizationCodeManager.deny(displayCode)
        }

        // Dismiss the notification
        if (notificationId >= 0) {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.cancel(notificationId)
        }
    }

    companion object {
        const val ACTION_APPROVE = "com.rousecontext.ACTION_AUTH_APPROVE"
        const val ACTION_DENY = "com.rousecontext.ACTION_AUTH_DENY"
        const val EXTRA_DISPLAY_CODE = "display_code"
        const val EXTRA_NOTIFICATION_ID = "notification_id"
    }
}
