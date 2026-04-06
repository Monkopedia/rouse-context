package com.rousecontext.app.debug

import com.rousecontext.api.McpIntegration
import com.rousecontext.mcp.core.McpServerProvider

/**
 * Debug-only [McpIntegration] that exposes simple test tools (echo, get_time, device_info)
 * for verifying end-to-end MCP connectivity.
 *
 * Auto-enabled in debug builds with no setup flow required.
 */
class TestMcpIntegration : McpIntegration {

    override val id = "test"
    override val displayName = "Test Tools"
    override val description = "Simple tools for verifying end-to-end MCP connectivity"
    override val path = "/test"
    override val provider: McpServerProvider = TestMcpServer()
    override val onboardingRoute = "setup"
    override val settingsRoute = "settings"

    override suspend fun isAvailable(): Boolean = true
}
