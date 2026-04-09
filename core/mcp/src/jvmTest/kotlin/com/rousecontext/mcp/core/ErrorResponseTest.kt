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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class ErrorResponseTest {

    private fun configureTestApp(
        registry: InMemoryProviderRegistry,
        tokenStore: InMemoryTokenStore
    ): io.ktor.server.testing.ApplicationTestBuilder.() -> Unit = {
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
    }

    private fun buildTestEnv(): Triple<InMemoryProviderRegistry, InMemoryTokenStore, String> {
        val provider = object : McpServerProvider {
            override val id = "health"
            override val displayName = "Health Connect"
            override fun register(server: Server) {
                server.addTool(
                    name = "ping",
                    description = "Simple ping"
                ) {
                    CallToolResult(content = listOf(TextContent("pong")))
                }
            }
        }
        val registry = InMemoryProviderRegistry()
        registry.register("health", provider)
        registry.setEnabled("health", true)
        val tokenStore = InMemoryTokenStore()
        val token = tokenStore.createTokenPair("health", "test-client").accessToken
        return Triple(registry, tokenStore, token)
    }

    private suspend fun io.ktor.client.HttpClient.mcpPost(
        token: String,
        body: String
    ): io.ktor.client.statement.HttpResponse {
        return post("/health/mcp") {
            header("Authorization", "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(body)
        }
    }

    private suspend fun io.ktor.client.HttpClient.initialize(token: String) {
        val initRequest = """{"jsonrpc":"2.0","method":"initialize",""" +
            """"params":{"protocolVersion":"2025-03-26","capabilities":{},""" +
            """"clientInfo":{"name":"test","version":"1.0"}},"id":1}"""
        mcpPost(token, initRequest)
    }

    @Test
    fun `malformed JSON returns parse error -32700`() = testApplication {
        val (registry, tokenStore, token) = buildTestEnv()
        configureTestApp(registry, tokenStore).invoke(this)

        val response = client.mcpPost(token, "this is not json{{{")

        assertEquals(HttpStatusCode.OK, response.status)
        val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        val error = json["error"]?.jsonObject
        assertNotNull("Expected JSON-RPC error object", error)
        assertEquals(-32700, error!!["code"]?.jsonPrimitive?.content?.toInt())
    }

    @Test
    fun `dispatchJsonRpc returns internal error for SDK exceptions`() = testApplication {
        val (registry, tokenStore, token) = buildTestEnv()
        configureTestApp(registry, tokenStore).invoke(this)

        client.initialize(token)

        // Send a valid JSON-RPC but with a method the SDK doesn't support
        val request = """{"jsonrpc":"2.0","method":"completely/bogus","params":{},"id":99}"""
        val response = client.mcpPost(token, request)

        assertEquals(HttpStatusCode.OK, response.status)
        val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        val error = json["error"]?.jsonObject
        assertNotNull("Expected JSON-RPC error for unknown method", error)
        // Should be -32601 (method not found) or -32603 (internal error)
        val code = error!!["code"]?.jsonPrimitive?.content?.toInt()
        assertNotNull("Expected error code", code)
    }

    @Test
    fun `JSON-RPC error response includes jsonrpc version`() = testApplication {
        val (registry, tokenStore, token) = buildTestEnv()
        configureTestApp(registry, tokenStore).invoke(this)

        val response = client.mcpPost(token, "not json")

        val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("2.0", json["jsonrpc"]?.jsonPrimitive?.content)
    }
}
