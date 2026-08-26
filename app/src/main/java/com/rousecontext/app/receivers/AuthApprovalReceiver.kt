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
 * Retrieves the [McpSession] via Koin and delegates to the authorization code
 * manager, falling back to the device code manager for RFC 8628 user codes.
 */
class AuthApprovalReceiver :
    BroadcastReceiver(),
    KoinComponent {

    private val mcpSession: McpSession by inject()

    override fun onReceive(context: Context, intent: Intent) {
        val displayCode = intent.getStringExtra(EXTRA_DISPLAY_CODE) ?: return
        val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, -1)

        when (intent.action) {
            // The notification carries either an auth-code display code or an
            // RFC 8628 user code -- they are indistinguishable by shape, so ask
            // the auth-code manager first and fall through to the device-code
            // manager when it doesn't own the code (#606).
            ACTION_APPROVE ->
                if (!mcpSession.authorizationCodeManager.approve(displayCode)) {
                    mcpSession.deviceCodeManager.approve(displayCode)
                }
            ACTION_DENY ->
                if (!mcpSession.authorizationCodeManager.deny(displayCode)) {
                    mcpSession.deviceCodeManager.deny(displayCode)
                }
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
