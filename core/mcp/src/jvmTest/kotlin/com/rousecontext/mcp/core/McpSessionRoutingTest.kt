package com.rousecontext.mcp.core

import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for Mcp-Session-Id based session routing (issue #189).
 *
 * Each `initialize` request creates a new session with a unique id returned
 * via the `Mcp-Session-Id` response header. Subsequent requests must include
 * that header to route to the correct Server instance.
 */
class McpSessionRoutingTest {

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
     * Helper: creates a test application with an echo provider and returns
     * a bearer token for auth. Uses [clock] for session timeout testing.
     */
    private fun testApp(
        clock: Clock = SystemClock,
        block: suspend io.ktor.server.testing.ApplicationTestBuilder.(token: String) -> Unit
    ) = testApplication {
        val registry = InMemoryProviderRegistry()
        registry.register("health", EchoProvider())
        registry.setEnabled("health", true)
        val tokenStore = InMemoryTokenStore()
        val token = tokenStore.createTokenPair("health", "test-client").accessToken

        application {
            configureMcpRouting(
                registry = registry,
                tokenStore = tokenStore,
                deviceCodeManager = DeviceCodeManager(tokenStore = tokenStore),
                hostname = "test.rousecontext.com",
                integration = "health",
                clock = clock
            )
        }

        block(token)
    }

    // -- Test 1: Two concurrent sessions on same integration --

    @Test
    fun `two concurrent sessions get different session ids and work independently`() =
        testApp { token ->
            // First session: initialize
            val resp1 = client.post("/mcp") {
                header("Authorization", "Bearer $token")
                contentType(ContentType.Application.Json)
                setBody(mcpJsonRpc("initialize", initializeParams))
            }
            assertEquals(HttpStatusCode.OK, resp1.status)
            val sessionId1 = resp1.headers["Mcp-Session-Id"]
            assertNotNull("First initialize must return Mcp-Session-Id", sessionId1)

            // Second session: initialize
            val resp2 = client.post("/mcp") {
                header("Authorization", "Bearer $token")
                contentType(ContentType.Application.Json)
                setBody(mcpJsonRpc("initialize", initializeParams))
            }
            assertEquals(HttpStatusCode.OK, resp2.status)
            val sessionId2 = resp2.headers["Mcp-Session-Id"]
            assertNotNull("Second initialize must return Mcp-Session-Id", sessionId2)
            assertNotEquals("Sessions must have different ids", sessionId1, sessionId2)

            // Both sessions can list tools independently
            val list1 = client.post("/mcp") {
                header("Authorization", "Bearer $token")
                header("Mcp-Session-Id", sessionId1!!)
                contentType(ContentType.Application.Json)
                setBody(mcpJsonRpc("tools/list", id = 2))
            }
            assertEquals(HttpStatusCode.OK, list1.status)
            val body1 = Json.parseToJsonElement(list1.bodyAsText()).jsonObject
            assertNotNull("Session 1 tools/list should have result", body1["result"])

            val list2 = client.post("/mcp") {
                header("Authorization", "Bearer $token")
                header("Mcp-Session-Id", sessionId2!!)
                contentType(ContentType.Application.Json)
                setBody(mcpJsonRpc("tools/list", id = 3))
            }
            assertEquals(HttpStatusCode.OK, list2.status)
            val body2 = Json.parseToJsonElement(list2.bodyAsText()).jsonObject
            assertNotNull("Session 2 tools/list should have result", body2["result"])
        }

    // -- Test 2: Unknown session id returns 404 --

    @Test
    fun `unknown session id returns 404`() = testApp { token ->
        val resp = client.post("/mcp") {
            header("Authorization", "Bearer $token")
            header("Mcp-Session-Id", "nonexistent-session-id")
            contentType(ContentType.Application.Json)
            setBody(mcpJsonRpc("tools/list"))
        }
        assertEquals(HttpStatusCode.NotFound, resp.status)
    }

    // -- Test 3: Missing session id on non-initialize request returns error --

    @Test
    fun `missing session id on non-initialize request returns 400`() = testApp { token ->
        val resp = client.post("/mcp") {
            header("Authorization", "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(mcpJsonRpc("tools/list"))
        }
        assertEquals(HttpStatusCode.BadRequest, resp.status)
    }

    // -- Test 4: Session idle timeout evicts --

    @Test
    fun `session idle timeout evicts session`() {
        val clock = FakeClock()
        testApp(clock = clock) { token ->
            // Initialize a session
            val resp = client.post("/mcp") {
                header("Authorization", "Bearer $token")
                contentType(ContentType.Application.Json)
                setBody(mcpJsonRpc("initialize", initializeParams))
            }
            val sessionId = resp.headers["Mcp-Session-Id"]!!

            // Advance clock past the idle timeout (30 min + 1 min)
            clock.advanceMinutes(31)

            // Next request triggers sweep; session should be gone
            val resp2 = client.post("/mcp") {
                header("Authorization", "Bearer $token")
                header("Mcp-Session-Id", sessionId)
                contentType(ContentType.Application.Json)
                setBody(mcpJsonRpc("tools/list", id = 2))
            }
            assertEquals(HttpStatusCode.NotFound, resp2.status)
        }
    }

    // -- Test 5: Per-integration cap evicts oldest --

    @Test
    fun `per-integration session cap evicts oldest session`() = testApp { token ->
        val sessionIds = mutableListOf<String>()

        // Create MAX_SESSIONS_PER_INTEGRATION + 1 sessions
        for (i in 0..MAX_SESSIONS_PER_INTEGRATION) {
            val resp = client.post("/mcp") {
                header("Authorization", "Bearer $token")
                contentType(ContentType.Application.Json)
                setBody(mcpJsonRpc("initialize", initializeParams, id = i + 1))
            }
            assertEquals(HttpStatusCode.OK, resp.status)
            sessionIds.add(resp.headers["Mcp-Session-Id"]!!)
        }

        // The first session should be evicted
        val evictedResp = client.post("/mcp") {
            header("Authorization", "Bearer $token")
            header("Mcp-Session-Id", sessionIds.first())
            contentType(ContentType.Application.Json)
            setBody(mcpJsonRpc("tools/list", id = 100))
        }
        assertEquals(HttpStatusCode.NotFound, evictedResp.status)

        // The last session should still work
        val lastResp = client.post("/mcp") {
            header("Authorization", "Bearer $token")
            header("Mcp-Session-Id", sessionIds.last())
            contentType(ContentType.Application.Json)
            setBody(mcpJsonRpc("tools/list", id = 101))
        }
        assertEquals(HttpStatusCode.OK, lastResp.status)
        val body = Json.parseToJsonElement(lastResp.bodyAsText()).jsonObject
        assertNotNull("Last session tools/list should have result", body["result"])
    }

    // -- Test 6: Existing single-client flow still works --

    @Test
    fun `single client initialize then tools list then tools call works`() = testApp { token ->
        // Initialize
        val initResp = client.post("/mcp") {
            header("Authorization", "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(mcpJsonRpc("initialize", initializeParams))
        }
        assertEquals(HttpStatusCode.OK, initResp.status)
        val sessionId = initResp.headers["Mcp-Session-Id"]!!
        val initBody = Json.parseToJsonElement(initResp.bodyAsText()).jsonObject
        assertNotNull("initialize should have result", initBody["result"])
        assertNull("initialize should not have error", initBody["error"])

        // tools/list
        val listResp = client.post("/mcp") {
            header("Authorization", "Bearer $token")
            header("Mcp-Session-Id", sessionId)
            contentType(ContentType.Application.Json)
            setBody(mcpJsonRpc("tools/list", id = 2))
        }
        assertEquals(HttpStatusCode.OK, listResp.status)
        val listBody = Json.parseToJsonElement(listResp.bodyAsText()).jsonObject
        assertNotNull("tools/list should have result", listBody["result"])

        // tools/call echo
        val callResp = client.post("/mcp") {
            header("Authorization", "Bearer $token")
            header("Mcp-Session-Id", sessionId)
            contentType(ContentType.Application.Json)
            setBody(
                mcpJsonRpc(
                    "tools/call",
                    """{"name":"echo","arguments":{"message":"hello"}}""",
                    id = 3
                )
            )
        }
        assertEquals(HttpStatusCode.OK, callResp.status)
        val callBody = Json.parseToJsonElement(callResp.bodyAsText()).jsonObject
        assertNotNull("tools/call should have result", callBody["result"])

        // Verify the echo result
        val resultContent = callBody["result"]!!.jsonObject["content"]
        assertTrue(
            "Echo result should contain 'hello'",
            resultContent.toString().contains("hello")
        )
    }

    // -- Test 7: Subsequent responses echo Mcp-Session-Id --

    @Test
    fun `subsequent responses echo Mcp-Session-Id header`() = testApp { token ->
        val initResp = client.post("/mcp") {
            header("Authorization", "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(mcpJsonRpc("initialize", initializeParams))
        }
        val sessionId = initResp.headers["Mcp-Session-Id"]!!

        val listResp = client.post("/mcp") {
            header("Authorization", "Bearer $token")
            header("Mcp-Session-Id", sessionId)
            contentType(ContentType.Application.Json)
            setBody(mcpJsonRpc("tools/list", id = 2))
        }
        assertEquals(
            "Response should echo Mcp-Session-Id",
            sessionId,
            listResp.headers["Mcp-Session-Id"]
        )
    }
}
