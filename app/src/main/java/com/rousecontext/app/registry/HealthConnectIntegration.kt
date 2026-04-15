package com.rousecontext.app.registry

import android.content.Context
import android.os.Build
import com.rousecontext.api.McpIntegration
import com.rousecontext.integrations.health.HealthConnectMcpServer
import com.rousecontext.integrations.health.RealHealthConnectRepository
import com.rousecontext.mcp.core.McpServerProvider

/**
 * [McpIntegration] for Health Connect.
 *
 * Always available in the integration picker. Permission checks happen
 * during the setup flow, not at availability time.
 *
 * Health Connect requires API 28+. On older devices [isAvailable] returns false.
 */
class HealthConnectIntegration(private val context: Context) : McpIntegration {

    override val id = "health"
    override val displayName = "Health Connect"
    override val description = "Expose step count, heart rate, sleep, HRV, and workout data"
    override val path = "/health"
    override val provider: McpServerProvider = HealthConnectMcpServer(
        repository = RealHealthConnectRepository(context)
    )
    override val onboardingRoute = "setup"
    override val settingsRoute = "settings"

    override suspend fun isAvailable(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
}
