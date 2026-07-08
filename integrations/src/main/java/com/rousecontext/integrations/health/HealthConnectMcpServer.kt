package com.rousecontext.integrations.health

import com.rousecontext.integrations.common.PeriodParser
import com.rousecontext.mcp.core.McpServerProvider
import com.rousecontext.mcp.tool.McpTool
import com.rousecontext.mcp.tool.ToolResult
import com.rousecontext.mcp.tool.registerTool
import io.modelcontextprotocol.kotlin.sdk.server.Server
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
    override val description = "List Health Connect record types with permission status."

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
        "Query Health Connect records by type and time range; " +
            "see list_record_types for types. " +
            "Default returns raw records; when the range holds more than the cap " +
            "(limit, else 500) the records are evenly downsampled across the range " +
            "and the response carries total_count and downsampled=true. " +
            "Pass 'bucket' (e.g. 1m, 5m, 1h, 1d) to instead get per-period stats " +
            "{start,count,min,max,avg}; bucketing works only for scalar types " +
            "(BloodGlucose, HeartRate, temperatures, weight, etc.) and rejects a " +
            "too-fine bucket over a large range."

    val recordType by stringParam("record_type", "e.g. Steps, HeartRate, SleepSession")
    val since by stringParam("since", "ISO 8601 date or datetime")
    val until by stringParam("until", "ISO 8601, defaults to now").optional()
    val limit by intParam("limit", "cap on raw records (also the downsample cap)").optional()
    val bucket by stringParam(
        "bucket",
        "optional aggregation window like 1m, 5m, 1h, 1d (scalar types only)"
    ).optional()

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

        return if (bucket != null) {
            executeBucketed(type, fromInstant, toInstant, bucket!!)
        } else {
            executeRaw(type, fromInstant, toInstant)
        }
    }

    private suspend fun executeRaw(type: String, from: Instant, to: Instant): ToolResult {
        val query = repository.queryRecords(type, from, to, limit)
        val result = buildJsonObject {
            put("record_type", type)
            put("count", JsonPrimitive(query.records.size))
            put("total_count", JsonPrimitive(query.totalCount))
            put("downsampled", query.downsampled)
            put("records", buildJsonArray { query.records.forEach { add(it) } })
        }
        return ToolResult.Success(Json.encodeToString(result))
    }

    private suspend fun executeBucketed(
        type: String,
        from: Instant,
        to: Instant,
        bucketSpec: String
    ): ToolResult {
        val duration = parseBucket(bucketSpec)
            ?: return ToolResult.Error(
                "Invalid 'bucket' format: '$bucketSpec'. " +
                    "Use <int><unit> where unit is s, m, h, or d (e.g. 5m, 1h)."
            )
        return when (val outcome = repository.bucketRecords(type, from, to, duration)) {
            is BucketResult.Error -> ToolResult.Error(outcome.message)
            is BucketResult.Success -> {
                val result = buildJsonObject {
                    put("record_type", type)
                    put("bucket", bucketSpec)
                    put("buckets", buildJsonArray { outcome.buckets.forEach { add(it.toJson()) } })
                }
                ToolResult.Success(Json.encodeToString(result))
            }
        }
    }

    private companion object {
        private val BUCKET_PATTERN = Regex("^(\\d+)([smhd])$")

        /** Parse a `<int><unit>` bucket spec (unit s/m/h/d) to a positive [Duration]. */
        fun parseBucket(spec: String): java.time.Duration? {
            val match = BUCKET_PATTERN.matchEntire(spec.trim()) ?: return null
            val amount = match.groupValues[1].toLongOrNull()?.takeIf { it > 0 } ?: return null
            return when (match.groupValues[2]) {
                "s" -> java.time.Duration.ofSeconds(amount)
                "m" -> java.time.Duration.ofMinutes(amount)
                "h" -> java.time.Duration.ofHours(amount)
                "d" -> java.time.Duration.ofDays(amount)
                else -> null
            }
        }
    }
}

internal class GetHealthSummaryTool(private val repository: HealthConnectRepository) : McpTool() {
    override val name = "get_health_summary"
    override val description = "Health summary across permitted types for a period."

    val period by stringParam("period", PeriodParser.PERIOD_DESCRIPTION)

    override suspend fun execute(): ToolResult {
        // Delegate to the shared parser so all MCP providers agree on zone
        // (local) and anchoring (sliding window from the start of today).
        val range = PeriodParser.parse(period!!)
            ?: return ToolResult.Error(
                "Invalid period: $period. Must be today, week, or month."
            )

        val summary = repository.getSummary(range.start, range.end)
        return ToolResult.Success(Json.encodeToString(summary))
    }
}
