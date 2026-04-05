package com.rousecontext.mcp.health

import android.content.Context
import android.content.pm.PackageManager
import com.rousecontext.mcp.core.McpIntegration
import com.rousecontext.mcp.core.McpServerProvider

/**
 * MCP integration that exposes Health Connect data.
 *
 * Checks device availability of the Health Connect provider package
 * and delegates MCP tool/resource registration to [HealthConnectMcpServer].
 */
class HealthConnectIntegration(
    private val context: Context,
) : McpIntegration {

    override val id: String = "health"

    override val displayName: String = "Health Connect"

    override val description: String =
        "Share step count, heart rate, and sleep data with AI clients"

    override val path: String = "health"

    override val provider: McpServerProvider = HealthConnectMcpServer()

    override val onboardingRoute: String = "health/setup"

    override val settingsRoute: String = "health/settings"

    override suspend fun isAvailable(): Boolean {
        val packageManager = context.packageManager
        return try {
            packageManager.getPackageInfo(HEALTH_CONNECT_PACKAGE, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    internal companion object {
        const val HEALTH_CONNECT_PACKAGE = "com.google.android.apps.healthdata"
    }
}
