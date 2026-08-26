package com.rousecontext.integrations.health

import com.rousecontext.integrations.common.PeriodParser
import com.rousecontext.integrations.health.query.Bucket
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
            "Returns raw records when the range holds no more than the cap " +
            "(limit, else 500). When it holds more, the records that fit would be " +
            "only the range's earliest slice, so the answer is instead per-period " +
            "stats {start,count,min,max,avg} spanning the whole range, with the " +
            "chosen 'bucket' width and a 'note' saying so; narrow the range or " +
            "raise 'limit' for raw records. Record types that cannot be aggregated " +
            "(sessions, multi-value, cumulative) return records evenly spread " +
            "across the range with downsampled=true. " +
            "Pass 'bucket' (e.g. 1m, 5m, 1h, 1d) to ask for per-period stats " +
            "directly; bucketing works only for scalar types (BloodGlucose, " +
            "HeartRate, temperatures, weight, etc.) and rejects a too-fine bucket " +
            "over a large range."

    val recordType by stringParam("record_type", "e.g. Steps, HeartRate, SleepSession").required()
    val since by stringParam("since", "ISO 8601 date or datetime").required()
    val until by stringParam("until", "ISO 8601, defaults to now").optional()
    val limit by intParam("limit", "cap on raw records (also the downsample cap)").optional()
    val bucket by stringParam(
        "bucket",
        "optional aggregation window like 1m, 5m, 1h, 1d (scalar types only)"
    ).optional()

    override suspend fun execute(): ToolResult {
        if (RecordTypeRegistry[recordType] == null) {
            return ToolResult.Error(
                "Unknown record type: $recordType. " +
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

        val bucketSpec = bucket
        return if (bucketSpec != null) {
            executeBucketed(recordType, fromInstant, toInstant, bucketSpec)
        } else {
            executeRaw(recordType, fromInstant, toInstant)
        }
    }

    private suspend fun executeRaw(type: String, from: Instant, to: Instant): ToolResult {
        val result = when (val query = repository.queryRecords(type, from, to, limit)) {
            is QueryResult.Records -> buildJsonObject {
                put("record_type", type)
                put("count", JsonPrimitive(query.records.size))
                put("total_count", JsonPrimitive(query.totalCount))
                put("downsampled", query.downsampled)
                put("records", buildJsonArray { query.records.forEach { add(it) } })
            }
            is QueryResult.Buckets -> {
                val spec = formatBucket(query.width)
                bucketsJson(
                    type = type,
                    bucketSpec = spec,
                    buckets = query.buckets,
                    totalCount = query.totalCount,
                    note = "The range holds ${query.totalCount} records, more than the cap of " +
                        "${limit ?: DEFAULT_MAX_RECORDS}, so returning $spec aggregates spanning " +
                        "the whole range rather than only its earliest records. Narrow " +
                        "'since'/'until' or raise 'limit' to get raw records."
                )
            }
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
            is BucketResult.Success -> ToolResult.Success(
                Json.encodeToString(
                    bucketsJson(type, bucketSpec, outcome.buckets, outcome.totalCount, note = null)
                )
            )
        }
    }

    private fun bucketsJson(
        type: String,
        bucketSpec: String,
        buckets: List<Bucket>,
        totalCount: Int,
        note: String?
    ) = buildJsonObject {
        put("record_type", type)
        put("bucket", bucketSpec)
        put("total_count", JsonPrimitive(totalCount))
        put("buckets", buildJsonArray { buckets.forEach { add(it.toJson()) } })
        if (note != null) put("note", note)
    }

    private companion object {
        private val BUCKET_PATTERN = Regex("^(\\d+)([smhd])$")

        /** Parse a `<int><unit>` bucket spec (unit s/m/h/d) to a positive [Duration]. */
        fun parseBucket(spec: String): Duration? {
            val match = BUCKET_PATTERN.matchEntire(spec.trim()) ?: return null
            val amount = match.groupValues[1].toLongOrNull()?.takeIf { it > 0 } ?: return null
            return when (match.groupValues[2]) {
                "s" -> Duration.ofSeconds(amount)
                "m" -> Duration.ofMinutes(amount)
                "h" -> Duration.ofHours(amount)
                "d" -> Duration.ofDays(amount)
                else -> null
            }
        }

        /**
         * Render a bucket width in the same `<int><unit>` form the tool accepts,
         * using the largest unit that divides it exactly. Sub-second widths round
         * up to `1s`, the finest spec the tool can express.
         */
        fun formatBucket(width: Duration): String {
            val seconds = width.toSeconds().coerceAtLeast(1)
            return when {
                seconds % 86_400L == 0L -> "${seconds / 86_400L}d"
                seconds % 3_600L == 0L -> "${seconds / 3_600L}h"
                seconds % 60L == 0L -> "${seconds / 60L}m"
                else -> "${seconds}s"
            }
        }
    }
}

internal class GetHealthSummaryTool(private val repository: HealthConnectRepository) : McpTool() {
    override val name = "get_health_summary"
    override val description = "Health summary across permitted types for a period."

    val period by stringParam("period", PeriodParser.PERIOD_DESCRIPTION).required()

    override suspend fun execute(): ToolResult {
        // Delegate to the shared parser so all MCP providers agree on zone
        // (local) and anchoring (sliding window from the start of today).
        val range = PeriodParser.parse(period)
            ?: return ToolResult.Error(
                "Invalid period: $period. Must be today, week, or month."
            )

        val summary = repository.getSummary(range.start, range.end)
        return ToolResult.Success(Json.encodeToString(summary))
    }
}
