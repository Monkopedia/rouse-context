package com.rousecontext.app.testing

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.rousecontext.api.IntegrationStateStore
import com.rousecontext.mcp.core.McpSession
import kotlinx.coroutines.runBlocking
import org.koin.java.KoinJavaComponent.getKoin

/**
 * Debug-only broadcast receiver for host-side e2e test automation.
 *
 * Handles two actions:
 * - `ENABLE_INTEGRATION` — enables an integration by ID via [IntegrationStateStore].
 * - `APPROVE_AUTH` — approves a pending OAuth authorization request by display code.
 *
 * Protected by `android.permission.SHELL` so only `adb shell` can invoke it.
 *
 * Usage:
 * ```
 * adb shell am broadcast \
 *   -a com.rousecontext.debug.ENABLE_INTEGRATION \
 *   --es id "health_connect" \
 *   -n com.rousecontext.debug/com.rousecontext.app.testing.TestCommandReceiver
 *
 * adb shell am broadcast \
 *   -a com.rousecontext.debug.APPROVE_AUTH \
 *   --es code "AB3X-9K2F" \
 *   -n com.rousecontext.debug/com.rousecontext.app.testing.TestCommandReceiver
 * ```
 */
class TestCommandReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        // Only exists in debug builds — no additional permission check needed.
        when (intent.action) {
            ACTION_ENABLE_INTEGRATION -> handleEnableIntegration(intent)
            ACTION_APPROVE_AUTH -> handleApproveAuth(intent)
            else -> Log.w(TAG, "Unknown action: ${intent.action}")
        }
    }

    private fun handleEnableIntegration(intent: Intent) {
        val id = intent.getStringExtra(EXTRA_ID)
        if (id == null) {
            Log.w(TAG, "ENABLE_INTEGRATION requires 'id' extra")
            return
        }

        val store = getKoin().get<IntegrationStateStore>()
        runBlocking { store.setUserEnabled(id, true) }
        Log.i(TAG, "Enabled integration: $id")
    }

    private fun handleApproveAuth(intent: Intent) {
        val displayCode = intent.getStringExtra(EXTRA_CODE)
        if (displayCode == null) {
            Log.w(TAG, "APPROVE_AUTH requires 'code' extra")
            return
        }

        val session = getKoin().get<McpSession>()
        val approved = session.authorizationCodeManager.approve(displayCode)
        if (approved) {
            Log.i(TAG, "Approved auth request: $displayCode")
        } else {
            Log.w(TAG, "No pending auth request for code: $displayCode")
        }
    }

    companion object {
        private const val TAG = "TestCommandReceiver"
        private const val ACTION_ENABLE_INTEGRATION =
            "com.rousecontext.debug.ENABLE_INTEGRATION"
        private const val ACTION_APPROVE_AUTH =
            "com.rousecontext.debug.APPROVE_AUTH"
        private const val EXTRA_ID = "id"
        private const val EXTRA_CODE = "code"
    }
}
