package com.rousecontext.mcp.core

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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Regression tests for issue #206: MCP [Mcp-Session-Id] MUST be bound to the
 * OAuth client id that initialized the session. A different client (even one
 * with a valid token for the same integration) must not be able to reuse the
 * session id.
 */
class McpSessionClientBindingTest {

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

    private fun testApp(
        block: suspend io.ktor.server.testing.ApplicationTestBuilder.(
            tokenA: String,
            tokenB: String
        ) -> Unit
    ) = testApplication {
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

        block(tokenA, tokenB)
    }

    @Test
    fun `different client id cannot reuse session id from another client`() =
        testApp { tokenA, tokenB ->
            // Client A initializes a session
            val initResp = client.post("/mcp") {
                header("Authorization", "Bearer $tokenA")
                contentType(ContentType.Application.Json)
                setBody(mcpJsonRpc("initialize", initializeParams))
            }
            assertEquals(HttpStatusCode.OK, initResp.status)
            val sessionId = initResp.headers["Mcp-Session-Id"]
            assertNotNull("Client A must receive Mcp-Session-Id", sessionId)

            // Client B attempts to reuse Client A's session id with its own token
            val hijackResp = client.post("/mcp") {
                header("Authorization", "Bearer $tokenB")
                header("Mcp-Session-Id", sessionId!!)
                contentType(ContentType.Application.Json)
                setBody(mcpJsonRpc("tools/list", id = 2))
            }
            assertEquals(
                "Cross-client session reuse must be rejected (404 per spec)",
                HttpStatusCode.NotFound,
                hijackResp.status
            )
        }

    @Test
    fun `original client can continue using its own session id`() = testApp { tokenA, _ ->
        val initResp = client.post("/mcp") {
            header("Authorization", "Bearer $tokenA")
            contentType(ContentType.Application.Json)
            setBody(mcpJsonRpc("initialize", initializeParams))
        }
        val sessionId = initResp.headers["Mcp-Session-Id"]!!

        val listResp = client.post("/mcp") {
            header("Authorization", "Bearer $tokenA")
            header("Mcp-Session-Id", sessionId)
            contentType(ContentType.Application.Json)
            setBody(mcpJsonRpc("tools/list", id = 2))
        }
        assertEquals(HttpStatusCode.OK, listResp.status)
    }

    @Test
    fun `two clients get independent sessions scoped to their own tokens`() =
        testApp { tokenA, tokenB ->
            val initA = client.post("/mcp") {
                header("Authorization", "Bearer $tokenA")
                contentType(ContentType.Application.Json)
                setBody(mcpJsonRpc("initialize", initializeParams))
            }
            val sessionA = initA.headers["Mcp-Session-Id"]!!

            val initB = client.post("/mcp") {
                header("Authorization", "Bearer $tokenB")
                contentType(ContentType.Application.Json)
                setBody(mcpJsonRpc("initialize", initializeParams))
            }
            val sessionB = initB.headers["Mcp-Session-Id"]!!

            assertNotEquals("Sessions must be distinct", sessionA, sessionB)

            // A on A's session works
            val okA = client.post("/mcp") {
                header("Authorization", "Bearer $tokenA")
                header("Mcp-Session-Id", sessionA)
                contentType(ContentType.Application.Json)
                setBody(mcpJsonRpc("tools/list", id = 2))
            }
            assertEquals(HttpStatusCode.OK, okA.status)

            // B on B's session works
            val okB = client.post("/mcp") {
                header("Authorization", "Bearer $tokenB")
                header("Mcp-Session-Id", sessionB)
                contentType(ContentType.Application.Json)
                setBody(mcpJsonRpc("tools/list", id = 2))
            }
            assertEquals(HttpStatusCode.OK, okB.status)

            // A cannot use B's session id
            val crossAB = client.post("/mcp") {
                header("Authorization", "Bearer $tokenA")
                header("Mcp-Session-Id", sessionB)
                contentType(ContentType.Application.Json)
                setBody(mcpJsonRpc("tools/list", id = 3))
            }
            assertEquals(HttpStatusCode.NotFound, crossAB.status)

            // B cannot use A's session id
            val crossBA = client.post("/mcp") {
                header("Authorization", "Bearer $tokenB")
                header("Mcp-Session-Id", sessionA)
                contentType(ContentType.Application.Json)
                setBody(mcpJsonRpc("tools/list", id = 3))
            }
            assertEquals(HttpStatusCode.NotFound, crossBA.status)
        }

    @Test
    fun `second token from same client id CAN reuse session`() = testApplication {
        // Two tokens issued to the SAME client id (e.g. refresh rotation).
        // After rotation, the old access token is revoked, so this models the
        // case of two parallel-issued tokens to the same client-id.
        val registry = InMemoryProviderRegistry()
        registry.register("health", EchoProvider())
        registry.setEnabled("health", true)
        val tokenStore = InMemoryTokenStore()
        val tokenA1 = tokenStore.createTokenPair("health", "client-A").accessToken
        val tokenA2 = tokenStore.createTokenPair("health", "client-A").accessToken

        application {
            configureMcpRouting(
                registry = registry,
                tokenStore = tokenStore,
                deviceCodeManager = DeviceCodeManager(tokenStore = tokenStore),
                hostname = "test.rousecontext.com",
                integration = "health"
            )
        }

        val initResp = client.post("/mcp") {
            header("Authorization", "Bearer $tokenA1")
            contentType(ContentType.Application.Json)
            setBody(mcpJsonRpc("initialize", initializeParams))
        }
        val sessionId = initResp.headers["Mcp-Session-Id"]!!

        // Second token for same client id should be accepted
        val listResp = client.post("/mcp") {
            header("Authorization", "Bearer $tokenA2")
            header("Mcp-Session-Id", sessionId)
            contentType(ContentType.Application.Json)
            setBody(mcpJsonRpc("tools/list", id = 2))
        }
        assertEquals(HttpStatusCode.OK, listResp.status)
    }
}
