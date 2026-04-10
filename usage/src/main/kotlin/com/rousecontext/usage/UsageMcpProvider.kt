package com.rousecontext.usage

import android.app.usage.UsageEvents
import android.app.usage.UsageStats
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import com.rousecontext.mcp.core.McpServerProvider
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Calendar
import java.util.TimeZone
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * MCP server provider that exposes device usage statistics
 * via [UsageStatsManager].
 *
 * Tools:
 * - `get_usage_summary` -- Per-app screen time breakdown
 * - `get_app_usage` -- Detailed usage for a single app
 * - `get_usage_events` -- Raw usage events (app opened/closed)
 * - `compare_usage` -- Compare two periods, show biggest changes
 */
class UsageMcpProvider(
    private val context: Context
) : McpServerProvider {

    override val id = "usage"
    override val displayName = "Usage Stats"

    private val usageStatsManager: UsageStatsManager
        get() = context.getSystemService(UsageStatsManager::class.java)

    override fun register(server: Server) {
        registerGetUsageSummary(server)
        registerGetAppUsage(server)
        registerGetUsageEvents(server)
        registerCompareUsage(server)
    }

    private fun registerGetUsageSummary(server: Server) {
        server.addTool(
            name = "get_usage_summary",
            description = "Get total screen time and per-app breakdown " +
                "for a time period. Returns apps sorted by foreground time.",
            inputSchema = summarySchema()
        ) { request -> handleUsageSummary(request) }
    }

    private fun registerGetAppUsage(server: Server) {
        server.addTool(
            name = "get_app_usage",
            description = "Get detailed usage for a specific app, " +
                "including daily breakdown.",
            inputSchema = appUsageSchema()
        ) { request -> handleAppUsage(request) }
    }

    private fun registerGetUsageEvents(server: Server) {
        server.addTool(
            name = "get_usage_events",
            description = "Get raw usage events " +
                "(app opened, closed, etc.) for a time range.",
            inputSchema = eventsSchema()
        ) { request -> handleUsageEvents(request) }
    }

    private fun registerCompareUsage(server: Server) {
        server.addTool(
            name = "compare_usage",
            description = "Compare app usage between two time periods. " +
                "Shows biggest increases and decreases in screen time.",
            inputSchema = compareSchema()
        ) { request -> handleCompareUsage(request) }
    }

    // region handlers

    private fun handleUsageSummary(request: CallToolRequest): CallToolResult {
        val periodStr = request.stringArg("period")
            ?: return errorResult("Missing period")
        val limit = request.intArg("limit") ?: DEFAULT_LIMIT

        val range = parsePeriod(periodStr)
            ?: return invalidPeriodResult(periodStr)

        val stats = usageStatsManager.queryUsageStats(
            UsageStatsManager.INTERVAL_BEST,
            range.first,
            range.second
        )

        val merged = mergeByPackage(stats)
        val filtered = merged.entries
            .filter { (_, ms) -> ms > 0 }
            .sortedByDescending { (_, ms) -> ms }
            .take(limit)

        val totalMs = filtered.sumOf { (_, ms) -> ms }
        val apps = filtered.joinToString(",") { (pkg, ms) -> appToJson(pkg, ms) }

        return jsonResult(
            """{"period":"$periodStr",""" +
                """"total_minutes":${totalMs / MS_PER_MIN},""" +
                """"apps":[$apps]}"""
        )
    }

    private fun handleAppUsage(request: CallToolRequest): CallToolResult {
        val packageName = request.stringArg("package_name")
            ?: return errorResult("Missing package_name")
        val periodStr = request.stringArg("period")
            ?: return errorResult("Missing period")

        val range = parsePeriod(periodStr)
            ?: return invalidPeriodResult(periodStr)

        val stats = usageStatsManager.queryUsageStats(
            UsageStatsManager.INTERVAL_DAILY,
            range.first,
            range.second
        )

        val appStats = stats.filter { it.packageName == packageName }
        val appName = resolveAppName(packageName)
        val totalMs = mergeByPackage(appStats)[packageName] ?: 0L
        val daily = formatDailyBreakdown(appStats)

        return jsonResult(
            """{"package":"${packageName.escapeJson()}",""" +
                """"name":"${appName.escapeJson()}",""" +
                """"period":"$periodStr",""" +
                """"total_minutes":${totalMs / MS_PER_MIN},""" +
                """"daily":[$daily]}"""
        )
    }

    @Suppress("ReturnCount")
    private fun handleUsageEvents(request: CallToolRequest): CallToolResult {
        val sinceStr = request.stringArg("since")
            ?: return errorResult("Missing since")
        val untilStr = request.stringArg("until")
            ?: return errorResult("Missing until")
        val packageFilter = request.stringArg("package")
        val includeSystem = request.boolArg("include_system") ?: false
        val limit = request.intArg("limit") ?: DEFAULT_EVENT_LIMIT

        val sinceRange = parsePeriod(sinceStr)
            ?: return errorResult("Invalid since period: $sinceStr")
        val untilRange = parsePeriod(untilStr)
            ?: return errorResult("Invalid until period: $untilStr")

        val events = usageStatsManager.queryEvents(
            sinceRange.first,
            untilRange.second
        )
        val entries = collectEvents(events, packageFilter, includeSystem, limit)

        return jsonResult(
            """{"events":[${entries.joinToString(",")}]}"""
        )
    }

    @Suppress("ReturnCount")
    private fun handleCompareUsage(request: CallToolRequest): CallToolResult {
        val p1Str = request.stringArg("period1")
            ?: return errorResult("Missing period1")
        val p2Str = request.stringArg("period2")
            ?: return errorResult("Missing period2")
        val limit = request.intArg("limit") ?: DEFAULT_LIMIT

        val r1 = parsePeriod(p1Str)
            ?: return invalidPeriodResult(p1Str, "period1")
        val r2 = parsePeriod(p2Str)
            ?: return invalidPeriodResult(p2Str, "period2")

        val stats1 = queryBest(r1)
        val stats2 = queryBest(r2)

        return buildComparisonResult(p1Str, p2Str, stats1, stats2, limit)
    }

    // endregion

    // region formatting helpers

    private fun appToJson(packageName: String, foregroundMs: Long): String {
        val name = resolveAppName(packageName)
        val mins = foregroundMs / MS_PER_MIN
        return """{"package":"${packageName.escapeJson()}",""" +
            """"name":"${name.escapeJson()}",""" +
            """"foreground_minutes":$mins}"""
    }

    private fun formatDailyBreakdown(appStats: List<UsageStats>): String {
        val cal = Calendar.getInstance(TimeZone.getDefault())
        // Group by date string and sum foreground time per day
        val byDate = mutableMapOf<String, Long>()
        for (stat in appStats) {
            if (stat.totalTimeInForeground <= 0) continue
            cal.timeInMillis = stat.firstTimeStamp
            val date = "%04d-%02d-%02d".format(
                cal.get(Calendar.YEAR),
                cal.get(Calendar.MONTH) + 1,
                cal.get(Calendar.DAY_OF_MONTH)
            )
            byDate[date] = (byDate[date] ?: 0L) + stat.totalTimeInForeground
        }
        return byDate.entries.joinToString(",") { (date, ms) ->
            """{"date":"$date","foreground_minutes":${ms / MS_PER_MIN}}"""
        }
    }

    @Suppress("LoopWithTooManyJumpStatements")
    private fun collectEvents(
        usageEvents: UsageEvents,
        packageFilter: String?,
        includeSystem: Boolean,
        limit: Int
    ): List<String> {
        val entries = mutableListOf<String>()
        val event = UsageEvents.Event()
        while (usageEvents.hasNextEvent() && entries.size < limit) {
            usageEvents.getNextEvent(event)
            val pkg = event.packageName
            if (packageFilter != null && pkg != packageFilter) continue
            if (!includeSystem && isFilteredPackage(pkg)) continue
            val typeName = eventTypeToString(event.eventType)
            val appName = resolveAppName(pkg)
            val isoTime = formatTimestamp(event.timeStamp)
            entries.add(
                """{"package":"${pkg.escapeJson()}",""" +
                    """"name":"${appName.escapeJson()}",""" +
                    """"type":"$typeName",""" +
                    """"timestamp":"$isoTime"}"""
            )
        }
        return entries
    }

    private fun buildComparisonResult(
        p1Str: String,
        p2Str: String,
        stats1: List<UsageStats>,
        stats2: List<UsageStats>,
        limit: Int
    ): CallToolResult {
        val map1 = mergeByPackage(stats1)
        val map2 = mergeByPackage(stats2)

        val changes = (map1.keys + map2.keys)
            .map { pkg ->
                Triple(pkg, map1[pkg] ?: 0L, map2[pkg] ?: 0L)
            }
            .filter { (_, t1, t2) -> t1 > 0 || t2 > 0 }
            .sortedByDescending { (_, t1, t2) ->
                kotlin.math.abs(t2 - t1)
            }
            .take(limit)

        val total1 = map1.values.sum()
        val total2 = map2.values.sum()

        val apps = changes.joinToString(",") { (pkg, t1, t2) ->
            val name = resolveAppName(pkg)
            val diff = (t2 - t1) / MS_PER_MIN
            val sign = if (diff >= 0) "+" else ""
            """{"package":"${pkg.escapeJson()}",""" +
                """"name":"${name.escapeJson()}",""" +
                """"period1_minutes":${t1 / MS_PER_MIN},""" +
                """"period2_minutes":${t2 / MS_PER_MIN},""" +
                """"change":"$sign$diff min"}"""
        }

        return jsonResult(
            """{"period1":"$p1Str","period2":"$p2Str",""" +
                """"total1_minutes":${total1 / MS_PER_MIN},""" +
                """"total2_minutes":${total2 / MS_PER_MIN},""" +
                """"apps":[$apps]}"""
        )
    }

    // endregion

    // region utilities

    /**
     * Merge duplicate [UsageStats] entries by package name, summing foreground time.
     * [UsageStatsManager] may return multiple entries per package for different
     * internal time buckets; this collapses them into one entry per package.
     */
    private fun mergeByPackage(stats: List<UsageStats>): Map<String, Long> =
        stats.groupBy { it.packageName }
            .mapValues { (_, entries) -> entries.sumOf { it.totalTimeInForeground } }

    private fun queryBest(range: Pair<Long, Long>): List<UsageStats> =
        usageStatsManager.queryUsageStats(
            UsageStatsManager.INTERVAL_BEST,
            range.first,
            range.second
        )

    @Suppress("DEPRECATION")
    private fun resolveAppName(packageName: String): String = try {
        val info = context.packageManager.getApplicationInfo(
            packageName,
            0
        )
        context.packageManager.getApplicationLabel(info).toString()
    } catch (_: PackageManager.NameNotFoundException) {
        packageName
    }

    companion object {
        private const val DEFAULT_LIMIT = 10
        private const val DEFAULT_EVENT_LIMIT = 50
        private const val MS_PER_MIN = 60_000L

        /**
         * Packages filtered out by default from usage events to reduce noise.
         * Includes Rouse Context itself and Android system intelligence services.
         */
        private val FILTERED_PACKAGE_PREFIXES = listOf(
            "com.rousecontext",
            "com.google.android.as",
            "com.google.android.ext.services"
        )

        internal fun isFilteredPackage(packageName: String): Boolean =
            FILTERED_PACKAGE_PREFIXES.any { packageName.startsWith(it) }

        private val ISO_FORMATTER: DateTimeFormatter =
            DateTimeFormatter.ISO_OFFSET_DATE_TIME.withZone(ZoneId.systemDefault())

        internal fun formatTimestamp(epochMillis: Long): String =
            ISO_FORMATTER.format(Instant.ofEpochMilli(epochMillis))
    }

    // endregion
}

// region Period parsing

/**
 * Parse a period string into a start/end epoch millis pair.
 * Returns null for unrecognised values.
 */
internal fun parsePeriod(period: String): Pair<Long, Long>? {
    val now = System.currentTimeMillis()
    val cal = Calendar.getInstance(TimeZone.getDefault())

    return when (period) {
        "today" -> {
            cal.set(Calendar.HOUR_OF_DAY, 0)
            cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            cal.timeInMillis to now
        }
        "yesterday" -> {
            cal.set(Calendar.HOUR_OF_DAY, 0)
            cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            val todayMidnight = cal.timeInMillis
            cal.add(Calendar.DAY_OF_YEAR, -1)
            cal.timeInMillis to todayMidnight
        }
        "week" -> {
            cal.add(Calendar.DAY_OF_YEAR, -7)
            cal.timeInMillis to now
        }
        "month" -> {
            cal.add(Calendar.DAY_OF_YEAR, -30)
            cal.timeInMillis to now
        }
        else -> null
    }
}

// endregion

// region Schema builders

private fun summarySchema() = ToolSchema(
    properties = buildJsonObject {
        put("period", periodProperty())
        put(
            "limit",
            buildJsonObject {
                put("type", JsonPrimitive("integer"))
                put(
                    "description",
                    JsonPrimitive("Max apps to return (default 10)")
                )
            }
        )
    },
    required = listOf("period")
)

private fun appUsageSchema() = ToolSchema(
    properties = buildJsonObject {
        put(
            "package_name",
            buildJsonObject {
                put("type", JsonPrimitive("string"))
                put(
                    "description",
                    JsonPrimitive("Package name of the app")
                )
            }
        )
        put("period", periodProperty())
    },
    required = listOf("package_name", "period")
)

private fun eventsSchema() = ToolSchema(
    properties = buildJsonObject {
        put(
            "since",
            buildJsonObject {
                put("type", JsonPrimitive("string"))
                put(
                    "description",
                    JsonPrimitive("Start period: today, yesterday, week, month")
                )
            }
        )
        put(
            "until",
            buildJsonObject {
                put("type", JsonPrimitive("string"))
                put(
                    "description",
                    JsonPrimitive("End period: today, yesterday, week, month")
                )
            }
        )
        put(
            "package",
            buildJsonObject {
                put("type", JsonPrimitive("string"))
                put(
                    "description",
                    JsonPrimitive("Optional: filter to this package name")
                )
            }
        )
        put(
            "include_system",
            buildJsonObject {
                put("type", JsonPrimitive("boolean"))
                put(
                    "description",
                    JsonPrimitive(
                        "Include Rouse Context and Android system " +
                            "intelligence events (default false)"
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
                    JsonPrimitive("Max events to return (default 50)")
                )
            }
        )
    },
    required = listOf("since", "until")
)

private fun compareSchema() = ToolSchema(
    properties = buildJsonObject {
        put(
            "period1",
            buildJsonObject {
                put("type", JsonPrimitive("string"))
                put(
                    "description",
                    JsonPrimitive(
                        "First period: today, yesterday, week, month"
                    )
                )
            }
        )
        put(
            "period2",
            buildJsonObject {
                put("type", JsonPrimitive("string"))
                put(
                    "description",
                    JsonPrimitive(
                        "Second period: today, yesterday, week, month"
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
                    JsonPrimitive("Max apps to compare (default 10)")
                )
            }
        )
    },
    required = listOf("period1", "period2")
)

// endregion

// region Event type mapping

/**
 * Maps [UsageEvents.Event] type constants to human-readable names.
 * Constants that lack SDK fields are referenced by their integer value.
 */
@Suppress("MagicNumber")
private fun eventTypeToString(type: Int): String = EVENT_TYPE_NAMES[type]
    ?: "unknown($type)"

/**
 * Lookup table for all documented UsageEvents event type constants.
 * Values sourced from [UsageEvents.Event] and AOSP docs.
 */
@Suppress("MagicNumber")
private val EVENT_TYPE_NAMES: Map<Int, String> = mapOf(
    UsageEvents.Event.ACTIVITY_RESUMED to "activity_resumed",
    UsageEvents.Event.ACTIVITY_PAUSED to "activity_paused",
    3 to "end_of_day",
    4 to "continue_previous_day",
    UsageEvents.Event.CONFIGURATION_CHANGE to "configuration_change",
    6 to "system_interaction",
    UsageEvents.Event.USER_INTERACTION to "user_interaction",
    8 to "shortcut_invocation",
    9 to "chooser_action",
    10 to "notification_seen",
    11 to "standby_bucket_changed",
    12 to "notification_interruption",
    13 to "slice_pinned_priv",
    14 to "slice_pinned",
    15 to "screen_interactive",
    16 to "screen_non_interactive",
    17 to "keyguard_shown",
    18 to "keyguard_hidden",
    19 to "foreground_service_start",
    20 to "foreground_service_stop",
    21 to "continue_previous_day",
    22 to "continuing_foreground_service",
    23 to "activity_stopped",
    24 to "activity_destroyed",
    25 to "flush_to_disk",
    26 to "device_shutdown",
    27 to "device_startup",
    28 to "user_unlocked",
    29 to "user_stopped",
    30 to "locus_id_set"
)

// endregion

// region JSON helpers

private fun periodProperty() = buildJsonObject {
    put("type", JsonPrimitive("string"))
    put(
        "description",
        JsonPrimitive("Time period: today, yesterday, week, month")
    )
    put(
        "enum",
        kotlinx.serialization.json.JsonArray(
            listOf(
                JsonPrimitive("today"),
                JsonPrimitive("yesterday"),
                JsonPrimitive("week"),
                JsonPrimitive("month")
            )
        )
    )
}

private fun errorResult(message: String): CallToolResult = CallToolResult(
    content = listOf(
        TextContent("""{"success":false,"error":"$message"}""")
    ),
    isError = true
)

private fun invalidPeriodResult(period: String, label: String = "period"): CallToolResult =
    errorResult(
        "Invalid $label: $period. Use: today, yesterday, week, month"
    )

private fun jsonResult(json: String): CallToolResult = CallToolResult(
    content = listOf(TextContent(json))
)

private fun CallToolRequest.stringArg(name: String): String? =
    params.arguments?.get(name)?.let { it as? JsonPrimitive }?.content

private fun CallToolRequest.intArg(name: String): Int? = stringArg(name)?.toIntOrNull()

private fun CallToolRequest.boolArg(name: String): Boolean? =
    params.arguments?.get(name)?.let { it as? JsonPrimitive }
        ?.content?.toBooleanStrictOrNull()

private fun String.escapeJson(): String = replace("\\", "\\\\")
    .replace("\"", "\\\"")
    .replace("\n", "\\n")
    .replace("\r", "\\r")
    .replace("\t", "\\t")

// endregion
