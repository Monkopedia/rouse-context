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

        val filtered = stats
            .filter { it.totalTimeInForeground > 0 }
            .sortedByDescending { it.totalTimeInForeground }
            .take(limit)

        val totalMs = filtered.sumOf { it.totalTimeInForeground }
        val apps = filtered.joinToString(",") { it.toJson() }

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
        val totalMs = appStats.sumOf { it.totalTimeInForeground }
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
        val limit = request.intArg("limit") ?: DEFAULT_EVENT_LIMIT

        val sinceRange = parsePeriod(sinceStr)
            ?: return errorResult("Invalid since period: $sinceStr")
        val untilRange = parsePeriod(untilStr)
            ?: return errorResult("Invalid until period: $untilStr")

        val events = usageStatsManager.queryEvents(
            sinceRange.first,
            untilRange.second
        )
        val entries = collectEvents(events, packageFilter, limit)

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

    private fun UsageStats.toJson(): String {
        val name = resolveAppName(packageName)
        val mins = totalTimeInForeground / MS_PER_MIN
        return """{"package":"${packageName.escapeJson()}",""" +
            """"name":"${name.escapeJson()}",""" +
            """"foreground_minutes":$mins}"""
    }

    private fun formatDailyBreakdown(appStats: List<UsageStats>): String = appStats
        .filter { it.totalTimeInForeground > 0 }
        .joinToString(",") { stat ->
            val cal = Calendar.getInstance(TimeZone.getDefault())
            cal.timeInMillis = stat.firstTimeStamp
            val date = "%04d-%02d-%02d".format(
                cal.get(Calendar.YEAR),
                cal.get(Calendar.MONTH) + 1,
                cal.get(Calendar.DAY_OF_MONTH)
            )
            val mins = stat.totalTimeInForeground / MS_PER_MIN
            """{"date":"$date","foreground_minutes":$mins}"""
        }

    private fun collectEvents(
        usageEvents: UsageEvents,
        packageFilter: String?,
        limit: Int
    ): List<String> {
        val entries = mutableListOf<String>()
        val event = UsageEvents.Event()
        while (usageEvents.hasNextEvent() && entries.size < limit) {
            usageEvents.getNextEvent(event)
            val pkg = event.packageName
            if (packageFilter != null && pkg != packageFilter) continue
            val typeName = eventTypeToString(event.eventType)
            val appName = resolveAppName(pkg)
            entries.add(
                """{"package":"${pkg.escapeJson()}",""" +
                    """"name":"${appName.escapeJson()}",""" +
                    """"type":"$typeName",""" +
                    """"timestamp":${event.timeStamp}}"""
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
        val map1 = stats1.associate { it.packageName to it.totalTimeInForeground }
        val map2 = stats2.associate { it.packageName to it.totalTimeInForeground }

        val changes = (map1.keys + map2.keys)
            .map { pkg ->
                Triple(pkg, map1[pkg] ?: 0L, map2[pkg] ?: 0L)
            }
            .filter { (_, t1, t2) -> t1 > 0 || t2 > 0 }
            .sortedByDescending { (_, t1, t2) ->
                kotlin.math.abs(t2 - t1)
            }
            .take(limit)

        val total1 = stats1.sumOf { it.totalTimeInForeground }
        val total2 = stats2.sumOf { it.totalTimeInForeground }

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

private fun eventTypeToString(type: Int): String = when (type) {
    UsageEvents.Event.ACTIVITY_RESUMED -> "resumed"
    UsageEvents.Event.ACTIVITY_PAUSED -> "paused"
    UsageEvents.Event.ACTIVITY_STOPPED -> "stopped"
    UsageEvents.Event.CONFIGURATION_CHANGE -> "config_change"
    UsageEvents.Event.USER_INTERACTION -> "user_interaction"
    UsageEvents.Event.FOREGROUND_SERVICE_START -> "fg_service_start"
    UsageEvents.Event.FOREGROUND_SERVICE_STOP -> "fg_service_stop"
    UsageEvents.Event.SCREEN_INTERACTIVE -> "screen_on"
    UsageEvents.Event.SCREEN_NON_INTERACTIVE -> "screen_off"
    UsageEvents.Event.DEVICE_SHUTDOWN -> "shutdown"
    UsageEvents.Event.DEVICE_STARTUP -> "startup"
    else -> "unknown($type)"
}

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

private fun String.escapeJson(): String = replace("\\", "\\\\")
    .replace("\"", "\\\"")
    .replace("\n", "\\n")
    .replace("\r", "\\r")
    .replace("\t", "\\t")

// endregion
