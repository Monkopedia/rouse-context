package com.rousecontext.integrations.health

import io.modelcontextprotocol.kotlin.sdk.server.ClientConnection
import io.modelcontextprotocol.kotlin.sdk.server.RegisteredTool
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class HealthConnectMcpServerTest {

    private lateinit var repo: FakeHealthConnectRepository
    private lateinit var mcpServer: HealthConnectMcpServer
    private lateinit var toolHandlers: Map<String, RegisteredTool>

    @Before
    fun setUp() {
        repo = FakeHealthConnectRepository()
        mcpServer = HealthConnectMcpServer(repo)

        val server = Server(
            Implementation("test-server", "0.1.0"),
            ServerOptions(
                capabilities = ServerCapabilities(
                    tools = ServerCapabilities.Tools(listChanged = null)
                )
            )
        )
        mcpServer.register(server)

        toolHandlers = server.tools
    }

    private suspend fun callTool(
        name: String,
        arguments: JsonObject = buildJsonObject {}
    ): CallToolResult {
        val registered = toolHandlers[name]
            ?: throw AssertionError("No handler for tool: $name. Available: ${toolHandlers.keys}")

        val request = CallToolRequest(
            params = io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams(
                name = name,
                arguments = arguments
            )
        )
        val noOpConnection = java.lang.reflect.Proxy.newProxyInstance(
            ClientConnection::class.java.classLoader,
            arrayOf(ClientConnection::class.java)
        ) { _, _, _ -> throw UnsupportedOperationException() } as ClientConnection
        return registered.handler.invoke(noOpConnection, request)
    }

    // --- list_record_types ---

    @Test
    fun `list_record_types returns all types with permission status`() = runBlocking {
        repo.grantedPermissions.addAll(listOf("Steps", "HeartRate"))

        val result = callTool("list_record_types")
        val text = (result.content.first() as TextContent).text!!
        val types = Json.parseToJsonElement(text).jsonArray

        assertTrue("Should have multiple types", types.size > 2)

        val stepsType = types.first {
            it.jsonObject["type"]?.jsonPrimitive?.content == "Steps"
        }
        assertEquals(
            "true",
            stepsType.jsonObject["has_permission"]!!.jsonPrimitive.content
        )
        assertEquals(
            "activity",
            stepsType.jsonObject["category"]!!.jsonPrimitive.content
        )

        val weightType = types.first {
            it.jsonObject["type"]?.jsonPrimitive?.content == "Weight"
        }
        assertEquals(
            "false",
            weightType.jsonObject["has_permission"]!!.jsonPrimitive.content
        )
    }

    // --- query_health_data ---

    @Test
    fun `query_health_data returns records for valid type`() = runBlocking {
        val stepsRecord = buildJsonObject {
            put("start_time", "2026-04-01T00:00:00Z")
            put("end_time", "2026-04-01T23:59:59Z")
            put("count", 8500)
        }
        repo.records["Steps"] = listOf(stepsRecord)

        val result = callTool(
            "query_health_data",
            buildJsonObject {
                put("record_type", "Steps")
                put("since", "2026-04-01")
            }
        )

        val text = (result.content.first() as TextContent).text!!
        val json = Json.parseToJsonElement(text).jsonObject
        assertEquals("Steps", json["record_type"]!!.jsonPrimitive.content)
        assertEquals(1, json["count"]!!.jsonPrimitive.int)
        val records = json["records"]!!.jsonArray
        assertEquals(8500, records[0].jsonObject["count"]!!.jsonPrimitive.int)
    }

    @Test
    fun `query_health_data respects limit`() = runBlocking {
        val records = (1..5).map { i ->
            buildJsonObject {
                put("start_time", "2026-04-0${i}T00:00:00Z")
                put("count", i * 1000)
            }
        }
        repo.records["Steps"] = records

        val result = callTool(
            "query_health_data",
            buildJsonObject {
                put("record_type", "Steps")
                put("since", "2026-04-01")
                put("limit", 2)
            }
        )

        val text = (result.content.first() as TextContent).text!!
        val json = Json.parseToJsonElement(text).jsonObject
        assertEquals(2, json["count"]!!.jsonPrimitive.int)
    }

    @Test
    fun `query_health_data returns error for unknown type`() = runBlocking {
        val result = callTool(
            "query_health_data",
            buildJsonObject {
                put("record_type", "FakeType")
                put("since", "2026-04-01")
            }
        )

        assertTrue("Should be error", result.isError == true)
        val text = (result.content.first() as TextContent).text!!
        assertTrue(text.contains("Unknown record type"))
    }

    @Test
    fun `query_health_data returns error for invalid date`() = runBlocking {
        val result = callTool(
            "query_health_data",
            buildJsonObject {
                put("record_type", "Steps")
                put("since", "not-a-date")
            }
        )

        assertTrue("Should be error", result.isError == true)
        val text = (result.content.first() as TextContent).text!!
        assertTrue(text.contains("Invalid"))
    }

    @Test
    fun `query_health_data accepts ISO datetime`() = runBlocking {
        repo.records["HeartRate"] = listOf(
            buildJsonObject {
                put("time", "2026-04-03T10:30:00Z")
                put("bpm", 72)
            }
        )

        val result = callTool(
            "query_health_data",
            buildJsonObject {
                put("record_type", "HeartRate")
                put("since", "2026-04-03T00:00:00Z")
                put("until", "2026-04-04T00:00:00Z")
            }
        )

        val text = (result.content.first() as TextContent).text!!
        val json = Json.parseToJsonElement(text).jsonObject
        assertEquals(1, json["count"]!!.jsonPrimitive.int)
    }

    // --- get_health_summary ---

    @Test
    fun `get_health_summary returns summary for valid period`() = runBlocking {
        repo.summaryResponse = buildJsonObject {
            put("steps_total", 42000)
            put("avg_heart_rate", 68)
            put("sleep_hours", 7.5)
        }

        val result = callTool(
            "get_health_summary",
            buildJsonObject { put("period", "week") }
        )

        val text = (result.content.first() as TextContent).text!!
        val json = Json.parseToJsonElement(text).jsonObject
        assertEquals(42000, json["steps_total"]!!.jsonPrimitive.int)
    }

    @Test
    fun `get_health_summary returns error for invalid period`() = runBlocking {
        val result = callTool(
            "get_health_summary",
            buildJsonObject { put("period", "year") }
        )

        assertTrue("Should be error", result.isError == true)
        val text = (result.content.first() as TextContent).text!!
        assertTrue(text.contains("Invalid period"))
    }

    // --- parseInstant ---

    @Test
    fun `parseInstant handles ISO datetime`() {
        val instant = HealthConnectMcpServer.parseInstant("2026-04-01T12:00:00Z")
        assertNotNull(instant)
    }

    @Test
    fun `parseInstant handles date only`() {
        val instant = HealthConnectMcpServer.parseInstant("2026-04-01")
        assertNotNull(instant)
    }

    @Test
    fun `parseInstant returns null for garbage`() {
        assertEquals(null, HealthConnectMcpServer.parseInstant("not-a-date"))
    }

    @Test
    fun `parseInstant returns null for null input`() {
        assertEquals(null, HealthConnectMcpServer.parseInstant(null))
    }

    // --- registration ---

    @Test
    fun `registers all three tools`() {
        assertEquals(
            setOf("list_record_types", "query_health_data", "get_health_summary"),
            toolHandlers.keys
        )
    }
}
