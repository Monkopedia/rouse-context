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
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Verifies that MCP JSON-RPC responses do NOT contain explicit null values
 * for optional fields (e.g. "annotations":null, "_meta":null).
 *
 * The MCP SDK types have nullable fields like `annotations` and `_meta`.
 * If serialized with explicitNulls=true, these appear as `"annotations":null`
 * in the JSON, which some clients (e.g. Claude) may reject or misparse.
 * Our HttpTransport uses explicitNulls=false to suppress these.
 */
class McpResponseFormatTest {

    private class SimpleProvider : McpServerProvider {
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

    /**
     * Recursively checks that no JSON object in the tree contains a key
     * mapped to JsonNull.
     */
    private fun assertNoExplicitNulls(json: kotlinx.serialization.json.JsonElement, path: String) {
        when (json) {
            is JsonObject -> {
                for ((key, value) in json) {
                    if (value is JsonNull) {
                        throw AssertionError(
                            "Found explicit null at $path.$key -- " +
                                "MCP responses must not contain null-valued fields"
                        )
                    }
                    assertNoExplicitNulls(value, "$path.$key")
                }
            }
            is kotlinx.serialization.json.JsonArray -> {
                json.forEachIndexed { i, element ->
                    assertNoExplicitNulls(element, "$path[$i]")
                }
            }
            else -> { /* primitives are fine */ }
        }
    }

    @Test
    fun `tools call response has no explicit null values`() = testApplication {
        val registry = InMemoryProviderRegistry()
        registry.register("health", SimpleProvider())
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

        // Initialize
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
            """{"name":"echo","arguments":{"message":"hello"}}""",
            id = 2
        )
        val response = client.post("/health/mcp") {
            header("Authorization", "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(callRequest)
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val responseBody = response.bodyAsText()

        // Parse as raw JSON and check for nulls
        val json = Json.parseToJsonElement(responseBody)
        assertNoExplicitNulls(json, "root")

        // Also verify the response string itself does not contain ":null"
        assertFalse(
            "Response JSON should not contain ':null' -- got: $responseBody",
            responseBody.contains(":null")
        )
    }

    @Test
    fun `tools list response has no explicit null values`() = testApplication {
        val registry = InMemoryProviderRegistry()
        registry.register("health", SimpleProvider())
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

        // Initialize
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
        val listRequest = mcpJsonRpc("tools/list", id = 2)
        val response = client.post("/health/mcp") {
            header("Authorization", "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(listRequest)
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val responseBody = response.bodyAsText()

        val json = Json.parseToJsonElement(responseBody)
        assertNoExplicitNulls(json, "root")
    }

    @Test
    fun `initialize response has no explicit null values`() = testApplication {
        val registry = InMemoryProviderRegistry()
        registry.register("health", SimpleProvider())
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
        val responseBody = response.bodyAsText()

        val json = Json.parseToJsonElement(responseBody)
        assertNoExplicitNulls(json, "root")

        // Verify the response is still valid JSON-RPC
        val jsonObj = json.jsonObject
        assertNotNull("Should have result", jsonObj["result"])
    }
}
