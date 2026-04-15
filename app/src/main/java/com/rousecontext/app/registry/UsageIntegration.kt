package com.rousecontext.app.registry

import android.content.Context
import com.rousecontext.api.McpIntegration
import com.rousecontext.integrations.usage.UsageMcpProvider
import com.rousecontext.mcp.core.McpServerProvider

/**
 * [McpIntegration] for device usage statistics.
 *
 * Always available in the integration picker. The PACKAGE_USAGE_STATS
 * permission is requested during the setup flow, not at availability time.
 */
class UsageIntegration(private val context: Context) : McpIntegration {

    override val id = "usage"
    override val displayName = "Usage Stats"
    override val description = "Let AI see your app usage patterns and screen time"
    override val path = "/usage"
    override val onboardingRoute = "setup"
    override val settingsRoute = "settings"

    override val provider: McpServerProvider = UsageMcpProvider(context)

    override suspend fun isAvailable(): Boolean = true
}
