package com.rousecontext.mcp.health

<<<<<<< HEAD
import com.rousecontext.mcp.core.ToolResult
import kotlinx.coroutines.runBlocking
=======
import io.modelcontextprotocol.kotlin.sdk.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.Implementation
import io.modelcontextprotocol.kotlin.sdk.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.TextContent
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
>>>>>>> feat/health-connect-tools
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
<<<<<<< HEAD
=======
import java.time.Instant
>>>>>>> feat/health-connect-tools

class HealthConnectMcpServerTest {

    private lateinit var repository: FakeHealthConnectRepository
<<<<<<< HEAD
    private lateinit var server: HealthConnectMcpServer
=======
    private lateinit var server: Server
    private lateinit var mcpServer: HealthConnectMcpServer
>>>>>>> feat/health-connect-tools

    @Before
    fun setUp() {
        repository = FakeHealthConnectRepository()
<<<<<<< HEAD
        server = HealthConnectMcpServer(repository)
    }

    @Test
    fun `id is health_connect`() {
        assertEquals("health_connect", server.id)
    }

    @Test
    fun `displayName is Health Connect`() {
        assertEquals("Health Connect", server.displayName)
    }

    @Test
    fun `registers get_steps tool`() {
        val names = server.tools().map { it.name }
        assertTrue("get_steps" in names)
    }

    @Test
    fun `registers get_heart_rate tool`() {
        val names = server.tools().map { it.name }
        assertTrue("get_heart_rate" in names)
    }

    @Test
    fun `registers get_sleep tool`() {
        val names = server.tools().map { it.name }
        assertTrue("get_sleep" in names)
    }

    @Test
    fun `get_steps returns step data`() = runBlocking {
        repository.stepsResult = StepsData(totalSteps = 8432, date = "2026-04-03")

        val result = server.callTool("get_steps", mapOf("date" to "2026-04-03"))

        assertTrue(result is ToolResult.Success)
        val content = (result as ToolResult.Success).content
        assertTrue("8432" in content)
        assertTrue("2026-04-03" in content)
    }

    @Test
    fun `get_heart_rate returns heart rate data`() = runBlocking {
        repository.heartRateResult = HeartRateData(
            samples = listOf(
                HeartRateSample(bpm = 72, time = "2026-04-03T10:00:00Z"),
                HeartRateSample(bpm = 85, time = "2026-04-03T10:30:00Z"),
            ),
            date = "2026-04-03",
        )

        val result = server.callTool("get_heart_rate", mapOf("date" to "2026-04-03"))

        assertTrue(result is ToolResult.Success)
        val content = (result as ToolResult.Success).content
        assertTrue("72" in content)
        assertTrue("85" in content)
    }

    @Test
    fun `get_sleep returns sleep data`() = runBlocking {
        repository.sleepResult = SleepData(
            sessions = listOf(
                SleepSession(
                    startTime = "2026-04-02T23:00:00Z",
                    endTime = "2026-04-03T07:00:00Z",
                    durationMinutes = 480,
                ),
            ),
            date = "2026-04-03",
        )

        val result = server.callTool("get_sleep", mapOf("date" to "2026-04-03"))

        assertTrue(result is ToolResult.Success)
        val content = (result as ToolResult.Success).content
        assertTrue("480" in content)
        assertTrue("2026-04-02T23:00:00Z" in content)
    }

    @Test
    fun `unknown tool returns error`() = runBlocking {
        val result = server.callTool("nonexistent_tool", emptyMap())

        assertTrue(result is ToolResult.Error)
        val message = (result as ToolResult.Error).message
        assertTrue("nonexistent_tool" in message)
    }

    @Test
    fun `get_steps with missing date returns error`() = runBlocking {
        val result = server.callTool("get_steps", emptyMap())

        assertTrue(result is ToolResult.Error)
    }

    @Test
    fun `repository failure returns error`() = runBlocking {
        repository.shouldFail = true

        val result = server.callTool("get_steps", mapOf("date" to "2026-04-03"))

        assertTrue(result is ToolResult.Error)
=======
        mcpServer = HealthConnectMcpServer(repository)
        server = Server(
            Implementation("test-server", "1.0.0"),
            ServerOptions(
                capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = null)),
            ),
        )
        mcpServer.register(server)
    }

    @Test
    fun `get_steps returns daily step counts as JSON`() = runBlocking {
        repository.stepsResult = listOf(
            DailySteps(date = "2025-01-01", count = 8000),
            DailySteps(date = "2025-01-02", count = 12000),
        )

        val text = callToolText("get_steps")

        assertTrue(text.contains(""""date":"2025-01-01""""))
        assertTrue(text.contains(""""count":8000"""))
        assertTrue(text.contains(""""date":"2025-01-02""""))
        assertTrue(text.contains(""""count":12000"""))
    }

    @Test
    fun `get_steps passes days parameter to repository`() = runBlocking {
        repository.stepsResult = emptyList()

        callToolText("get_steps", mapOf("days" to JsonPrimitive(3)))

        assertEquals(3, repository.lastDaysRequested)
    }

    @Test
    fun `get_steps defaults to 7 days when no parameter given`() = runBlocking {
        repository.stepsResult = emptyList()

        callToolText("get_steps")

        assertEquals(7, repository.lastDaysRequested)
    }

    @Test
    fun `get_heart_rate returns readings as JSON`() = runBlocking {
        val time = Instant.parse("2025-01-15T10:30:00Z")
        repository.heartRateResult = listOf(
            HeartRateSample(time = time, bpm = 72),
        )

        val text = callToolText("get_heart_rate")

        assertTrue(text.contains(""""bpm":72"""))
        assertTrue(text.contains("2025-01-15T10:30:00Z"))
    }

    @Test
    fun `get_sleep returns sessions with stages as JSON`() = runBlocking {
        val start = Instant.parse("2025-01-15T23:00:00Z")
        val end = Instant.parse("2025-01-16T07:00:00Z")
        repository.sleepResult = listOf(
            SleepSession(
                startTime = start,
                endTime = end,
                durationMinutes = 480,
                stages = listOf(
                    SleepStage(
                        stage = "deep",
                        startTime = start,
                        endTime = Instant.parse("2025-01-16T01:00:00Z"),
                    ),
                ),
            ),
        )

        val text = callToolText("get_sleep")

        assertTrue(text.contains(""""durationMinutes":480"""))
        assertTrue(text.contains(""""stage":"deep""""))
        assertTrue(text.contains("2025-01-15T23:00:00Z"))
    }

    @Test
    fun `get_steps returns empty array when no data`() = runBlocking {
        repository.stepsResult = emptyList()

        val text = callToolText("get_steps")

        assertEquals("[]", text)
    }

    private suspend fun callToolText(
        name: String,
        arguments: Map<String, JsonPrimitive> = emptyMap(),
    ): String {
        val request = CallToolRequest(
            name = name,
            arguments = JsonObject(arguments),
        )
        val result = invokeToolHandler(server, request)
        return (result.content.first() as TextContent).text!!
    }

    /**
     * Invokes the registered tool handler directly via reflection,
     * since the SDK doesn't expose a public dispatch API for testing.
     */
    @Suppress("UNCHECKED_CAST")
    private suspend fun invokeToolHandler(
        server: Server,
        request: CallToolRequest,
    ): io.modelcontextprotocol.kotlin.sdk.CallToolResult {
        val toolsField = Server::class.java.getDeclaredField("tools")
        toolsField.isAccessible = true
        val tools = toolsField.get(server) as Map<String, Any>
        val registeredTool = tools[request.name]
            ?: throw IllegalArgumentException("Unknown tool: ${request.name}")

        val handlerField = registeredTool::class.java.getDeclaredField("handler")
        handlerField.isAccessible = true
        val handler = handlerField.get(registeredTool)
            as suspend (CallToolRequest) -> io.modelcontextprotocol.kotlin.sdk.CallToolResult
        return handler(request)
>>>>>>> feat/health-connect-tools
    }
}
