package com.rousecontext.integrations.common

import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * A resolved time range for a `period` argument: `[start, end]`.
 *
 * Shared shape across MCP providers. Health Connect consumes [Instant]s
 * directly; Usage and Notification providers use [startMillis] / [endMillis].
 */
data class PeriodRange(val start: Instant, val end: Instant) {
    val startMillis: Long get() = start.toEpochMilli()
    val endMillis: Long get() = end.toEpochMilli()
}

/**
 * Single source of truth for parsing the `period` argument shared by the
 * usage, notification, and Health Connect MCP providers.
 *
 * Semantics (see issue #496):
 * - **Zone:** local ([ZoneId.systemDefault]) by default.
 * - **Anchoring:** a sliding window ending at `now`, anchored to the start of
 *   today in the given zone:
 *     - `today` = `[start-of-today, now]`
 *     - `week`  = `[start-of-today - 7 days, now]`
 *     - `month` = `[start-of-today - 30 days, now]`
 *
 * `yesterday` is intentionally NOT handled here — it is a usage-only extra and
 * is resolved at that call site before delegating.
 */
object PeriodParser {

    const val WEEK_DAYS = 7L
    const val MONTH_DAYS = 30L

    /**
     * Human-readable description of the unified semantics, suitable for MCP
     * tool param docs so calling agents know the zone and anchoring.
     */
    const val PERIOD_DESCRIPTION =
        "today|week|month - local time; week/month = last 7/30 days from the start of today"

    /**
     * Resolve [period] into a [PeriodRange], or null if unrecognised.
     *
     * @param zone the zone whose start-of-today anchors the window (default local).
     * @param now the upper bound of the range (default [Instant.now]); the same
     *   instant also determines which local date counts as "today".
     */
    fun parse(
        period: String,
        zone: ZoneId = ZoneId.systemDefault(),
        now: Instant = Instant.now()
    ): PeriodRange? {
        val startOfToday = now.atZone(zone).toLocalDate().atStartOfDay(zone).toInstant()
        val start = when (period) {
            "today" -> startOfToday
            "week" -> startOfToday.minus(WEEK_DAYS, ChronoUnit.DAYS)
            "month" -> startOfToday.minus(MONTH_DAYS, ChronoUnit.DAYS)
            else -> return null
        }
        return PeriodRange(start, now)
    }
}
