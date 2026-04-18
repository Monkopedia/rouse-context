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
import io.modelcontextprotocol.kotlin.sdk.types.ReadResourceResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.TextResourceContents
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MCP protocol scenarios 109-113 from docs/design/overall.md.
 *
 * Scenarios 105-108 are already covered by HttpRoutingTest and McpSessionTest.
 */
class McpProtocolTest {

    /**
     * Provider that registers both tools and resources for protocol testing.
     */
    private class FullProvider : McpServerProvider {
        override val id = "health"
        override val displayName = "Health Connect"

        override fun register(server: Server) {
            server.addTool(
                name = "get_steps",
                description = "Returns step count",
                inputSchema = ToolSchema(
                    properties = buildJsonObject {
                        put(
                            "date",
                            buildJsonObject {
                                put("type", JsonPrimitive("string"))
                            }
                        )
                    },
                    required = listOf("date")
                )
            ) { request ->
                val date = request.params.arguments?.get("date")
                    ?.jsonPrimitive?.content ?: "unknown"
                CallToolResult(content = listOf(TextContent("Steps on $date: 8500")))
            }

            server.addResource(
                uri = "health://profile",
                name = "Health Profile",
                description = "User health profile summary",
                mimeType = "application/json"
            ) { _ ->
                ReadResourceResult(
                    contents = listOf(
                        TextResourceContents(
                            text = """{"height_cm":180,"weight_kg":75}""",
                            uri = "health://profile",
                            mimeType = "application/json"
                        )
                    )
                )
            }
        }
    }

    private fun mcpJsonRpc(method: String, params: String? = null, id: Int = 1): String {
        val paramsStr = if (params != null) ""","params":$params""" else ""
        return """{"jsonrpc":"2.0","method":"$method"$paramsStr,"id":$id}"""
    }

    private fun configureTestApp(
        registry: InMemoryProviderRegistry,
        tokenStore: InMemoryTokenStore,
        auditListener: AuditListener? = null,
        clock: Clock = SystemClock
    ): io.ktor.server.testing.ApplicationTestBuilder.() -> Unit = {
        val deviceCodeManager = DeviceCodeManager(tokenStore = tokenStore)
        application {
            configureMcpRouting(
                registry = registry,
                tokenStore = tokenStore,
                deviceCodeManager = deviceCodeManager,
                hostname = "test.rousecontext.com",
                integration = "health",
                auditListener = auditListener,
                clock = clock
            )
        }
    }

    private fun buildTestEnv(): Triple<InMemoryProviderRegistry, InMemoryTokenStore, String> {
        val registry = InMemoryProviderRegistry()
        registry.register("health", FullProvider())
        registry.setEnabled("health", true)
        val tokenStore = InMemoryTokenStore()
        val token = tokenStore.createTokenPair("health", "test-client").accessToken
        return Triple(registry, tokenStore, token)
    }

    /** Session id holder, set by [initialize] and used by [mcpPost]. */
    private var currentSessionId: String? = null

    private suspend fun io.ktor.client.HttpClient.mcpPost(
        token: String,
        body: String
    ): io.ktor.client.statement.HttpResponse = post("/mcp") {
        header("Authorization", "Bearer $token")
        currentSessionId?.let { header("Mcp-Session-Id", it) }
        contentType(ContentType.Application.Json)
        setBody(body)
    }

    /**
     * Like [mcpPost] but with an explicit [sessionId], so a test can route
     * requests across multiple sessions without relying on the shared
     * [currentSessionId] holder.
     */
    private suspend fun io.ktor.client.HttpClient.mcpPostWith(
        token: String,
        sessionId: String,
        body: String
    ): io.ktor.client.statement.HttpResponse = post("/mcp") {
        header("Authorization", "Bearer $token")
        header("Mcp-Session-Id", sessionId)
        contentType(ContentType.Application.Json)
        setBody(body)
    }

    private suspend fun io.ktor.client.HttpClient.initialize(token: String): String {
        val initRequest = mcpJsonRpc(
            "initialize",
            """{"protocolVersion":"2025-03-26","capabilities":{}""" +
                ""","clientInfo":{"name":"test","version":"1.0"}}"""
        )
        // Don't send Mcp-Session-Id on initialize (creates a new session)
        val prevSessionId = currentSessionId
        currentSessionId = null
        val response = mcpPost(token, initRequest)
        currentSessionId = response.headers["Mcp-Session-Id"] ?: prevSessionId
        return response.bodyAsText()
    }

    // -- Scenario 109: tools/call with unknown tool name --

    @Test
    fun `tools call with unknown tool name returns MCP error`() = testApplication {
        val (registry, tokenStore, token) = buildTestEnv()
        configureTestApp(registry, tokenStore).invoke(this)

        client.initialize(token)

        val callRequest = mcpJsonRpc(
            "tools/call",
            """{"name":"nonexistent_tool","arguments":{}}""",
            id = 2
        )
        val response = client.mcpPost(token, callRequest)

        assertEquals(HttpStatusCode.OK, response.status)
        val responseBody = response.bodyAsText()
        val json = Json.parseToJsonElement(responseBody).jsonObject

        // The SDK may return a JSON-RPC error object or a result with isError=true.
        // Check both forms.
        val errorField = json["error"]
        val hasJsonRpcError = errorField != null &&
            errorField !is kotlinx.serialization.json.JsonNull
        val result = json["result"]
        val hasResultError = result != null &&
            result !is kotlinx.serialization.json.JsonNull &&
            result.jsonObject["isError"]?.jsonPrimitive?.content == "true"
        assertTrue(
            "Expected error for unknown tool, got: $responseBody",
            hasJsonRpcError || hasResultError
        )
    }

    // -- Scenario 110: resources/list --

    @Test
    fun `resources list returns registered resources`() = testApplication {
        val (registry, tokenStore, token) = buildTestEnv()
        configureTestApp(registry, tokenStore).invoke(this)

        client.initialize(token)

        val listRequest = mcpJsonRpc("resources/list", id = 2)
        val response = client.mcpPost(token, listRequest)

        assertEquals(HttpStatusCode.OK, response.status)
        val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        val result = json["result"]?.jsonObject
        assertNotNull("Expected result in response", result)

        val resources = result!!["resources"]?.jsonArray
        assertNotNull("Expected resources array", resources)
        assertTrue("Expected at least one resource", resources!!.isNotEmpty())

        val resource = resources[0].jsonObject
        assertEquals("health://profile", resource["uri"]?.jsonPrimitive?.content)
        assertEquals("Health Profile", resource["name"]?.jsonPrimitive?.content)
    }

    @Test
    fun `resources list with no resources returns empty list`() = testApplication {
        // Provider with tools only, no resources
        val emptyResourceProvider = object : McpServerProvider {
            override val id = "health"
            override val displayName = "Health Connect"
            override fun register(server: Server) {
                server.addTool(
                    name = "ping",
                    description = "Ping"
                ) {
                    CallToolResult(content = listOf(TextContent("pong")))
                }
            }
        }

        val registry = InMemoryProviderRegistry()
        registry.register("health", emptyResourceProvider)
        registry.setEnabled("health", true)
        val tokenStore = InMemoryTokenStore()
        val token = tokenStore.createTokenPair("health", "test-client").accessToken
        val deviceCodeManager = DeviceCodeManager(tokenStore = tokenStore)

        application {
            configureMcpRouting(
                registry = registry,
                tokenStore = tokenStore,
                deviceCodeManager = deviceCodeManager,
                hostname = "test.rousecontext.com",
                integration = "health"
            )
        }

        client.initialize(token)

        val listRequest = mcpJsonRpc("resources/list", id = 2)
        val response = client.mcpPost(token, listRequest)

        assertEquals(HttpStatusCode.OK, response.status)
        val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        val result = json["result"]?.jsonObject
        assertNotNull("Expected result", result)

        val resources = result!!["resources"]?.jsonArray
        assertNotNull("Expected resources array", resources)
        assertEquals("Expected empty resources list", 0, resources!!.size)
    }

    // -- Scenario 111: resources/read --

    @Test
    fun `resources read returns content for valid URI`() = testApplication {
        val (registry, tokenStore, token) = buildTestEnv()
        configureTestApp(registry, tokenStore).invoke(this)

        client.initialize(token)

        val readRequest = mcpJsonRpc(
            "resources/read",
            """{"uri":"health://profile"}""",
            id = 2
        )
        val response = client.mcpPost(token, readRequest)

        assertEquals(HttpStatusCode.OK, response.status)
        val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        val result = json["result"]?.jsonObject
        assertNotNull("Expected result", result)

        val contents = result!!["contents"]?.jsonArray
        assertNotNull("Expected contents array", contents)
        assertTrue("Expected at least one content entry", contents!!.isNotEmpty())

        val content = contents[0].jsonObject
        assertEquals("health://profile", content["uri"]?.jsonPrimitive?.content)
        val text = content["text"]?.jsonPrimitive?.content
        assertNotNull("Expected text content", text)
        assertTrue("Expected JSON content", text!!.contains("height_cm"))
    }

    // -- Scenario 112: multiple sequential requests on same session --

    @Test
    fun `multiple sequential requests on same session all succeed`() = testApplication {
        val (registry, tokenStore, token) = buildTestEnv()
        configureTestApp(registry, tokenStore).invoke(this)

        client.initialize(token)

        // Request 1: tools/list
        val toolsResponse = client.mcpPost(token, mcpJsonRpc("tools/list", id = 2))
        assertEquals(HttpStatusCode.OK, toolsResponse.status)
        val toolsJson = Json.parseToJsonElement(toolsResponse.bodyAsText()).jsonObject
        assertNotNull("tools/list should return result", toolsJson["result"])

        // Request 2: resources/list
        val resourcesResponse = client.mcpPost(token, mcpJsonRpc("resources/list", id = 3))
        assertEquals(HttpStatusCode.OK, resourcesResponse.status)
        val resourcesJson = Json.parseToJsonElement(resourcesResponse.bodyAsText()).jsonObject
        assertNotNull("resources/list should return result", resourcesJson["result"])

        // Request 3: tools/call
        val callRequest = mcpJsonRpc(
            "tools/call",
            """{"name":"get_steps","arguments":{"date":"2024-01-15"}}""",
            id = 4
        )
        val callResponse = client.mcpPost(token, callRequest)
        assertEquals(HttpStatusCode.OK, callResponse.status)
        val callJson = Json.parseToJsonElement(callResponse.bodyAsText()).jsonObject
        val callResult = callJson["result"]?.jsonObject
        val callContent = callResult?.get("content")?.jsonArray
        assertTrue(
            "tools/call should return content",
            callContent != null && callContent.isNotEmpty()
        )

        // Request 4: resources/read
        val readRequest = mcpJsonRpc(
            "resources/read",
            """{"uri":"health://profile"}""",
            id = 5
        )
        val readResponse = client.mcpPost(token, readRequest)
        assertEquals(HttpStatusCode.OK, readResponse.status)
        val readJson = Json.parseToJsonElement(readResponse.bodyAsText()).jsonObject
        assertNotNull("resources/read should return result", readJson["result"])

        // Request 5: another tools/call to confirm session is still healthy
        val callRequest2 = mcpJsonRpc(
            "tools/call",
            """{"name":"get_steps","arguments":{"date":"2024-01-16"}}""",
            id = 6
        )
        val callResponse2 = client.mcpPost(token, callRequest2)
        assertEquals(HttpStatusCode.OK, callResponse2.status)
        val callJson2 = Json.parseToJsonElement(callResponse2.bodyAsText()).jsonObject
        assertNotNull("Second tools/call should return result", callJson2["result"])
    }

    // -- Scenario 113: concurrent requests from different clients --

    @Test
    fun `concurrent requests from different clients use independent sessions`() = testApplication {
        val (registry, tokenStore, _) = buildTestEnv()
        configureTestApp(registry, tokenStore).invoke(this)

        // Create two separate client tokens
        val token1 = tokenStore.createTokenPair("health", "client-1").accessToken
        val token2 = tokenStore.createTokenPair("health", "client-2").accessToken

        // Initialize both sessions. Issue #206: sessions are bound to the
        // owning client id, so each client must use its own session header.
        client.initialize(token1)
        val session1 = currentSessionId!!
        client.initialize(token2)
        val session2 = currentSessionId!!

        // Both clients call tools/list -- both should succeed independently,
        // each scoped to its own session.
        val response1 = client.mcpPostWith(token1, session1, mcpJsonRpc("tools/list", id = 2))
        val response2 = client.mcpPostWith(token2, session2, mcpJsonRpc("tools/list", id = 2))

        assertEquals(HttpStatusCode.OK, response1.status)
        assertEquals(HttpStatusCode.OK, response2.status)

        val json1 = Json.parseToJsonElement(response1.bodyAsText()).jsonObject
        val json2 = Json.parseToJsonElement(response2.bodyAsText()).jsonObject

        val tools1 = json1["result"]?.jsonObject?.get("tools")?.jsonArray
        val tools2 = json2["result"]?.jsonObject?.get("tools")?.jsonArray

        assertNotNull("Client 1 should see tools", tools1)
        assertNotNull("Client 2 should see tools", tools2)
        assertEquals(
            "Both clients should see same tool count",
            tools1!!.size,
            tools2!!.size
        )

        // Both clients call tools independently
        val call1 = client.mcpPostWith(
            token1,
            session1,
            mcpJsonRpc(
                "tools/call",
                """{"name":"get_steps","arguments":{"date":"2024-01-15"}}""",
                id = 3
            )
        )
        val call2 = client.mcpPostWith(
            token2,
            session2,
            mcpJsonRpc(
                "tools/call",
                """{"name":"get_steps","arguments":{"date":"2024-02-20"}}""",
                id = 3
            )
        )

        assertEquals(HttpStatusCode.OK, call1.status)
        assertEquals(HttpStatusCode.OK, call2.status)

        val result1 = Json.parseToJsonElement(call1.bodyAsText()).jsonObject
        val result2 = Json.parseToJsonElement(call2.bodyAsText()).jsonObject

        val text1 = result1["result"]?.jsonObject
            ?.get("content")?.jsonArray
            ?.get(0)?.jsonObject
            ?.get("text")?.jsonPrimitive?.content
        val text2 = result2["result"]?.jsonObject
            ?.get("content")?.jsonArray
            ?.get(0)?.jsonObject
            ?.get("text")?.jsonPrimitive?.content

        assertTrue(
            "Client 1 should get 2024-01-15 result",
            text1?.contains("2024-01-15") == true
        )
        assertTrue(
            "Client 2 should get 2024-02-20 result",
            text2?.contains("2024-02-20") == true
        )
    }

    // -- Audit logging tests --

    @Test
    fun `tool call emits audit event with correct fields`() = testApplication {
        val (registry, tokenStore, token) = buildTestEnv()
        val events = mutableListOf<ToolCallEvent>()
        val clock = FakeClock(1000L)
        configureTestApp(
            registry,
            tokenStore,
            auditListener = object : AuditListener {
                override suspend fun onToolCall(event: ToolCallEvent) {
                    events.add(event)
                }
            },
            clock = clock
        ).invoke(this)

        client.initialize(token)

        val callRequest = mcpJsonRpc(
            "tools/call",
            """{"name":"get_steps","arguments":{"date":"2024-01-15"}}""",
            id = 2
        )
        val response = client.mcpPost(token, callRequest)

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("Expected exactly one audit event", 1, events.size)

        val event = events[0]
        assertEquals("get_steps", event.toolName)
        assertEquals("health", event.providerId)
        assertEquals("health", event.sessionId)
        assertEquals(1000L, event.timestamp)
        assertTrue("Arguments should contain date key", event.arguments.containsKey("date"))
        assertNotNull("Result should not be null", event.result)
    }

    @Test
    fun `non-tool-call methods do not emit audit events`() = testApplication {
        val (registry, tokenStore, token) = buildTestEnv()
        val events = mutableListOf<ToolCallEvent>()
        configureTestApp(
            registry,
            tokenStore,
            auditListener = object : AuditListener {
                override suspend fun onToolCall(event: ToolCallEvent) {
                    events.add(event)
                }
            }
        ).invoke(this)

        client.initialize(token)

        // tools/list should NOT trigger audit
        client.mcpPost(token, mcpJsonRpc("tools/list", id = 2))
        // resources/list should NOT trigger audit
        client.mcpPost(token, mcpJsonRpc("resources/list", id = 3))

        assertEquals("No audit events for non-tool-call methods", 0, events.size)
    }

    @Test
    fun `multiple tool calls emit multiple audit events`() = testApplication {
        val (registry, tokenStore, token) = buildTestEnv()
        val events = mutableListOf<ToolCallEvent>()
        configureTestApp(
            registry,
            tokenStore,
            auditListener = object : AuditListener {
                override suspend fun onToolCall(event: ToolCallEvent) {
                    events.add(event)
                }
            }
        ).invoke(this)

        client.initialize(token)

        client.mcpPost(
            token,
            mcpJsonRpc(
                "tools/call",
                """{"name":"get_steps","arguments":{"date":"2024-01-15"}}""",
                id = 2
            )
        )
        client.mcpPost(
            token,
            mcpJsonRpc(
                "tools/call",
                """{"name":"get_steps","arguments":{"date":"2024-01-16"}}""",
                id = 3
            )
        )

        assertEquals("Expected two audit events", 2, events.size)
        assertEquals("get_steps", events[0].toolName)
        assertEquals("get_steps", events[1].toolName)
    }

    @Test
    fun `audit event records integration from Host header not default`() = testApplication {
        val registry = InMemoryProviderRegistry()
        registry.register("health", FullProvider())
        registry.setEnabled("health", true)
        registry.register(
            "notifications",
            object : McpServerProvider {
                override val id = "notifications"
                override val displayName = "Notifications"
                override fun register(server: Server) {
                    server.addTool(
                        name = "send_notification",
                        description = "Send a notification",
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
                        CallToolResult(content = listOf(TextContent("sent")))
                    }
                }
            }
        )
        registry.setEnabled("notifications", true)

        val tokenStore = InMemoryTokenStore()
        // Create tokens for both integrations
        tokenStore.createTokenPair("health", "test-client")
        val notifToken = tokenStore.createTokenPair("notifications", "test-client").accessToken

        val events = mutableListOf<ToolCallEvent>()

        val deviceCodeManager = DeviceCodeManager(tokenStore = tokenStore)
        application {
            configureMcpRouting(
                registry = registry,
                tokenStore = tokenStore,
                deviceCodeManager = deviceCodeManager,
                hostname = "brave-health.abc123.rousecontext.com",
                integration = "health",
                auditListener = object : AuditListener {
                    override suspend fun onToolCall(event: ToolCallEvent) {
                        events.add(event)
                    }
                }
            )
        }

        // Initialize with notifications Host header
        val initRequest = mcpJsonRpc(
            "initialize",
            """{"protocolVersion":"2025-03-26","capabilities":{}""" +
                ""","clientInfo":{"name":"test","version":"1.0"}}"""
        )
        val initResp = client.post("/mcp") {
            header("Authorization", "Bearer $notifToken")
            header("Host", "brave-notifications.abc123.rousecontext.com")
            contentType(ContentType.Application.Json)
            setBody(initRequest)
        }
        val sessionId = initResp.headers["Mcp-Session-Id"]!!

        // Tool call with notifications Host header
        val callRequest = mcpJsonRpc(
            "tools/call",
            """{"name":"send_notification","arguments":{"message":"hello"}}""",
            id = 2
        )
        val response = client.post("/mcp") {
            header("Authorization", "Bearer $notifToken")
            header("Host", "brave-notifications.abc123.rousecontext.com")
            header("Mcp-Session-Id", sessionId)
            contentType(ContentType.Application.Json)
            setBody(callRequest)
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("Expected exactly one audit event", 1, events.size)

        val event = events[0]
        assertEquals("send_notification", event.toolName)
        // The audit event must record "notifications", NOT the default "health"
        assertEquals("notifications", event.providerId)
        assertEquals("notifications", event.sessionId)
    }

    @Test
    fun `null audit listener does not break tool calls`() = testApplication {
        val (registry, tokenStore, token) = buildTestEnv()
        configureTestApp(registry, tokenStore, auditListener = null).invoke(this)

        client.initialize(token)

        val callRequest = mcpJsonRpc(
            "tools/call",
            """{"name":"get_steps","arguments":{"date":"2024-01-15"}}""",
            id = 2
        )
        val response = client.mcpPost(token, callRequest)

        assertEquals(HttpStatusCode.OK, response.status)
        val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        val result = json["result"]?.jsonObject
        assertNotNull("Tool call should still succeed without audit listener", result)
    }

    // -- onRequest audit coverage (issue #105) --

    private class CollectingAuditListener : AuditListener {
        val toolCalls = mutableListOf<ToolCallEvent>()
        val requests = mutableListOf<McpRequestEvent>()
        override suspend fun onToolCall(event: ToolCallEvent) {
            toolCalls.add(event)
        }
        override suspend fun onRequest(event: McpRequestEvent) {
            requests.add(event)
        }
    }

    @Test
    fun `onRequest fires for every JSON-RPC method`() = testApplication {
        val (registry, tokenStore, token) = buildTestEnv()
        val listener = CollectingAuditListener()
        configureTestApp(registry, tokenStore, auditListener = listener).invoke(this)

        client.initialize(token)
        client.mcpPost(token, mcpJsonRpc("tools/list", id = 2))
        client.mcpPost(token, mcpJsonRpc("resources/list", id = 3))
        client.mcpPost(
            token,
            mcpJsonRpc("resources/read", """{"uri":"health://profile"}""", id = 4)
        )
        client.mcpPost(
            token,
            mcpJsonRpc(
                "tools/call",
                """{"name":"get_steps","arguments":{"date":"2024-01-15"}}""",
                id = 5
            )
        )

        val methods = listener.requests.map { it.method }
        assertTrue("initialize should fire onRequest, got $methods", "initialize" in methods)
        assertTrue("tools/list should fire onRequest, got $methods", "tools/list" in methods)
        assertTrue(
            "resources/list should fire onRequest, got $methods",
            "resources/list" in methods
        )
        assertTrue(
            "resources/read should fire onRequest, got $methods",
            "resources/read" in methods
        )
        assertTrue("tools/call should fire onRequest, got $methods", "tools/call" in methods)

        // Every request carried provider + session
        listener.requests.forEach { event ->
            assertEquals("health", event.providerId)
            assertEquals("health", event.sessionId)
        }
    }

    @Test
    fun `tools call fires both onRequest and onToolCall`() = testApplication {
        val (registry, tokenStore, token) = buildTestEnv()
        val listener = CollectingAuditListener()
        configureTestApp(registry, tokenStore, auditListener = listener).invoke(this)

        client.initialize(token)
        client.mcpPost(
            token,
            mcpJsonRpc(
                "tools/call",
                """{"name":"get_steps","arguments":{"date":"2024-01-15"}}""",
                id = 2
            )
        )

        // onToolCall fires exactly once for tools/call
        assertEquals(1, listener.toolCalls.size)
        // onRequest fires for initialize AND tools/call
        val toolCallRequests = listener.requests.filter { it.method == "tools/call" }
        assertEquals(
            "tools/call should fire onRequest exactly once",
            1,
            toolCallRequests.size
        )
    }

    @Test
    fun `onRequest captures method params and duration`() = testApplication {
        val (registry, tokenStore, token) = buildTestEnv()
        val listener = CollectingAuditListener()
        val clock = FakeClock(1000L)
        configureTestApp(
            registry,
            tokenStore,
            auditListener = listener,
            clock = clock
        ).invoke(this)

        client.initialize(token)
        client.mcpPost(
            token,
            mcpJsonRpc("resources/read", """{"uri":"health://profile"}""", id = 2)
        )

        val readRequest = listener.requests.firstOrNull { it.method == "resources/read" }
        assertNotNull("Expected resources/read onRequest event", readRequest)
        val params = readRequest!!.params
        assertNotNull("params should be captured", params)
        // duration is best-effort non-negative
        assertTrue("durationMs non-negative", readRequest.durationMs >= 0)
        // resultBytes is captured for successful responses
        assertNotNull("resultBytes should be captured", readRequest.resultBytes)
        assertTrue("resultBytes > 0 for non-empty response", readRequest.resultBytes!! > 0)
    }

    @Test
    fun `onRequest does not fire onToolCall for non-tool methods`() = testApplication {
        val (registry, tokenStore, token) = buildTestEnv()
        val listener = CollectingAuditListener()
        configureTestApp(registry, tokenStore, auditListener = listener).invoke(this)

        client.initialize(token)
        client.mcpPost(token, mcpJsonRpc("tools/list", id = 2))
        client.mcpPost(token, mcpJsonRpc("resources/list", id = 3))

        assertEquals(
            "onToolCall must not fire for non-tool-call methods",
            0,
            listener.toolCalls.size
        )
        assertTrue(
            "onRequest must fire for list methods",
            listener.requests.any { it.method == "tools/list" } &&
                listener.requests.any { it.method == "resources/list" }
        )
    }
}
