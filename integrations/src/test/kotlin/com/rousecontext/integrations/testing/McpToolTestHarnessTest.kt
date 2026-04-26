package com.rousecontext.integrations.testing

import io.mockk.mockk
import io.modelcontextprotocol.kotlin.sdk.server.ClientConnection
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Regression test for [McpToolTestHarness] — proves the JSON parse assertion
 * fires when a tool returns a non-JSON body. This is the gate that catches
 * the malformed-JSON bug class that shipped in #417.
 */
class McpToolTestHarnessTest {

    private val fakeConnection: ClientConnection = mockk(relaxed = true)

    @Test
    fun `harness throws when tool returns non-JSON body and expectJsonBody is true`() =
        runBlocking {
            val harness = McpToolTestHarness()
            val server = harness.createMockServer()

            // Register a fake tool whose body is intentionally NOT valid JSON.
            server.addTool(
                name = "broken_tool",
                description = "returns garbage",
                inputSchema = ToolSchema(properties = buildJsonObject {}),
                handler = { _ ->
                    CallToolResult(
                        content = listOf(TextContent(text = "not valid json")),
                        isError = false
                    )
                }
            )

            try {
                harness.callTool(
                    name = "broken_tool",
                    arguments = buildJsonObject {},
                    connection = fakeConnection
                )
                fail("Harness should have thrown on non-JSON body")
            } catch (e: AssertionError) {
                val msg = e.message ?: ""
                assertTrue(
                    "Message should name the offending tool (was: $msg)",
                    msg.contains("broken_tool")
                )
                assertTrue(
                    "Message should include the bad body (was: $msg)",
                    msg.contains("not valid json")
                )
            }
        }

    @Test
    fun `harness allows non-JSON body when expectJsonBody is false`() = runBlocking {
        val harness = McpToolTestHarness()
        val server = harness.createMockServer()

        server.addTool(
            name = "plain_text_tool",
            description = "returns plain text",
            inputSchema = ToolSchema(properties = buildJsonObject {}),
            handler = { _ ->
                CallToolResult(
                    content = listOf(TextContent(text = "just a string")),
                    isError = false
                )
            }
        )

        // Should not throw with the opt-out flag.
        val result = harness.callTool(
            name = "plain_text_tool",
            arguments = buildJsonObject {},
            connection = fakeConnection,
            expectJsonBody = false
        )

        assertEquals("just a string", (result.content.first() as TextContent).text)
    }

    @Test
    fun `harness passes when tool returns valid JSON body`() = runBlocking {
        val harness = McpToolTestHarness()
        val server = harness.createMockServer()

        server.addTool(
            name = "good_tool",
            description = "returns proper JSON",
            inputSchema = ToolSchema(properties = buildJsonObject {}),
            handler = { _ ->
                CallToolResult(
                    content = listOf(TextContent(text = """{"ok":true}""")),
                    isError = false
                )
            }
        )

        val result = harness.callTool(
            name = "good_tool",
            arguments = buildJsonObject {},
            connection = fakeConnection
        )

        assertEquals("""{"ok":true}""", (result.content.first() as TextContent).text)
    }

    @Test
    fun `harness validates JSON on error results too`() = runBlocking {
        val harness = McpToolTestHarness()
        val server = harness.createMockServer()

        server.addTool(
            name = "broken_error_tool",
            description = "error path returns bad JSON",
            inputSchema = ToolSchema(properties = buildJsonObject {}),
            handler = { _ ->
                CallToolResult(
                    content = listOf(
                        TextContent(text = """{"error": "exception with " quotes"}""")
                    ),
                    isError = true
                )
            }
        )

        try {
            harness.callTool(
                name = "broken_error_tool",
                arguments = buildJsonObject {},
                connection = fakeConnection
            )
            fail("Harness should have thrown on malformed JSON in error path")
        } catch (e: AssertionError) {
            assertTrue(e.message!!.contains("broken_error_tool"))
        }
    }

    @Test
    fun `harness captures tool names for inspection`() {
        val harness = McpToolTestHarness()
        val server = harness.createMockServer()

        server.addTool(
            name = "tool_a",
            description = "a",
            inputSchema = ToolSchema(properties = buildJsonObject {}),
            handler = { _ -> CallToolResult(content = listOf(TextContent(text = "{}"))) }
        )
        server.addTool(
            name = "tool_b",
            description = "b",
            inputSchema = ToolSchema(properties = buildJsonObject {}),
            handler = { _ -> CallToolResult(content = listOf(TextContent(text = "{}"))) }
        )

        assertEquals(setOf("tool_a", "tool_b"), harness.toolHandlers.keys)
    }

    // Suppress unused import warnings for Server / Tool which appear when imports
    // are present but the class is created via mockk.
    @Suppress("unused")
    private fun keepImports(): Pair<Server?, Tool?> = null to null
}
