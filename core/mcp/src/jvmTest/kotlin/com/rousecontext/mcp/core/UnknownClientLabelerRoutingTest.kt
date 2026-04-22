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
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Routing-level tests for the [UnknownClientLabeler] hookup (issue #345).
 *
 * Covers two code paths that replaced the literal `"unknown"` / `"Unknown"`
 * fallbacks:
 *
 * 1. Dynamic Client Registration without a `client_name` now persists a
 *    monotonic `Unknown (#N)` label on the [TokenEntity] equivalent, instead
 *    of the literal string `"unknown"`.
 * 2. Pre-#345 rows whose stored label is the literal `"unknown"` are
 *    lazily upgraded to `Unknown (#N)` the first time the token is resolved
 *    for an MCP session, so audit rows and the authorized-clients list
 *    pick up the new label without a schema migration.
 */
class UnknownClientLabelerRoutingTest {

    /**
     * In-memory labeler used by these tests so we don't need DataStore:
     * the interface contract is the only thing [McpRouting] depends on.
     */
    private class InMemoryLabeler : UnknownClientLabeler {
        private val map = mutableMapOf<String, String>()
        private var next = 1
        override suspend fun labelFor(clientId: String): String = synchronized(this) {
            map.getOrPut(clientId) { "Unknown (#${next++})" }
        }
    }

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
    fun `DCR without client_name persists monotonic Unknown label`() = testApplication {
        val registry = InMemoryProviderRegistry().apply {
            register("health", EchoProvider())
            setEnabled("health", true)
        }
        val tokenStore = InMemoryTokenStore()
        val labeler = InMemoryLabeler()

        application {
            configureMcpRouting(
                registry = registry,
                tokenStore = tokenStore,
                deviceCodeManager = DeviceCodeManager(tokenStore = tokenStore),
                hostname = "test.rousecontext.com",
                integration = "health",
                unknownClientLabeler = labeler
            )
        }

        // /register without client_name -> response carries Unknown (#1).
        val resp = client.post("/register") {
            contentType(ContentType.Application.Json)
            setBody("""{"redirect_uris":["https://claude.ai/cb"]}""")
        }
        assertEquals(HttpStatusCode.Created, resp.status)
        val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
        val clientId = body["client_id"]?.jsonPrimitive?.content
        assertNotNull("DCR must return a client_id", clientId)
        assertEquals("Unknown (#1)", body["client_name"]?.jsonPrimitive?.content)

        // Second anonymous DCR -> Unknown (#2), keyed on the fresh client_id.
        val resp2 = client.post("/register") {
            contentType(ContentType.Application.Json)
            setBody("""{"redirect_uris":["https://claude.ai/cb"]}""")
        }
        val body2 = Json.parseToJsonElement(resp2.bodyAsText()).jsonObject
        assertEquals("Unknown (#2)", body2["client_name"]?.jsonPrimitive?.content)
        assertNotEquals(
            clientId,
            body2["client_id"]?.jsonPrimitive?.content
        )
    }

    @Test
    fun `DCR with client_name leaves user-supplied label untouched`() = testApplication {
        val registry = InMemoryProviderRegistry().apply {
            register("health", EchoProvider())
            setEnabled("health", true)
        }
        val tokenStore = InMemoryTokenStore()
        val labeler = InMemoryLabeler()

        application {
            configureMcpRouting(
                registry = registry,
                tokenStore = tokenStore,
                deviceCodeManager = DeviceCodeManager(tokenStore = tokenStore),
                hostname = "test.rousecontext.com",
                integration = "health",
                unknownClientLabeler = labeler
            )
        }

        val resp = client.post("/register") {
            contentType(ContentType.Application.Json)
            setBody(
                """{"client_name":"Claude Desktop",""" +
                    """"redirect_uris":["https://claude.ai/cb"]}"""
            )
        }
        assertEquals(HttpStatusCode.Created, resp.status)
        val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
        assertEquals("Claude Desktop", body["client_name"]?.jsonPrimitive?.content)
    }

    @Test
    fun `legacy unknown label is lazily upgraded on tool call`() = testApplication {
        val registry = InMemoryProviderRegistry().apply {
            register("health", EchoProvider())
            setEnabled("health", true)
        }
        val tokenStore = InMemoryTokenStore()
        val labeler = InMemoryLabeler()

        // Simulate a pre-#345 row: DCR wrote the literal "unknown" as the
        // client_name when none was supplied.
        val token = tokenStore.createTokenPair(
            integrationId = "health",
            clientId = "legacy-client",
            clientName = "unknown"
        ).accessToken

        val audit = RecordingAuditListener()

        application {
            configureMcpRouting(
                registry = registry,
                tokenStore = tokenStore,
                deviceCodeManager = DeviceCodeManager(tokenStore = tokenStore),
                hostname = "test.rousecontext.com",
                integration = "health",
                auditListener = audit,
                unknownClientLabeler = labeler
            )
        }

        val initResp = client.post("/mcp") {
            header("Authorization", "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(initJson())
        }
        assertEquals(HttpStatusCode.OK, initResp.status)
        val sessionId = initResp.headers["Mcp-Session-Id"]!!

        val callResp = client.post("/mcp") {
            header("Authorization", "Bearer $token")
            header("Mcp-Session-Id", sessionId)
            contentType(ContentType.Application.Json)
            setBody(toolCallJson(id = 2, message = "hi"))
        }
        assertEquals(HttpStatusCode.OK, callResp.status)
        Json.parseToJsonElement(callResp.bodyAsText()).jsonObject

        assertEquals(1, audit.toolCalls.size)
        // The audit row's clientLabel is the upgraded label, not "unknown".
        val upgraded = audit.toolCalls.single().clientLabel
        assertEquals("Unknown (#1)", upgraded)

        // And the upgrade was written back to the token store: subsequent
        // listTokens exposes the new label.
        val listed = tokenStore.listTokens("health")
        assertEquals(1, listed.size)
        assertEquals("Unknown (#1)", listed.single().label)
        assertTrue(
            "Upgraded label must not still be the legacy literal",
            listed.none { it.label == "unknown" }
        )
    }

    @Test
    fun `legacy upgrade is stable across sessions for the same client`() = testApplication {
        val registry = InMemoryProviderRegistry().apply {
            register("health", EchoProvider())
            setEnabled("health", true)
        }
        val tokenStore = InMemoryTokenStore()
        val labeler = InMemoryLabeler()

        val tokenA = tokenStore.createTokenPair(
            integrationId = "health",
            clientId = "legacy-A",
            clientName = "unknown"
        ).accessToken
        val tokenB = tokenStore.createTokenPair(
            integrationId = "health",
            clientId = "legacy-B",
            clientName = "unknown"
        ).accessToken

        val audit = RecordingAuditListener()

        application {
            configureMcpRouting(
                registry = registry,
                tokenStore = tokenStore,
                deviceCodeManager = DeviceCodeManager(tokenStore = tokenStore),
                hostname = "test.rousecontext.com",
                integration = "health",
                auditListener = audit,
                unknownClientLabeler = labeler
            )
        }

        // Exercise both clients in order A, B, A again. A must keep #1, B gets #2,
        // A still resolves to #1 on the second session (idempotent).
        suspend fun runToolCall(accessToken: String): String {
            val init = client.post("/mcp") {
                header("Authorization", "Bearer $accessToken")
                contentType(ContentType.Application.Json)
                setBody(initJson())
            }
            val sid = init.headers["Mcp-Session-Id"]!!
            val resp = client.post("/mcp") {
                header("Authorization", "Bearer $accessToken")
                header("Mcp-Session-Id", sid)
                contentType(ContentType.Application.Json)
                setBody(toolCallJson(id = 99, message = "x"))
            }
            Json.parseToJsonElement(resp.bodyAsText()).jsonObject
            return sid
        }

        runToolCall(tokenA)
        runToolCall(tokenB)
        runToolCall(tokenA)

        assertEquals(3, audit.toolCalls.size)
        assertEquals("Unknown (#1)", audit.toolCalls[0].clientLabel)
        assertEquals("Unknown (#2)", audit.toolCalls[1].clientLabel)
        assertEquals("Unknown (#1)", audit.toolCalls[2].clientLabel)
    }

    @Test
    fun `null labeler keeps legacy fallback to literal unknown`() = testApplication {
        // Regression guard: older tests/embed paths that don't wire a labeler
        // should continue to see the current `"unknown"` literal in DCR
        // without NPEing.
        val registry = InMemoryProviderRegistry().apply {
            register("health", EchoProvider())
            setEnabled("health", true)
        }
        val tokenStore = InMemoryTokenStore()

        application {
            configureMcpRouting(
                registry = registry,
                tokenStore = tokenStore,
                deviceCodeManager = DeviceCodeManager(tokenStore = tokenStore),
                hostname = "test.rousecontext.com",
                integration = "health"
                // no unknownClientLabeler supplied
            )
        }

        val resp = client.post("/register") {
            contentType(ContentType.Application.Json)
            setBody("""{"redirect_uris":["https://claude.ai/cb"]}""")
        }
        assertEquals(HttpStatusCode.Created, resp.status)
        val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
        assertEquals("unknown", body["client_name"]?.jsonPrimitive?.content)
    }
}
