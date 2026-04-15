package com.rousecontext.mcp.tool

import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End-to-end: register a DSL tool on a real [Server] and invoke it through the
 * SDK's registered-tool handler. Verifies the Tool exposed to `tools/list`
 * contains the generated schema, and that `tools/call` via the handler yields
 * the right CallToolResult for valid, missing, and invalid args.
 */
class McpToolEndToEndTest {

    private class GreetTool : McpTool() {
        override val name = "greet"
        override val description = "Say hello"
        val who by stringParam("who", "Person to greet")
        val excited by boolParam("excited", "Add exclamation").default(false)
        override suspend fun execute(): ToolResult =
            ToolResult.Success("hello, $who" + if (excited == true) "!" else "")
    }

    private fun server() = Server(
        Implementation("test", "0.1"),
        ServerOptions(
            capabilities = ServerCapabilities(
                tools = ServerCapabilities.Tools(listChanged = false)
            )
        )
    )

    @Test
    fun `tool registration exposes generated schema`() {
        val server = server()
        server.registerTool { GreetTool() }
        val tool = server.tools["greet"]!!.tool
        assertEquals("greet", tool.name)
        assertEquals("Say hello", tool.description)
        val props = tool.inputSchema.properties!!
        assertEquals("string", props["who"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("boolean", props["excited"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals(listOf("who"), tool.inputSchema.required)
    }

    @Test
    fun `tools-call with valid args returns success`() = runBlocking {
        val server = server()
        server.registerTool { GreetTool() }
        val handler = server.tools["greet"]!!.handler
        val request = CallToolRequest(
            params = CallToolRequestParams(
                name = "greet",
                arguments = buildJsonObject {
                    put("who", JsonPrimitive("world"))
                    put("excited", JsonPrimitive(true))
                }
            )
        )
        val result = handler.invoke(StubClientConnection, request)
        assertEquals(false, result.isError == true)
        assertEquals("hello, world!", (result.content.first() as TextContent).text)
    }

    @Test
    fun `tools-call with missing required param returns MCP error`() = runBlocking {
        val server = server()
        server.registerTool { GreetTool() }
        val handler = server.tools["greet"]!!.handler
        val request = CallToolRequest(
            params = CallToolRequestParams(
                name = "greet",
                arguments = buildJsonObject {}
            )
        )
        val result = handler.invoke(StubClientConnection, request)
        assertTrue(result.isError == true)
        val text = (result.content.first() as TextContent).text!!
        assertTrue(text, text.contains("Missing required parameter 'who'"))
    }

    @Test
    fun `tools-call with wrong type returns MCP error`() = runBlocking {
        val server = server()
        server.registerTool { GreetTool() }
        val handler = server.tools["greet"]!!.handler
        val request = CallToolRequest(
            params = CallToolRequestParams(
                name = "greet",
                arguments = buildJsonObject { put("who", JsonPrimitive(42)) }
            )
        )
        val result = handler.invoke(StubClientConnection, request)
        assertTrue(result.isError == true)
        val text = (result.content.first() as TextContent).text!!
        assertTrue(text, text.contains("must be a string"))
    }
}
