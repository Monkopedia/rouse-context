package com.rousecontext.mcp.health

import com.rousecontext.mcp.core.McpServerProvider
import io.modelcontextprotocol.kotlin.sdk.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.TextContent
import io.modelcontextprotocol.kotlin.sdk.Tool
import io.modelcontextprotocol.kotlin.sdk.server.Server
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * Health Connect MCP server implementation.
 * Exposes Health Connect data via MCP tools: get_steps, get_heart_rate, get_sleep.
 */
class HealthConnectMcpServer(
    private val repository: HealthConnectRepository,
) : McpServerProvider {

    override fun register(server: Server) {
        registerGetSteps(server)
        registerGetHeartRate(server)
        registerGetSleep(server)
    }

    private fun registerGetSteps(server: Server) {
        server.addTool(
            name = "get_steps",
            description = "Get daily step counts from Health Connect.",
            inputSchema = daysInputSchema(),
        ) { request ->
            val days = request.arguments.daysParam()
            val (start, end) = timeRange(days)
            val steps = repository.getSteps(start, end)
            val json = buildString {
                append("[")
                steps.forEachIndexed { i, s ->
                    if (i > 0) append(",")
                    append("""{"date":"${s.date}","count":${s.count}}""")
                }
                append("]")
            }
            CallToolResult(content = listOf(TextContent(json)))
        }
    }

    private fun registerGetHeartRate(server: Server) {
        server.addTool(
            name = "get_heart_rate",
            description = "Get heart rate readings from Health Connect.",
            inputSchema = daysInputSchema(),
        ) { request ->
            val days = request.arguments.daysParam()
            val (start, end) = timeRange(days)
            val samples = repository.getHeartRate(start, end)
            val json = buildString {
                append("[")
                samples.forEachIndexed { i, s ->
                    if (i > 0) append(",")
                    append("""{"time":"${ISO_FORMATTER.format(s.time)}","bpm":${s.bpm}}""")
                }
                append("]")
            }
            CallToolResult(content = listOf(TextContent(json)))
        }
    }

    private fun registerGetSleep(server: Server) {
        server.addTool(
            name = "get_sleep",
            description = "Get sleep sessions from Health Connect.",
            inputSchema = daysInputSchema(),
        ) { request ->
            val days = request.arguments.daysParam()
            val (start, end) = timeRange(days)
            val sessions = repository.getSleepSessions(start, end)
            val json = buildString {
                append("[")
                sessions.forEachIndexed { i, session ->
                    if (i > 0) append(",")
                    append("{")
                    append(""""startTime":"${ISO_FORMATTER.format(session.startTime)}",""")
                    append(""""endTime":"${ISO_FORMATTER.format(session.endTime)}",""")
                    append(""""durationMinutes":${session.durationMinutes},""")
                    append(""""stages":[""")
                    session.stages.forEachIndexed { j, stage ->
                        if (j > 0) append(",")
                        append("{")
                        append(""""stage":"${stage.stage}",""")
                        append(""""startTime":"${ISO_FORMATTER.format(stage.startTime)}",""")
                        append(""""endTime":"${ISO_FORMATTER.format(stage.endTime)}"""")
                        append("}")
                    }
                    append("]}")
                }
                append("]")
            }
            CallToolResult(content = listOf(TextContent(json)))
        }
    }

    companion object {
        private const val DEFAULT_DAYS = 7

        private val ISO_FORMATTER: DateTimeFormatter =
            DateTimeFormatter.ISO_INSTANT.withZone(ZoneId.of("UTC"))

        private fun daysInputSchema(): Tool.Input = Tool.Input(
            properties = JsonObject(
                mapOf(
                    "days" to JsonObject(
                        mapOf(
                            "type" to JsonPrimitive("integer"),
                            "description" to JsonPrimitive(
                                "Number of days of history (default $DEFAULT_DAYS)",
                            ),
                        ),
                    ),
                ),
            ),
        )

        private fun JsonObject?.daysParam(): Int =
            this?.get("days")?.jsonPrimitive?.int ?: DEFAULT_DAYS

        private fun timeRange(days: Int): Pair<Instant, Instant> {
            val end = Instant.now()
            val start = end.minus(days.toLong(), ChronoUnit.DAYS)
            return start to end
        }
    }
}
