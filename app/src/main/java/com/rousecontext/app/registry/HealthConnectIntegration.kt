package com.rousecontext.app.registry

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import com.rousecontext.api.McpIntegration
import com.rousecontext.mcp.core.McpServerProvider
import com.rousecontext.mcp.health.HealthConnectMcpServer

/**
 * [McpIntegration] for Health Connect.
 *
 * Checks device availability via the Health Connect SDK and delegates
 * MCP tool/resource registration to [HealthConnectMcpServer].
 */
class HealthConnectIntegration(
    private val context: Context
) : McpIntegration {

    override val id = "health"
    override val displayName = "Health Connect"
    override val description = "Expose step count, heart rate, sleep, HRV, and workout data"
    override val path = "/health"
    override val provider: McpServerProvider = HealthConnectMcpServer()
    override val onboardingRoute = "setup"
    override val settingsRoute = "settings"

    override suspend fun isAvailable(): Boolean {
        val status = HealthConnectClient.getSdkStatus(context)
        return status == HealthConnectClient.SDK_AVAILABLE
    }
}
