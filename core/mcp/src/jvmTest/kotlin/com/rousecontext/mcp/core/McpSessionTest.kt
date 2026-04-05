package com.rousecontext.mcp.core

import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import io.modelcontextprotocol.kotlin.sdk.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.TextContent
import io.modelcontextprotocol.kotlin.sdk.Tool
import io.modelcontextprotocol.kotlin.sdk.server.Server
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class McpSessionTest {

    private class TestProvider : McpServerProvider {
        override val id = "health"
        override val displayName = "Health Connect"

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
        }
    }

    private fun mcpJsonRpc(method: String, params: String? = null, id: Int = 1): String {
        val paramsStr = if (params != null) ""","params":$params""" else ""
        return """{"jsonrpc":"2.0","method":"$method"$paramsStr,"id":$id}"""
    }

    @Test
    fun `HTTP POST with valid Bearer returns MCP initialize response`() = testApplication {
        val registry = InMemoryProviderRegistry()
        val provider = TestProvider()
        registry.register("health", provider)
        registry.setEnabled("health", true)
        val tokenStore = InMemoryTokenStore()
        val token = tokenStore.createToken("health", "test-client")
        val deviceCodeManager = DeviceCodeManager(tokenStore = tokenStore)

        application {
            configureMcpRouting(
                registry = registry,
                tokenStore = tokenStore,
                deviceCodeManager = deviceCodeManager,
                hostname = "test.rousecontext.com"
            )
        }

        val initRequest = mcpJsonRpc(
            "initialize",
            """{"protocolVersion":"2025-03-26","capabilities":{}""" +
                ""","clientInfo":{"name":"test","version":"1.0"}}"""
        )

        val response = client.post("/health/mcp") {
            header("Authorization", "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(initRequest)
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("2.0", json["jsonrpc"]?.jsonPrimitive?.content)
        val result = json["result"]?.jsonObject
        assertTrue(result != null)
        assertTrue(result!!.containsKey("protocolVersion"))
        assertTrue(result.containsKey("serverInfo"))
    }

    @Test
    fun `tools list returns registered tools`() = testApplication {
        val registry = InMemoryProviderRegistry()
        val provider = TestProvider()
        registry.register("health", provider)
        registry.setEnabled("health", true)
        val tokenStore = InMemoryTokenStore()
        val token = tokenStore.createToken("health", "test-client")
        val deviceCodeManager = DeviceCodeManager(tokenStore = tokenStore)

        application {
            configureMcpRouting(
                registry = registry,
                tokenStore = tokenStore,
                deviceCodeManager = deviceCodeManager,
                hostname = "test.rousecontext.com"
            )
        }

        // Initialize first
        val initRequest = mcpJsonRpc(
            "initialize",
            """{"protocolVersion":"2025-03-26","capabilities":{}""" +
                ""","clientInfo":{"name":"test","version":"1.0"}}"""
        )
        client.post("/health/mcp") {
            header("Authorization", "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(initRequest)
        }

        // List tools
        val toolsRequest = mcpJsonRpc("tools/list", id = 2)
        val response = client.post("/health/mcp") {
            header("Authorization", "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(toolsRequest)
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        val result = json["result"]?.jsonObject
        val tools = result?.get("tools")?.jsonArray
        assertTrue(tools != null && tools.size == 1)
        assertEquals("echo", tools!![0].jsonObject["name"]?.jsonPrimitive?.content)
    }

    @Test
    fun `tools call returns correct result`() = testApplication {
        val registry = InMemoryProviderRegistry()
        val provider = TestProvider()
        registry.register("health", provider)
        registry.setEnabled("health", true)
        val tokenStore = InMemoryTokenStore()
        val token = tokenStore.createToken("health", "test-client")
        val deviceCodeManager = DeviceCodeManager(tokenStore = tokenStore)

        application {
            configureMcpRouting(
                registry = registry,
                tokenStore = tokenStore,
                deviceCodeManager = deviceCodeManager,
                hostname = "test.rousecontext.com"
            )
        }

        // Initialize first
        val initRequest = mcpJsonRpc(
            "initialize",
            """{"protocolVersion":"2025-03-26","capabilities":{}""" +
                ""","clientInfo":{"name":"test","version":"1.0"}}"""
        )
        client.post("/health/mcp") {
            header("Authorization", "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(initRequest)
        }

        // Call tool
        val callRequest = mcpJsonRpc(
            "tools/call",
            """{"name":"echo","arguments":{"message":"hello world"}}""",
            id = 3
        )
        val response = client.post("/health/mcp") {
            header("Authorization", "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(callRequest)
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        val result = json["result"]?.jsonObject
        val content = result?.get("content")?.jsonArray
        assertTrue(content != null && content.size == 1)
        assertEquals("hello world", content!![0].jsonObject["text"]?.jsonPrimitive?.content)
    }
}
