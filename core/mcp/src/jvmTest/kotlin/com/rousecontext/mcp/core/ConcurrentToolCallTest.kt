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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests that multiple rapid-fire tools/call requests are handled correctly
 * without interference between requests. Exercises the shared HttpTransport
 * with many sequential calls to verify no state leaks.
 */
class ConcurrentToolCallTest {

    private class MultiToolProvider : McpServerProvider {
        override val id = "health"
        override val displayName = "Health Connect"

        override fun register(server: Server) {
            server.addTool(
                name = "get_steps",
                description = "Returns step count for a date",
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
                CallToolResult(
                    content = listOf(TextContent("Steps on $date: 8500"))
                )
            }

            server.addTool(
                name = "get_heart_rate",
                description = "Returns heart rate for a date",
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
                CallToolResult(
                    content = listOf(TextContent("Heart rate on $date: 72 bpm"))
                )
            }
        }
    }

    private fun mcpJsonRpc(method: String, params: String? = null, id: Int = 1): String {
        val paramsStr = if (params != null) ""","params":$params""" else ""
        return """{"jsonrpc":"2.0","method":"$method"$paramsStr,"id":$id}"""
    }

    private suspend fun io.ktor.client.HttpClient.mcpPost(
        token: String,
        body: String
    ): io.ktor.client.statement.HttpResponse {
        return post("/mcp") {
            header("Authorization", "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(body)
        }
    }

    private suspend fun io.ktor.client.HttpClient.initialize(token: String) {
        val initRequest = mcpJsonRpc(
            "initialize",
            """{"protocolVersion":"2025-03-26","capabilities":{}""" +
                ""","clientInfo":{"name":"test","version":"1.0"}}"""
        )
        mcpPost(token, initRequest)
    }

    @Test
    fun `many rapid calls to same tool all return correct responses`() = testApplication {
        val registry = InMemoryProviderRegistry()
        registry.register("health", MultiToolProvider())
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

        // Fire 10 rapid requests for the same tool with different dates
        val dates = (1..10).map { "2024-01-%02d".format(it) }
        for ((index, date) in dates.withIndex()) {
            val callRequest = mcpJsonRpc(
                "tools/call",
                """{"name":"get_steps","arguments":{"date":"$date"}}""",
                id = index + 2
            )
            val response = client.mcpPost(token, callRequest)

            assertEquals(
                "Request for $date should succeed",
                HttpStatusCode.OK,
                response.status
            )
            val body = response.bodyAsText()
            val json = Json.parseToJsonElement(body).jsonObject
            val result = json["result"]?.jsonObject
            assertNotNull("Expected result for date $date, got: $body", result)

            val content = result!!["content"]?.jsonArray
            assertNotNull("Expected content for date $date", content)
            assertTrue("Expected non-empty content", content!!.isNotEmpty())

            val text = content[0].jsonObject["text"]?.jsonPrimitive?.content
            assertNotNull("Expected text content for date $date", text)
            assertTrue(
                "Response for $date should contain the date, got: $text",
                text!!.contains(date)
            )
        }
    }

    @Test
    fun `interleaved calls to different tools return correct responses`() = testApplication {
        val registry = InMemoryProviderRegistry()
        registry.register("health", MultiToolProvider())
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

        // Alternate between two different tools
        val tools = listOf(
            "get_steps" to "Steps",
            "get_heart_rate" to "Heart rate",
            "get_steps" to "Steps",
            "get_heart_rate" to "Heart rate",
            "get_steps" to "Steps"
        )

        for ((index, pair) in tools.withIndex()) {
            val (toolName, expectedPrefix) = pair
            val date = "2024-03-%02d".format(index + 1)
            val callRequest = mcpJsonRpc(
                "tools/call",
                """{"name":"$toolName","arguments":{"date":"$date"}}""",
                id = index + 2
            )
            val response = client.mcpPost(token, callRequest)

            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            val json = Json.parseToJsonElement(body).jsonObject
            val text = json["result"]?.jsonObject
                ?.get("content")?.jsonArray
                ?.get(0)?.jsonObject
                ?.get("text")?.jsonPrimitive?.content

            assertNotNull("Expected text for $toolName call $index", text)
            assertTrue(
                "Call to $toolName should return '$expectedPrefix', got: $text",
                text!!.contains(expectedPrefix)
            )
            assertTrue(
                "Call to $toolName should contain date $date, got: $text",
                text.contains(date)
            )
        }
    }

    @Test
    fun `rapid requests from different clients are independent`() = testApplication {
        val registry = InMemoryProviderRegistry()
        registry.register("health", MultiToolProvider())
        registry.setEnabled("health", true)
        val tokenStore = InMemoryTokenStore()
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

        val token1 = tokenStore.createTokenPair("health", "client-1").accessToken
        val token2 = tokenStore.createTokenPair("health", "client-2").accessToken

        client.initialize(token1)
        client.initialize(token2)

        // Alternate requests from two clients
        for (i in 1..5) {
            val date1 = "2024-06-%02d".format(i)
            val date2 = "2024-07-%02d".format(i)

            val r1 = client.mcpPost(
                token1,
                mcpJsonRpc(
                    "tools/call",
                    """{"name":"get_steps","arguments":{"date":"$date1"}}""",
                    id = i + 1
                )
            )
            val r2 = client.mcpPost(
                token2,
                mcpJsonRpc(
                    "tools/call",
                    """{"name":"get_steps","arguments":{"date":"$date2"}}""",
                    id = i + 1
                )
            )

            assertEquals(HttpStatusCode.OK, r1.status)
            assertEquals(HttpStatusCode.OK, r2.status)

            val text1 = Json.parseToJsonElement(r1.bodyAsText()).jsonObject["result"]
                ?.jsonObject?.get("content")?.jsonArray
                ?.get(0)?.jsonObject?.get("text")?.jsonPrimitive?.content
            val text2 = Json.parseToJsonElement(r2.bodyAsText()).jsonObject["result"]
                ?.jsonObject?.get("content")?.jsonArray
                ?.get(0)?.jsonObject?.get("text")?.jsonPrimitive?.content

            assertTrue(
                "Client 1 round $i should get $date1, got: $text1",
                text1!!.contains(date1)
            )
            assertTrue(
                "Client 2 round $i should get $date2, got: $text2",
                text2!!.contains(date2)
            )
        }
    }
}
