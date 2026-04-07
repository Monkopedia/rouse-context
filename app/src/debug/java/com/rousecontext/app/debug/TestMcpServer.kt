package com.rousecontext.app.debug

import com.rousecontext.mcp.core.McpServerProvider
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Debug-only MCP server with simple tools for verifying end-to-end connectivity.
 *
 * Tools:
 * - `echo` — returns the input message unchanged
 * - `get_time` — returns device time as ISO-8601
 * - `device_info` — returns device model, Android version, app version
 */
class TestMcpServer : McpServerProvider {

    override val id = "test"
    override val displayName = "Test Tools"

    override fun register(server: Server) {
        registerEcho(server)
        registerGetTime(server)
        registerDeviceInfo(server)
    }

    private fun registerEcho(server: Server) {
        server.addTool(
            name = "echo",
            description = "Returns the input message unchanged." +
                " Simplest tool for testing connectivity.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    put(
                        "message",
                        buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put("description", JsonPrimitive("The message to echo back"))
                        }
                    )
                },
                required = listOf("message")
            )
        ) { request ->
            val message = request.params.arguments?.get("message")?.jsonPrimitive?.content ?: ""
            CallToolResult(content = listOf(TextContent(message)))
        }
    }

    private fun registerGetTime(server: Server) {
        server.addTool(
            name = "get_time",
            description = "Returns the current device time as an ISO-8601 string."
        ) {
            val now = DateTimeFormatter.ISO_OFFSET_DATE_TIME
                .withZone(ZoneId.systemDefault())
                .format(Instant.now())
            CallToolResult(content = listOf(TextContent(now)))
        }
    }

    private fun registerDeviceInfo(server: Server) {
        server.addTool(
            name = "device_info",
            description = "Returns device model, Android version, and app version."
        ) {
            val info = buildString {
                appendLine("model: ${android.os.Build.MODEL}")
                appendLine("manufacturer: ${android.os.Build.MANUFACTURER}")
                appendLine("android_version: ${android.os.Build.VERSION.RELEASE}")
                appendLine("sdk_int: ${android.os.Build.VERSION.SDK_INT}")
            }
            CallToolResult(content = listOf(TextContent(info)))
        }
    }
}
