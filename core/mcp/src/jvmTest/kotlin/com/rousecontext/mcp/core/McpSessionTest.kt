package com.rousecontext.mcp.core

import io.modelcontextprotocol.kotlin.sdk.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.Implementation
import io.modelcontextprotocol.kotlin.sdk.ReadResourceRequest
import io.modelcontextprotocol.kotlin.sdk.TextContent
import io.modelcontextprotocol.kotlin.sdk.TextResourceContents
import io.modelcontextprotocol.kotlin.sdk.Tool
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StdioClientTransport
import io.modelcontextprotocol.kotlin.sdk.server.Server
import java.net.ServerSocket
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class McpSessionTest {

    private class TestProvider : McpServerProvider {
        override val id = "test"
        override val displayName = "Test Provider"

        override fun register(server: Server) {
            server.addTool(
                name = "echo",
                description = "Echoes back the input message",
                inputSchema = Tool.Input(
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
                val message = request.arguments["message"]?.jsonPrimitive?.content ?: "empty"
                CallToolResult(content = listOf(TextContent(message)))
            }

            server.addResource(
                uri = "test://greeting",
                name = "Greeting",
                description = "A test greeting",
                mimeType = "text/plain"
            ) { request ->
                io.modelcontextprotocol.kotlin.sdk.ReadResourceResult(
                    contents = listOf(
                        TextResourceContents("Hello from test", request.uri, "text/plain")
                    )
                )
            }
        }
    }

    /**
     * Sets up a loopback MCP session (server + client) and runs [block] once the
     * client handshake completes. All coroutines are structured children of the
     * [runBlocking] scope and cleaned up in the finally block.
     */
    private fun mcpTest(
        providers: List<McpServerProvider> = listOf(TestProvider()),
        block: suspend (Client) -> Unit
    ) = runBlocking {
        val serverSocket = ServerSocket(0)
        val session = McpSession(providers = providers)

        // Server: accept connection, run MCP session
        launch(Dispatchers.IO) {
            serverSocket.use { ss ->
                val socket = ss.accept()
                socket.use {
                    session.run(it.getInputStream(), it.getOutputStream())
                }
            }
        }

        // Client: connect transport and complete handshake
        val client = Client(Implementation(name = "test-client", version = "1.0"))
        val clientSocket = java.net.Socket("127.0.0.1", serverSocket.localPort)
        val clientTransport = StdioClientTransport(
            clientSocket.getInputStream().asSource().buffered(),
            clientSocket.getOutputStream().asSink().buffered()
        )

        val clientConnected = CompletableDeferred<Unit>()
        // connect() starts long-lived transport read/write children in this scope
        launch(Dispatchers.IO) {
            client.connect(clientTransport)
            clientConnected.complete(Unit)
        }
        clientConnected.await()

        try {
            block(client)
        } finally {
            client.close()
            session.close()
            clientSocket.close()
            coroutineContext.cancelChildren()
        }
    }

    @Test
    fun `session handles initialize and tools list`() = mcpTest { client ->
        val toolsResult = client.listTools()!!
        assertEquals(1, toolsResult.tools.size)

        val tool = toolsResult.tools.first()
        assertEquals("echo", tool.name)
        assertEquals("Echoes back the input message", tool.description)
    }

    @Test
    fun `session handles tool call`() = mcpTest { client ->
        val result = client.callTool(
            name = "echo",
            arguments = mapOf("message" to JsonPrimitive("hello world"))
        )
        assertNotNull(result)
        val content = (result as CallToolResult).content.first() as TextContent
        assertEquals("hello world", content.text)
    }

    @Test
    fun `session handles resource read`() = mcpTest { client ->
        val result = client.readResource(ReadResourceRequest(uri = "test://greeting"))!!
        assertEquals(1, result.contents.size)
        val content = result.contents.first() as TextResourceContents
        assertEquals("Hello from test", content.text)
        assertEquals("text/plain", content.mimeType)
    }

    @Test
    fun `session aggregates tools from multiple providers`() {
        val secondProvider = object : McpServerProvider {
            override val id = "second"
            override val displayName = "Second Provider"

            override fun register(server: Server) {
                server.addTool(
                    name = "ping",
                    description = "Returns pong"
                ) { _ ->
                    CallToolResult(content = listOf(TextContent("pong")))
                }
            }
        }

        mcpTest(providers = listOf(TestProvider(), secondProvider)) { client ->
            val toolsResult = client.listTools()!!
            assertEquals(2, toolsResult.tools.size)

            val toolNames = toolsResult.tools.map { it.name }.toSet()
            assertEquals(setOf("echo", "ping"), toolNames)
        }
    }
}
