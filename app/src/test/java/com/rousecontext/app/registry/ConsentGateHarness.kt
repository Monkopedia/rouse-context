package com.rousecontext.app.registry

import com.rousecontext.mcp.core.McpServerProvider
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.modelcontextprotocol.kotlin.sdk.server.ClientConnection
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject

/**
 * Minimal capture harness for asserting on tool behaviour at the
 * [McpServerProvider] boundary from `:app` tests.
 *
 * The consent-gate regressions this exists for (`allow_actions`,
 * `dnd_toggled`) were both invisible to preference-round-trip tests: the
 * preference stored and read back fine, and nothing consumed it. Asserting
 * here — on what a tool call actually does — is the only assertion that can
 * catch that shape.
 *
 * `:integrations` has its own richer `McpToolTestHarness`; that source set is
 * not on the `:app` test classpath, hence this small local equivalent.
 */
class ConsentGateHarness {

    private val handlers =
        mutableMapOf<String, suspend ClientConnection.(CallToolRequest) -> CallToolResult>()

    val toolNames: Set<String> get() = handlers.keys

    val connection: ClientConnection = mockk(relaxed = true)

    fun register(provider: McpServerProvider) {
        val server = mockk<Server>(relaxed = true)
        val nameSlot = slot<String>()
        val handlerSlot = slot<suspend ClientConnection.(CallToolRequest) -> CallToolResult>()
        every {
            server.addTool(
                name = capture(nameSlot),
                description = any(),
                inputSchema = any(),
                handler = capture(handlerSlot)
            )
        } answers {
            handlers[nameSlot.captured] = handlerSlot.captured
        }
        provider.register(server)
    }

    suspend fun callTool(name: String, arguments: JsonObject = buildJsonObject {}): CallToolResult {
        val handler = handlers[name]
            ?: throw AssertionError(
                "No tool registered with name '$name'. Registered: ${handlers.keys}"
            )
        return handler.invoke(
            connection,
            CallToolRequest(params = CallToolRequestParams(name = name, arguments = arguments))
        )
    }

    companion object {
        fun bodyOf(result: CallToolResult): String =
            result.content.filterIsInstance<TextContent>().joinToString("\n") { it.text.orEmpty() }
    }
}
