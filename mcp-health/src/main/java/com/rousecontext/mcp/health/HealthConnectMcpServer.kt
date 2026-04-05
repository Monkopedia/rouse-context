package com.rousecontext.mcp.health

import com.rousecontext.mcp.core.McpServerProvider
import com.rousecontext.mcp.core.ToolDefinition
import com.rousecontext.mcp.core.ToolResult

/**
 * Health Connect MCP server implementation.
 * Exposes Health Connect data via MCP tools: get_steps, get_heart_rate, get_sleep.
 */
class HealthConnectMcpServer(
    private val repository: HealthConnectRepository,
) : McpServerProvider {

    override val id: String = "health_connect"
    override val displayName: String = "Health Connect"

    override fun tools(): List<ToolDefinition> = listOf(
        ToolDefinition(
            name = "get_steps",
            description = "Get step count for a given date.",
            parameters = mapOf("date" to "Date in yyyy-MM-dd format"),
        ),
        ToolDefinition(
            name = "get_heart_rate",
            description = "Get heart rate samples for a given date.",
            parameters = mapOf("date" to "Date in yyyy-MM-dd format"),
        ),
        ToolDefinition(
            name = "get_sleep",
            description = "Get sleep sessions for a given date.",
            parameters = mapOf("date" to "Date in yyyy-MM-dd format"),
        ),
    )

    override suspend fun callTool(name: String, arguments: Map<String, String>): ToolResult {
        if (name !in TOOL_NAMES) {
            return ToolResult.Error("Unknown tool: $name")
        }

        val date = arguments["date"]
            ?: return ToolResult.Error("Missing required parameter: date")

        return try {
            when (name) {
                "get_steps" -> handleGetSteps(date)
                "get_heart_rate" -> handleGetHeartRate(date)
                "get_sleep" -> handleGetSleep(date)
                else -> ToolResult.Error("Unknown tool: $name")
            }
        } catch (e: HealthConnectUnavailableException) {
            ToolResult.Error("Health Connect unavailable: ${e.message}")
        }
    }

    private companion object {
        val TOOL_NAMES = setOf("get_steps", "get_heart_rate", "get_sleep")
    }

    private suspend fun handleGetSteps(date: String): ToolResult {
        val data = repository.getSteps(date)
        return ToolResult.Success(
            "Steps on ${data.date}: ${data.totalSteps}",
        )
    }

    private suspend fun handleGetHeartRate(date: String): ToolResult {
        val data = repository.getHeartRate(date)
        val lines = data.samples.joinToString("\n") { "  ${it.time}: ${it.bpm} bpm" }
        return ToolResult.Success(
            "Heart rate on ${data.date}:\n$lines",
        )
    }

    private suspend fun handleGetSleep(date: String): ToolResult {
        val data = repository.getSleep(date)
        val lines = data.sessions.joinToString("\n") { session ->
            "  ${session.startTime} - ${session.endTime} (${session.durationMinutes} min)"
        }
        return ToolResult.Success(
            "Sleep on ${data.date}:\n$lines",
        )
    }
}
