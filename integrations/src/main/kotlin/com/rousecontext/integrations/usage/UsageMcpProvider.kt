package com.rousecontext.integrations.usage

import android.app.usage.UsageEvents
import android.app.usage.UsageStats
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import com.rousecontext.mcp.core.McpServerProvider
import com.rousecontext.mcp.tool.McpTool
import com.rousecontext.mcp.tool.ToolResult
import com.rousecontext.mcp.tool.registerTool
import io.modelcontextprotocol.kotlin.sdk.server.Server
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Calendar
import java.util.TimeZone
import kotlinx.serialization.encodeToString

/**
 * MCP server provider that exposes device usage statistics
 * via [UsageStatsManager].
 *
 * Tools are authored with the [McpTool] DSL; this provider just wires
 * dependencies and registers them.
 */
class UsageMcpProvider(private val context: Context) : McpServerProvider {

    override val id = "usage"
    override val displayName = "Usage Stats"

    override fun register(server: Server) {
        server.registerTool { GetUsageSummaryTool(context) }
        server.registerTool { GetAppUsageTool(context) }
        server.registerTool { GetUsageEventsTool(context) }
        server.registerTool { CompareUsageTool(context) }
    }

    companion object {
        internal const val DEFAULT_LIMIT = 10
        internal const val DEFAULT_EVENT_LIMIT = 50
        internal const val MS_PER_MIN = 60_000L

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
}

// ---------- tools ----------

internal class GetUsageSummaryTool(private val context: Context) : McpTool() {
    override val name = "get_usage_summary"
    override val description = "Screen time totals and top apps for a period."

    val period by stringParam("period", "today|yesterday|week|month")
    val limit by intParam("limit", "Max apps (default 10)").optional()

    override suspend fun execute(): ToolResult {
        val periodStr = period!!
        val lim = limit ?: UsageMcpProvider.DEFAULT_LIMIT
        val range = parsePeriod(periodStr)
            ?: return invalidPeriodError(periodStr)

        val usageStatsManager = context.getSystemService(UsageStatsManager::class.java)
        val stats = usageStatsManager.queryUsageStats(
            UsageStatsManager.INTERVAL_BEST,
            range.first,
            range.second
        )

        val merged = mergeByPackage(stats)
        val filtered = merged.entries
            .filter { (_, ms) -> ms > 0 }
            .sortedByDescending { (_, ms) -> ms }
            .take(lim)

        val totalMs = filtered.sumOf { (_, ms) -> ms }
        val apps = filtered.map { (pkg, ms) -> appUsageEntry(context, pkg, ms) }

        return ToolResult.Success(
            UsageJson.encodeToString(
                UsageSummary(
                    period = periodStr,
                    totalMinutes = totalMs / UsageMcpProvider.MS_PER_MIN,
                    apps = apps
                )
            )
        )
    }
}

internal class GetAppUsageTool(private val context: Context) : McpTool() {
    override val name = "get_app_usage"
    override val description = "Per-day usage for one app."

    val packageName by stringParam("package_name", "")
    val period by stringParam("period", "today|yesterday|week|month")

    override suspend fun execute(): ToolResult {
        val pkg = packageName!!
        val periodStr = period!!
        val range = parsePeriod(periodStr)
            ?: return invalidPeriodError(periodStr)

        val usageStatsManager = context.getSystemService(UsageStatsManager::class.java)
        val stats = usageStatsManager.queryUsageStats(
            UsageStatsManager.INTERVAL_DAILY,
            range.first,
            range.second
        )

        val appStats = stats.filter { it.packageName == pkg }
        val appName = resolveAppName(context, pkg)
        val totalMs = mergeByPackage(appStats)[pkg] ?: 0L
        val daily = dailyBreakdown(appStats)

        return ToolResult.Success(
            UsageJson.encodeToString(
                AppUsage(
                    `package` = pkg,
                    name = appName,
                    period = periodStr,
                    totalMinutes = totalMs / UsageMcpProvider.MS_PER_MIN,
                    daily = daily
                )
            )
        )
    }
}

internal class GetUsageEventsTool(private val context: Context) : McpTool() {
    override val name = "get_usage_events"
    override val description = "Raw app foreground/background events over a range."

    val since by stringParam("since", "today|yesterday|week|month")
    val until by stringParam("until", "today|yesterday|week|month")
    val packageFilter by stringParam("package", "").optional()
    val includeSystem by boolParam(
        "include_system",
        "Include Rouse/Android system events (default false)"
    ).optional()
    val limit by intParam("limit", "Default 50").optional()

    override suspend fun execute(): ToolResult {
        val sinceStr = since!!
        val untilStr = until!!
        val sinceRange = parsePeriod(sinceStr)
            ?: return usageError("Invalid since period: $sinceStr")
        val untilRange = parsePeriod(untilStr)
            ?: return usageError("Invalid until period: $untilStr")

        val usageStatsManager = context.getSystemService(UsageStatsManager::class.java)
        val events = usageStatsManager.queryEvents(sinceRange.first, untilRange.second)
        val entries = collectEvents(
            context = context,
            usageEvents = events,
            packageFilter = packageFilter,
            includeSystem = includeSystem ?: false,
            limit = limit ?: UsageMcpProvider.DEFAULT_EVENT_LIMIT
        )

        return ToolResult.Success(UsageJson.encodeToString(UsageEventList(events = entries)))
    }
}

internal class CompareUsageTool(private val context: Context) : McpTool() {
    override val name = "compare_usage"
    override val description = "Compare screen time between two periods; biggest deltas first."

    val period1 by stringParam("period1", "today|yesterday|week|month")
    val period2 by stringParam("period2", "today|yesterday|week|month")
    val limit by intParam("limit", "Max apps (default 10)").optional()

    override suspend fun execute(): ToolResult {
        val p1Str = period1!!
        val p2Str = period2!!
        val lim = limit ?: UsageMcpProvider.DEFAULT_LIMIT

        val r1 = parsePeriod(p1Str)
            ?: return invalidPeriodError(p1Str, "period1")
        val r2 = parsePeriod(p2Str)
            ?: return invalidPeriodError(p2Str, "period2")

        val usageStatsManager = context.getSystemService(UsageStatsManager::class.java)
        val stats1 = usageStatsManager.queryUsageStats(
            UsageStatsManager.INTERVAL_BEST,
            r1.first,
            r1.second
        )
        val stats2 = usageStatsManager.queryUsageStats(
            UsageStatsManager.INTERVAL_BEST,
            r2.first,
            r2.second
        )

        return buildComparisonResult(context, p1Str, p2Str, stats1, stats2, lim)
    }
}

// ---------- formatting helpers ----------

private fun appUsageEntry(
    context: Context,
    packageName: String,
    foregroundMs: Long
): AppUsageEntry {
    val name = resolveAppName(context, packageName)
    val mins = foregroundMs / UsageMcpProvider.MS_PER_MIN
    return AppUsageEntry(`package` = packageName, name = name, foregroundMinutes = mins)
}

private fun dailyBreakdown(appStats: List<UsageStats>): List<DailyUsage> {
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
    return byDate.entries.map { (date, ms) ->
        DailyUsage(date = date, foregroundMinutes = ms / UsageMcpProvider.MS_PER_MIN)
    }
}

@Suppress("LoopWithTooManyJumpStatements")
private fun collectEvents(
    context: Context,
    usageEvents: UsageEvents,
    packageFilter: String?,
    includeSystem: Boolean,
    limit: Int
): List<UsageEvent> {
    val entries = mutableListOf<UsageEvent>()
    val event = UsageEvents.Event()
    while (usageEvents.hasNextEvent() && entries.size < limit) {
        usageEvents.getNextEvent(event)
        val pkg = event.packageName
        if (packageFilter != null && pkg != packageFilter) continue
        if (!includeSystem && UsageMcpProvider.isFilteredPackage(pkg)) continue
        val typeName = eventTypeToString(event.eventType)
        val appName = resolveAppName(context, pkg)
        val isoTime = UsageMcpProvider.formatTimestamp(event.timeStamp)
        entries.add(
            UsageEvent(
                `package` = pkg,
                name = appName,
                type = typeName,
                timestamp = isoTime
            )
        )
    }
    return entries
}

private fun buildComparisonResult(
    context: Context,
    p1Str: String,
    p2Str: String,
    stats1: List<UsageStats>,
    stats2: List<UsageStats>,
    limit: Int
): ToolResult {
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

    val apps = changes.map { (pkg, t1, t2) ->
        val name = resolveAppName(context, pkg)
        val diff = (t2 - t1) / UsageMcpProvider.MS_PER_MIN
        val sign = if (diff >= 0) "+" else ""
        UsageDelta(
            `package` = pkg,
            name = name,
            period1Minutes = t1 / UsageMcpProvider.MS_PER_MIN,
            period2Minutes = t2 / UsageMcpProvider.MS_PER_MIN,
            change = "$sign$diff min"
        )
    }

    return ToolResult.Success(
        UsageJson.encodeToString(
            UsageComparison(
                period1 = p1Str,
                period2 = p2Str,
                total1Minutes = total1 / UsageMcpProvider.MS_PER_MIN,
                total2Minutes = total2 / UsageMcpProvider.MS_PER_MIN,
                apps = apps
            )
        )
    )
}

/**
 * Merge duplicate [UsageStats] entries by package name, summing foreground time.
 * [UsageStatsManager] may return multiple entries per package for different
 * internal time buckets; this collapses them into one entry per package.
 */
private fun mergeByPackage(stats: List<UsageStats>): Map<String, Long> =
    stats.groupBy { it.packageName }
        .mapValues { (_, entries) -> entries.sumOf { it.totalTimeInForeground } }

@Suppress("DEPRECATION")
private fun resolveAppName(context: Context, packageName: String): String = try {
    val info = context.packageManager.getApplicationInfo(packageName, 0)
    context.packageManager.getApplicationLabel(info).toString()
} catch (_: PackageManager.NameNotFoundException) {
    packageName
}

private fun invalidPeriodError(period: String, label: String = "period"): ToolResult =
    usageError("Invalid $label: $period. Use: today, yesterday, week, month")

internal fun usageError(message: String): ToolResult =
    ToolResult.Error(UsageJson.encodeToString(UsageError(error = message)))

// ---------- Period parsing ----------

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

// ---------- Event type mapping ----------

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
