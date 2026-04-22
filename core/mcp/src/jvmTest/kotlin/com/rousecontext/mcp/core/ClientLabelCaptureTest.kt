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
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the `clientLabel` propagation on every [ToolCallEvent]
 * emitted from an MCP session (issue #344).
 *
 * The label is captured once per session from the OAuth Bearer token (via
 * [TokenStore.resolveClientLabel]) and every tool call emitted within the
 * session carries that same cached label. Sessions without a resolvable
 * label fall back to the literal [UNKNOWN_CLIENT_LABEL] -- the
 * `Unknown (#N)` monotonic numbering is follow-up work in issue #345.
 */
class ClientLabelCaptureTest {

    private class EchoProvider : McpServerProvider {
        override val id = "health"
        override val displayName = "Health Connect"

        override fun register(server: Server) {
            server.addTool(
                name = "echo",
                description = "Echoes the provided message",
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
            ) { _ ->
                CallToolResult(content = listOf(TextContent("ok")))
            }
        }
    }

    private class RecordingAuditListener : AuditListener {
        val toolCalls: MutableList<ToolCallEvent> = CopyOnWriteArrayList()

        override suspend fun onToolCall(event: ToolCallEvent) {
            toolCalls += event
        }
    }

    private fun mcpJsonRpc(method: String, params: String? = null, id: Int = 1): String {
        val paramsStr = if (params != null) ""","params":$params""" else ""
        return """{"jsonrpc":"2.0","method":"$method"$paramsStr,"id":$id}"""
    }

    private fun initJson(): String = mcpJsonRpc(
        "initialize",
        """{"protocolVersion":"2025-03-26","capabilities":{}""" +
            ""","clientInfo":{"name":"test","version":"1.0"}}"""
    )

    private fun toolCallJson(id: Int, message: String): String = mcpJsonRpc(
        "tools/call",
        """{"name":"echo","arguments":{"message":"$message"}}""",
        id = id
    )

    @Test
    fun `clientLabel is populated from TokenStore label on every tool call`() = testApplication {
        val registry = InMemoryProviderRegistry().apply {
            register("health", EchoProvider())
            setEnabled("health", true)
        }
        val tokenStore = InMemoryTokenStore()
        val token = tokenStore
            .createTokenPair("health", clientId = "client-42", clientName = "Claude Desktop")
            .accessToken
        val audit = RecordingAuditListener()

        application {
            configureMcpRouting(
                registry = registry,
                tokenStore = tokenStore,
                deviceCodeManager = DeviceCodeManager(tokenStore = tokenStore),
                hostname = "test.rousecontext.com",
                integration = "health",
                auditListener = audit
            )
        }

        // initialize -> sessionId
        val initResp = client.post("/mcp") {
            header("Authorization", "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(initJson())
        }
        assertEquals(HttpStatusCode.OK, initResp.status)
        val sessionId = initResp.headers["Mcp-Session-Id"]
        assertNotNull(sessionId)

        // Three tool calls within the same session
        repeat(3) { idx ->
            val r = client.post("/mcp") {
                header("Authorization", "Bearer $token")
                header("Mcp-Session-Id", sessionId!!)
                contentType(ContentType.Application.Json)
                setBody(toolCallJson(id = idx + 2, message = "m$idx"))
            }
            assertEquals(HttpStatusCode.OK, r.status)
            // drain
            Json.parseToJsonElement(r.bodyAsText()).jsonObject
        }

        assertEquals(3, audit.toolCalls.size)
        assertTrue(
            "All three tool calls should carry the cached clientLabel",
            audit.toolCalls.all { it.clientLabel == "Claude Desktop" }
        )
    }

    @Test
    fun `clientLabel falls back to client id label when clientName unset`() = testApplication {
        val registry = InMemoryProviderRegistry().apply {
            register("health", EchoProvider())
            setEnabled("health", true)
        }
        val tokenStore = InMemoryTokenStore()
        val token = tokenStore
            .createTokenPair("health", clientId = "bare-client", clientName = null)
            .accessToken
        val audit = RecordingAuditListener()

        application {
            configureMcpRouting(
                registry = registry,
                tokenStore = tokenStore,
                deviceCodeManager = DeviceCodeManager(tokenStore = tokenStore),
                hostname = "test.rousecontext.com",
                integration = "health",
                auditListener = audit
            )
        }

        val initResp = client.post("/mcp") {
            header("Authorization", "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(initJson())
        }
        val sessionId = initResp.headers["Mcp-Session-Id"]!!

        val r = client.post("/mcp") {
            header("Authorization", "Bearer $token")
            header("Mcp-Session-Id", sessionId)
            contentType(ContentType.Application.Json)
            setBody(toolCallJson(id = 2, message = "hi"))
        }
        assertEquals(HttpStatusCode.OK, r.status)
        Json.parseToJsonElement(r.bodyAsText()).jsonObject

        assertEquals(1, audit.toolCalls.size)
        // When clientName isn't set at DCR, TokenStore.resolveClientLabel
        // falls back to the clientId so the audit row still carries a
        // stable, non-empty identifier.
        assertEquals("bare-client", audit.toolCalls.single().clientLabel)
    }

    @Test
    fun `TokenStore resolveClientLabel returns null for invalid token`() {
        val store = InMemoryTokenStore()
        assertEquals(null, store.resolveClientLabel("health", "not-a-real-token"))
    }
}
