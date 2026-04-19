package com.rousecontext.app.integration.harness

import com.rousecontext.api.McpIntegration
import com.rousecontext.mcp.core.McpServerProvider
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Minimal [McpIntegration] exposing a couple of deterministic tools
 * (`echo` and `get_time`) for batch B integration scenarios (issue #252).
 *
 * Scenarios use this integration as the "test" one they connect through
 * so their assertions don't depend on any production integration's
 * behaviour. The production debug-only `TestMcpIntegration` lives in the
 * `debug` source set which `:app:test` cannot see; this is a replica
 * scoped to the test harness.
 */
class TestEchoMcpIntegration : McpIntegration {
    override val id = ID
    override val displayName = "Test"
    override val description = "Harness-only echo/get_time tools for integration scenarios"
    override val path = "/test"
    override val onboardingRoute = "setup"
    override val settingsRoute = "settings"
    override val provider: McpServerProvider = TestEchoProvider()
    override suspend fun isAvailable(): Boolean = true

    companion object {
        const val ID: String = "test"
    }
}

private class TestEchoProvider : McpServerProvider {
    override val id = TestEchoMcpIntegration.ID
    override val displayName = "Test"

    override fun register(server: Server) {
        server.addTool(
            name = "echo",
            description = "Echoes the input message",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    put(
                        "message",
                        buildJsonObject {
                            put("type", JsonPrimitive("string"))
                        }
                    )
                },
                required = listOf("message")
            )
        ) { request ->
            val message = request.params.arguments
                ?.get("message")?.jsonPrimitive?.content ?: ""
            CallToolResult(content = listOf(TextContent(message)))
        }
        server.addTool(
            name = "get_time",
            description = "Returns a fixed string (tests don't care about the value)"
        ) {
            CallToolResult(content = listOf(TextContent("synth-time")))
        }
    }
}
