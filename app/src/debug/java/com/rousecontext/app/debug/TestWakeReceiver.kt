package com.rousecontext.app.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.rousecontext.work.TunnelForegroundService

/**
 * Debug-only broadcast receiver that starts [TunnelForegroundService] without
 * requiring the C2DM sender permission. This allows ADB broadcasts to trigger
 * the wake flow during device integration tests.
 *
 * Only included in debug builds.
 */
class TestWakeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val type = intent.getStringExtra("type")
        Log.d(TAG, "TestWakeReceiver received: type=$type")

        when (type) {
            "wake" -> {
                val serviceIntent = Intent(context, TunnelForegroundService::class.java)
                context.startForegroundService(serviceIntent)
                Log.i(TAG, "Started TunnelForegroundService via test wake broadcast")
            }
            "approve" -> {
                val userCode = intent.getStringExtra("user_code")
                if (userCode != null) {
                    val session = org.koin.java.KoinJavaComponent.getKoin()
                        .get<com.rousecontext.mcp.core.McpSession>()
                    session.deviceCodeManager.approve(userCode)
                    Log.i(TAG, "Approved device code: $userCode")
                } else {
                    Log.w(TAG, "approve type requires user_code extra")
                }
            }
            "approve_auth" -> {
                val displayCode = intent.getStringExtra("display_code")
                if (displayCode != null) {
                    val session = org.koin.java.KoinJavaComponent.getKoin()
                        .get<com.rousecontext.mcp.core.McpSession>()
                    val approved = session.authorizationCodeManager.approve(displayCode)
                    if (approved) {
                        Log.i(TAG, "Approved auth code request: $displayCode")
                    } else {
                        Log.w(TAG, "No pending auth request for code: $displayCode")
                    }
                } else {
                    Log.w(TAG, "approve_auth type requires display_code extra")
                }
            }
        }
    }

    companion object {
        private const val TAG = "TestWakeReceiver"
    }
}
