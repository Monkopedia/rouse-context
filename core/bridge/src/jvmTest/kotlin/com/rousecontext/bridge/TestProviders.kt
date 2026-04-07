package com.rousecontext.bridge

import com.rousecontext.mcp.core.McpServerProvider
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Test MCP provider that registers an "echo" tool.
 */
class EchoProvider : McpServerProvider {
    override val id = "test"
    override val displayName = "Test Integration"

    override fun register(server: Server) {
        server.addTool(
            name = "echo",
            description = "Echoes back the input message",
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
                ?.get("message")?.jsonPrimitive?.content ?: "empty"
            CallToolResult(content = listOf(TextContent(message)))
        }
    }
}

/**
 * Test MCP provider simulating a health integration with a "get_steps" tool.
 */
class HealthProvider : McpServerProvider {
    override val id = "health"
    override val displayName = "Health Connect"

    override fun register(server: Server) {
        server.addTool(
            name = "get_steps",
            description = "Returns step count",
            inputSchema = ToolSchema(
                properties = buildJsonObject {},
                required = emptyList()
            )
        ) {
            CallToolResult(content = listOf(TextContent("10000 steps")))
        }
    }
}
