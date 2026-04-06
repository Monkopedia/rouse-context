package com.rousecontext.app.registry

import android.app.AppOpsManager
import android.content.Context
import android.os.Process
import com.rousecontext.api.McpIntegration
import com.rousecontext.mcp.core.McpServerProvider
import com.rousecontext.usage.UsageMcpProvider

/**
 * [McpIntegration] for device usage statistics.
 *
 * Requires the PACKAGE_USAGE_STATS permission, which users must grant
 * via Settings > Apps > Special access > Usage access.
 */
class UsageIntegration(
    private val context: Context
) : McpIntegration {

    override val id = "usage"
    override val displayName = "Usage Stats"
    override val description = "Let AI see your app usage patterns and screen time"
    override val path = "/usage"
    override val onboardingRoute = "setup"
    override val settingsRoute = "settings"

    override val provider: McpServerProvider = UsageMcpProvider(context)

    override suspend fun isAvailable(): Boolean {
        val appOps = context.getSystemService(AppOpsManager::class.java)
        val mode = appOps.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }
}
