package com.rousecontext.mcp.core

import io.ktor.client.request.delete
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Tests for DELETE /mcp (session teardown) route from the Streamable HTTP transport.
 */
class McpDeleteTest {

    private class EchoProvider : McpServerProvider {
        override val id = "health"
        override val displayName = "Health Connect"

        override fun register(server: Server) {
            server.addTool(
                name = "echo",
                description = "Echoes input",
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
                val msg = request.params.arguments?.get("message")
                    ?.jsonPrimitive?.content ?: "empty"
                CallToolResult(content = listOf(TextContent(msg)))
            }
        }
    }

    private fun mcpJsonRpc(method: String, params: String? = null, id: Int = 1): String {
        val paramsStr = if (params != null) ""","params":$params""" else ""
        return """{"jsonrpc":"2.0","method":"$method"$paramsStr,"id":$id}"""
    }

    private val initializeParams =
        """{"protocolVersion":"2025-03-26","capabilities":{}""" +
            ""","clientInfo":{"name":"test","version":"1.0"}}"""

    /**
     * Helper: sets up a test application with a single "health" integration,
     * creates a token, initializes an MCP session, and passes the token +
     * session id to [block].
     */
    private fun withSession(
        block: suspend io.ktor.server.testing.ApplicationTestBuilder.(
            token: String,
            sessionId: String
        ) -> Unit
    ) = testApplication {
        val registry = InMemoryProviderRegistry()
        registry.register("health", EchoProvider())
        registry.setEnabled("health", true)
        val tokenStore = InMemoryTokenStore()
        val token = tokenStore.createTokenPair("health", "client-A").accessToken

        application {
            configureMcpRouting(
                registry = registry,
                tokenStore = tokenStore,
                deviceCodeManager = DeviceCodeManager(tokenStore = tokenStore),
                hostname = "test.rousecontext.com",
                integration = "health"
            )
        }

        // Initialize a session via POST /mcp
        val initResp = client.post("/mcp") {
            header("Authorization", "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(mcpJsonRpc("initialize", initializeParams))
        }
        assertEquals(HttpStatusCode.OK, initResp.status)
        val sessionId = initResp.headers["Mcp-Session-Id"]
        assertNotNull("Must receive Mcp-Session-Id", sessionId)

        block(token, sessionId!!)
    }

    // ---- DELETE /mcp tests ----

    @Test
    fun `DELETE mcp with valid session returns 200`() = withSession { token, sessionId ->
        val resp = client.delete("/mcp") {
            header("Authorization", "Bearer $token")
            header("Mcp-Session-Id", sessionId)
        }
        assertEquals(HttpStatusCode.OK, resp.status)
    }

    @Test
    fun `DELETE mcp removes session so subsequent POST recreates it`() =
        withSession { token, sessionId ->
            // Delete the session
            val deleteResp = client.delete("/mcp") {
                header("Authorization", "Bearer $token")
                header("Mcp-Session-Id", sessionId)
            }
            assertEquals(HttpStatusCode.OK, deleteResp.status)

            // POST with the same id auto-recreates a fresh session under
            // that id and serves the request. The DELETE successfully
            // removed the original; the recreated session is brand new.
            val postResp = client.post("/mcp") {
                header("Authorization", "Bearer $token")
                header("Mcp-Session-Id", sessionId)
                contentType(ContentType.Application.Json)
                setBody(mcpJsonRpc("tools/list", id = 2))
            }
            assertEquals(HttpStatusCode.OK, postResp.status)
            assertEquals(sessionId, postResp.headers["Mcp-Session-Id"])
        }

    @Test
    fun `DELETE mcp with invalid session returns 404`() = withSession { token, _ ->
        val resp = client.delete("/mcp") {
            header("Authorization", "Bearer $token")
            header("Mcp-Session-Id", "nonexistent-session-id")
        }
        assertEquals(HttpStatusCode.NotFound, resp.status)
    }

    @Test
    fun `DELETE mcp without auth returns 401`() = withSession { _, sessionId ->
        val resp = client.delete("/mcp") {
            header("Mcp-Session-Id", sessionId)
        }
        assertEquals(HttpStatusCode.Unauthorized, resp.status)
    }

    @Test
    fun `DELETE mcp without session id returns 400`() = withSession { token, _ ->
        val resp = client.delete("/mcp") {
            header("Authorization", "Bearer $token")
        }
        assertEquals(HttpStatusCode.BadRequest, resp.status)
    }

    @Test
    fun `DELETE mcp enforces client binding`() = testApplication {
        val registry = InMemoryProviderRegistry()
        registry.register("health", EchoProvider())
        registry.setEnabled("health", true)
        val tokenStore = InMemoryTokenStore()
        val tokenA = tokenStore.createTokenPair("health", "client-A").accessToken
        val tokenB = tokenStore.createTokenPair("health", "client-B").accessToken

        application {
            configureMcpRouting(
                registry = registry,
                tokenStore = tokenStore,
                deviceCodeManager = DeviceCodeManager(tokenStore = tokenStore),
                hostname = "test.rousecontext.com",
                integration = "health"
            )
        }

        // Client A initializes a session
        val initResp = client.post("/mcp") {
            header("Authorization", "Bearer $tokenA")
            contentType(ContentType.Application.Json)
            setBody(mcpJsonRpc("initialize", initializeParams))
        }
        val sessionId = initResp.headers["Mcp-Session-Id"]!!

        // Client B tries to delete Client A's session
        val deleteResp = client.delete("/mcp") {
            header("Authorization", "Bearer $tokenB")
            header("Mcp-Session-Id", sessionId)
        }
        assertEquals(
            "Cross-client session delete must be rejected",
            HttpStatusCode.NotFound,
            deleteResp.status
        )

        // Client A can still use the session (it was not deleted)
        val postResp = client.post("/mcp") {
            header("Authorization", "Bearer $tokenA")
            header("Mcp-Session-Id", sessionId)
            contentType(ContentType.Application.Json)
            setBody(mcpJsonRpc("tools/list", id = 2))
        }
        assertEquals(HttpStatusCode.OK, postResp.status)
    }
}
