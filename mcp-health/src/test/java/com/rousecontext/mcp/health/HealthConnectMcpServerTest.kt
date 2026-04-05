package com.rousecontext.mcp.health

import com.rousecontext.mcp.core.ToolResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class HealthConnectMcpServerTest {

    private lateinit var repository: FakeHealthConnectRepository
    private lateinit var server: HealthConnectMcpServer

    @Before
    fun setUp() {
        repository = FakeHealthConnectRepository()
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
    }
}
