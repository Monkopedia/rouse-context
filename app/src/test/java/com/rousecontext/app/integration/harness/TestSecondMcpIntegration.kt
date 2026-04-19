package com.rousecontext.app.integration.harness

import com.rousecontext.api.McpIntegration
import com.rousecontext.mcp.core.McpServerProvider
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent

/**
 * Second lightweight [McpIntegration] used by multi-integration scenarios
 * (issues #274 / #275 / #276). Together with [TestEchoMcpIntegration] this
 * gives harness tests two distinct integration IDs to exercise enable /
 * disable / re-enable payload flips without pulling in any production
 * integration's setup machinery.
 *
 * The provider registers a single deterministic tool so the integration is
 * syntactically valid for the MCP session, but none of the flip scenarios
 * actually call it — they only care about the `/rotate-secret` payload.
 */
class TestSecondMcpIntegration : McpIntegration {
    override val id = ID
    override val displayName = "Test 2"
    override val description = "Harness-only second integration for enable/disable flip scenarios"
    override val path = "/test2"
    override val onboardingRoute = "setup"
    override val settingsRoute = "settings"
    override val provider: McpServerProvider = TestSecondProvider()
    override suspend fun isAvailable(): Boolean = true

    companion object {
        const val ID: String = "test2"
    }
}

private class TestSecondProvider : McpServerProvider {
    override val id = TestSecondMcpIntegration.ID
    override val displayName = "Test 2"

    override fun register(server: Server) {
        server.addTool(
            name = "pong",
            description = "Returns a fixed string; unused by flip scenarios"
        ) {
            CallToolResult(content = listOf(TextContent("pong")))
        }
    }
}
