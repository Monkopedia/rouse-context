package com.rousecontext.integrations.health

import com.rousecontext.mcp.core.McpServerProvider
import com.rousecontext.mcp.tool.McpTool
import com.rousecontext.mcp.tool.ToolResult
import com.rousecontext.mcp.tool.registerTool
import io.modelcontextprotocol.kotlin.sdk.server.Server
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeParseException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Health Connect MCP server implementation.
 *
 * Exposes Health Connect data via three generic tools:
 * - `list_record_types` -- lists available record types with permission status
 * - `query_health_data` -- generic query by record type and time range
 * - `get_health_summary` -- high-level summary across types for a period
 *
 * Tools are authored with the [McpTool] DSL; this provider just wires
 * dependencies and registers them.
 */
class HealthConnectMcpServer(private val repository: HealthConnectRepository) : McpServerProvider {

    override val id = "health-connect"
    override val displayName = "Health Connect"

    override fun register(server: Server) {
        server.registerTool { ListRecordTypesTool(repository) }
        server.registerTool { QueryHealthDataTool(repository) }
        server.registerTool { GetHealthSummaryTool(repository) }
    }

    companion object {
        internal const val DAYS_IN_WEEK = 7L
        internal const val DAYS_IN_MONTH = 30L

        /**
         * Parses an ISO 8601 datetime or date-only string to [Instant].
         * Returns null if parsing fails.
         */
        internal fun parseInstant(value: String?): Instant? {
            if (value == null) return null
            return try {
                Instant.parse(value)
            } catch (_: DateTimeParseException) {
                try {
                    LocalDate.parse(value)
                        .atStartOfDay()
                        .toInstant(ZoneOffset.UTC)
                } catch (_: DateTimeParseException) {
                    null
                }
            }
        }

        internal fun parseUntil(value: String?): Instant? =
            if (value == null) Instant.now() else parseInstant(value)

        internal fun buildRecordTypeJson(info: RecordTypeInfo, granted: Set<String>) =
            buildJsonObject {
                put("type", info.name)
                put("display_name", info.displayName)
                put("category", info.category.value)
                put("description", info.description)
                put("has_permission", granted.contains(info.name))
            }
    }
}

// ---------- tools ----------

internal class ListRecordTypesTool(private val repository: HealthConnectRepository) : McpTool() {
    override val name = "list_record_types"
    override val description =
        "Lists all available Health Connect record types with permission status"

    override suspend fun execute(): ToolResult {
        val granted = repository.getGrantedPermissions()
        val typesArray = buildJsonArray {
            for (info in RecordTypeRegistry.allTypes) {
                add(HealthConnectMcpServer.buildRecordTypeJson(info, granted))
            }
        }
        return ToolResult.Success(Json.encodeToString(typesArray))
    }
}

internal class QueryHealthDataTool(private val repository: HealthConnectRepository) : McpTool() {
    override val name = "query_health_data"
    override val description =
        "Query Health Connect records by type and time range. " +
            "Use list_record_types to see available types."

    val recordType by stringParam(
        "record_type",
        "Record type name, e.g. Steps, HeartRate, SleepSession"
    )
    val since by stringParam(
        "since",
        "Start of time range, ISO 8601 datetime or date"
    )
    val until by stringParam(
        "until",
        "End of time range, ISO 8601 datetime or date. Defaults to now."
    ).optional()
    val limit by intParam("limit", "Maximum number of records to return").optional()

    override suspend fun execute(): ToolResult {
        val type = recordType!!
        if (RecordTypeRegistry[type] == null) {
            return ToolResult.Error(
                "Unknown record type: $type. " +
                    "Use list_record_types to see available types."
            )
        }
        val fromInstant = HealthConnectMcpServer.parseInstant(since)
            ?: return ToolResult.Error(
                "Invalid 'since' format. Use ISO 8601 datetime " +
                    "(e.g. 2026-04-01T00:00:00Z) or date (e.g. 2026-04-01)."
            )
        val toInstant = HealthConnectMcpServer.parseUntil(until)
            ?: return ToolResult.Error(
                "Invalid 'until' format. Use ISO 8601 datetime or date."
            )

        val records = repository.queryRecords(type, fromInstant, toInstant, limit)
        val result = buildJsonObject {
            put("record_type", type)
            put("count", JsonPrimitive(records.size))
            put("records", buildJsonArray { records.forEach { add(it) } })
        }
        return ToolResult.Success(Json.encodeToString(result))
    }
}

internal class GetHealthSummaryTool(private val repository: HealthConnectRepository) : McpTool() {
    override val name = "get_health_summary"
    override val description =
        "Get a high-level health summary across all permitted data types " +
            "for a given period (today, week, or month)."

    val period by stringParam("period", "Summary period: today, week, or month")

    override suspend fun execute(): ToolResult {
        val now = Instant.now()
        val from = when (period) {
            "today" -> LocalDate.now().atStartOfDay().toInstant(ZoneOffset.UTC)
            "week" -> now.minus(Duration.ofDays(HealthConnectMcpServer.DAYS_IN_WEEK))
            "month" -> now.minus(Duration.ofDays(HealthConnectMcpServer.DAYS_IN_MONTH))
            else -> return ToolResult.Error(
                "Invalid period: $period. Must be today, week, or month."
            )
        }

        val summary = repository.getSummary(from, now)
        return ToolResult.Success(Json.encodeToString(summary))
    }
}
