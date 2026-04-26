package com.rousecontext.integrations.testing

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.modelcontextprotocol.kotlin.sdk.server.ClientConnection
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject

/**
 * Shared harness for `*McpProvider*Test` classes that need to capture tool
 * registrations against a mocked [Server], invoke them with sample
 * [CallToolRequest]s, and assert on the resulting [CallToolResult].
 *
 * **Why this exists (#426 / #417):** the malformed-JSON bug in #417 shipped
 * because individual tool tests asserted on substrings of the result body but
 * never verified the body was parseable JSON. The connector proxy that
 * actually consumes these results fails on malformed JSON; our tests didn't.
 *
 * Every tool body returned via [callTool] is automatically run through
 * [Json.parseToJsonElement]. Parse failures surface as a clear
 * [AssertionError] naming the tool and the offending body. Tools whose bodies
 * are intentionally non-JSON can opt out per-call with `expectJsonBody = false`.
 *
 * Usage:
 * ```
 * private val harness = McpToolTestHarness()
 *
 * @Before fun setUp() {
 *     val server = harness.createMockServer()
 *     MyMcpProvider(...).register(server)
 * }
 *
 * @Test fun `tool does the thing`() = runBlocking {
 *     val result = harness.callTool(
 *         name = "my_tool",
 *         arguments = buildJsonObject { put("x", JsonPrimitive(1)) },
 *         connection = fakeConnection
 *     )
 *     // body is guaranteed parseable; substring assertions still allowed
 * }
 * ```
 */
class McpToolTestHarness {

    private val handlers =
        mutableMapOf<String, suspend ClientConnection.(CallToolRequest) -> CallToolResult>()
    private val schemas = mutableMapOf<String, ToolSchema>()

    /** Names of all registered tools, in registration order is not guaranteed. */
    val toolHandlers: Map<String, suspend ClientConnection.(CallToolRequest) -> CallToolResult>
        get() = handlers

    /** Schemas captured during tool registration. */
    val toolSchemas: Map<String, ToolSchema>
        get() = schemas

    /**
     * Build a `mockk<Server>(relaxed = true)` whose `addTool(...)` call captures
     * the registered name, schema, and handler into this harness instance.
     *
     * Multiple servers can share the same harness — handler maps merge. Tests
     * that need isolated capture (e.g. a "no DND" variant alongside the default
     * setup) should construct a separate [McpToolTestHarness].
     */
    fun createMockServer(): Server {
        val server = mockk<Server>(relaxed = true)
        val nameSlot = slot<String>()
        val schemaSlot = slot<ToolSchema>()
        val handlerSlot = slot<suspend ClientConnection.(CallToolRequest) -> CallToolResult>()
        every {
            server.addTool(
                name = capture(nameSlot),
                description = any(),
                inputSchema = capture(schemaSlot),
                handler = capture(handlerSlot)
            )
        } answers {
            handlers[nameSlot.captured] = handlerSlot.captured
            schemas[nameSlot.captured] = schemaSlot.captured
        }
        return server
    }

    /**
     * Invoke a previously-registered tool with the given arguments and return
     * its result. The result body is validated as JSON unless [expectJsonBody]
     * is `false`.
     *
     * @throws AssertionError if no tool with [name] was registered.
     * @throws AssertionError if [expectJsonBody] is `true` and any
     *   [TextContent] in the result fails to parse as JSON. The message
     *   includes the tool name and the offending body content.
     */
    suspend fun callTool(
        name: String,
        arguments: JsonObject = buildJsonObject {},
        connection: ClientConnection,
        expectJsonBody: Boolean = true
    ): CallToolResult {
        val handler = handlers[name]
            ?: throw AssertionError(
                "No tool registered with name '$name'. Registered: ${handlers.keys}"
            )

        val request = CallToolRequest(
            params = CallToolRequestParams(name = name, arguments = arguments)
        )
        val result = handler.invoke(connection, request)

        if (expectJsonBody) {
            assertResultIsJson(name, result)
        }
        return result
    }

    /**
     * Validate every [TextContent] body in [result] parses as JSON. Used
     * internally by [callTool]; exposed for tests that invoke handlers
     * directly but still want the gate.
     */
    fun assertResultIsJson(toolName: String, result: CallToolResult) {
        result.content
            .mapIndexedNotNull { index, content ->
                val text = (content as? TextContent)?.text
                if (text != null) index to text else null
            }
            .forEach { (index, body) ->
                try {
                    Json.parseToJsonElement(body)
                } catch (e: kotlinx.serialization.SerializationException) {
                    throw AssertionError(
                        buildString {
                            append("Tool '").append(toolName).append("' returned non-JSON body")
                            if (result.content.size > 1) {
                                append(" (content[").append(index).append("])")
                            }
                            append(": ").append(body)
                            append(" — parse error: ").append(e.message)
                        },
                        e
                    )
                }
            }
    }
}
