package com.rousecontext.mcp.health

import com.rousecontext.mcp.core.McpServerProvider
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeParseException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Health Connect MCP server implementation.
 *
 * Exposes Health Connect data via three generic tools:
 * - `list_record_types` -- lists available record types with permission status
 * - `query_health_data` -- generic query by record type and time range
 * - `get_health_summary` -- high-level summary across types for a period
 */
class HealthConnectMcpServer(private val repository: HealthConnectRepository) : McpServerProvider {

    override val id = "health-connect"
    override val displayName = "Health Connect"

    override fun register(server: Server) {
        registerListRecordTypes(server)
        registerQueryHealthData(server)
        registerGetHealthSummary(server)
    }

    private fun registerListRecordTypes(server: Server) {
        server.addTool(
            name = "list_record_types",
            description = "Lists all available Health Connect record types " +
                "with permission status",
            inputSchema = ToolSchema()
        ) { _ ->
            val granted = repository.getGrantedPermissions()
            val typesArray = buildJsonArray {
                for (info in RecordTypeRegistry.allTypes) {
                    add(buildRecordTypeJson(info, granted))
                }
            }
            CallToolResult(
                content = listOf(TextContent(Json.encodeToString(typesArray)))
            )
        }
    }

    private fun registerQueryHealthData(server: Server) {
        server.addTool(
            name = "query_health_data",
            description = "Query Health Connect records by type and time " +
                "range. Use list_record_types to see available types.",
            inputSchema = queryHealthDataSchema
        ) { request -> handleQueryHealthData(request) }
    }

    private suspend fun handleQueryHealthData(request: CallToolRequest): CallToolResult {
        val validationError = validateQueryArgs(request)
        if (validationError != null) return validationError

        val recordType = request.params.arguments?.get("record_type")!!.jsonPrimitive.content
        val since = parseInstant(request.params.arguments?.get("since")!!.jsonPrimitive.content)!!
        val until = parseUntil(request.params.arguments?.get("until")?.jsonPrimitive?.content)!!
        val limit = request.params.arguments?.get("limit")?.jsonPrimitive?.int

        val records = repository.queryRecords(recordType, since, until, limit)
        val result = buildJsonObject {
            put("record_type", recordType)
            put("count", JsonPrimitive(records.size))
            put("records", buildJsonArray { records.forEach { add(it) } })
        }
        return CallToolResult(
            content = listOf(TextContent(Json.encodeToString(result)))
        )
    }

    private fun registerGetHealthSummary(server: Server) {
        server.addTool(
            name = "get_health_summary",
            description = "Get a high-level health summary across all " +
                "permitted data types for a given period " +
                "(today, week, or month).",
            inputSchema = summarySchema
        ) { request -> handleGetHealthSummary(request) }
    }

    private suspend fun handleGetHealthSummary(request: CallToolRequest): CallToolResult {
        val period = request.params.arguments?.get("period")?.jsonPrimitive?.content
            ?: return errorResult("Missing period")

        val now = Instant.now()
        val from = when (period) {
            "today" -> LocalDate.now().atStartOfDay().toInstant(ZoneOffset.UTC)
            "week" -> now.minus(Duration.ofDays(DAYS_IN_WEEK))
            "month" -> now.minus(Duration.ofDays(DAYS_IN_MONTH))
            else -> return errorResult(
                "Invalid period: $period. Must be today, week, or month."
            )
        }

        val summary = repository.getSummary(from, now)
        return CallToolResult(
            content = listOf(TextContent(Json.encodeToString(summary)))
        )
    }

    @Suppress("ReturnCount")
    private fun validateQueryArgs(request: CallToolRequest): CallToolResult? {
        val recordType = request.params.arguments?.get("record_type")?.jsonPrimitive?.content
            ?: return errorResult("Missing record_type")
        if (RecordTypeRegistry[recordType] == null) {
            return errorResult(
                "Unknown record type: $recordType. " +
                    "Use list_record_types to see available types."
            )
        }
        val sinceStr = request.params.arguments?.get("since")?.jsonPrimitive?.content
        if (parseInstant(sinceStr) == null) {
            return errorResult(
                "Invalid 'since' format. Use ISO 8601 datetime " +
                    "(e.g. 2026-04-01T00:00:00Z) or date (e.g. 2026-04-01)."
            )
        }
        val untilStr = request.params.arguments?.get("until")?.jsonPrimitive?.content
        if (untilStr != null && parseInstant(untilStr) == null) {
            return errorResult(
                "Invalid 'until' format. Use ISO 8601 datetime or date."
            )
        }
        return null
    }

    private fun errorResult(message: String): CallToolResult =
        CallToolResult(content = listOf(TextContent(message)), isError = true)

    companion object {
        private const val DAYS_IN_WEEK = 7L
        private const val DAYS_IN_MONTH = 30L

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

        private fun parseUntil(value: String?): Instant? =
            if (value == null) Instant.now() else parseInstant(value)

        private fun buildRecordTypeJson(info: RecordTypeInfo, granted: Set<String>) =
            buildJsonObject {
                put("type", info.name)
                put("display_name", info.displayName)
                put("category", info.category.value)
                put("description", info.description)
                put("has_permission", granted.contains(info.name))
            }

        private val queryHealthDataSchema = ToolSchema(
            properties = buildJsonObject {
                put(
                    "record_type",
                    buildJsonObject {
                        put("type", JsonPrimitive("string"))
                        put(
                            "description",
                            JsonPrimitive(
                                "Record type name, e.g. Steps, " +
                                    "HeartRate, SleepSession"
                            )
                        )
                    }
                )
                put(
                    "since",
                    buildJsonObject {
                        put("type", JsonPrimitive("string"))
                        put(
                            "description",
                            JsonPrimitive(
                                "Start of time range, ISO 8601 " +
                                    "datetime or date"
                            )
                        )
                    }
                )
                put(
                    "until",
                    buildJsonObject {
                        put("type", JsonPrimitive("string"))
                        put(
                            "description",
                            JsonPrimitive(
                                "End of time range, ISO 8601 " +
                                    "datetime or date. Defaults to now."
                            )
                        )
                    }
                )
                put(
                    "limit",
                    buildJsonObject {
                        put("type", JsonPrimitive("integer"))
                        put(
                            "description",
                            JsonPrimitive(
                                "Maximum number of records to return"
                            )
                        )
                    }
                )
            },
            required = listOf("record_type", "since")
        )

        private val summarySchema = ToolSchema(
            properties = buildJsonObject {
                put(
                    "period",
                    buildJsonObject {
                        put("type", JsonPrimitive("string"))
                        put(
                            "description",
                            JsonPrimitive(
                                "Summary period: today, week, or month"
                            )
                        )
                        put(
                            "enum",
                            buildJsonArray {
                                add(JsonPrimitive("today"))
                                add(JsonPrimitive("week"))
                                add(JsonPrimitive("month"))
                            }
                        )
                    }
                )
            },
            required = listOf("period")
        )
    }
}
