package com.rousecontext.app.ui.format

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Display-time date/time rendering for the app's own UI.
 *
 * Every formatter here renders the user's own data on the user's own device,
 * so device-local is the correct product behaviour: nothing is pinned to a
 * fixed zone or locale. What this object fixes (issue #635) is *when* the
 * device values are read.
 *
 * The sites this replaced held a `SimpleDateFormat` in a companion object (or
 * as a top-level `val`), and `SimpleDateFormat` resolves its [java.util.TimeZone]
 * and its [Locale] at **construction**. Because those statics are built once at
 * class initialisation, a **device timezone change** (travel, manual change) or
 * a **device locale change** kept rendering with whatever was installed at class
 * load, until the process restarted. No error, no crash — the timestamps were
 * simply wrong, which is the hardest kind of wrong to notice. For the
 * rate-limit retry-after dates that can name the wrong calendar day and tell
 * the user to come back at the wrong time.
 *
 * **A DST rollover was NOT one of the failure modes, despite what #635's
 * original report and this file's first draft both claimed.** A
 * `SimpleDateFormat` captures a *region* `TimeZone` (`ZoneInfo`, with
 * `useDaylightTime = true`), and `format()` applies that zone's transition
 * rules per call — so one long-lived instance already rendered a January
 * instant and a July instant at correctly different offsets. What it captured
 * was *which zone*, not *which offset*. Only changing the zone or the locale
 * out from under it went stale, and those are the two the tests cover. Stated
 * here because the false version was written down in four places before it was
 * measured, and this is the copy that outlives the issue and the pull request.
 *
 * The patterns stay static because a [DateTimeFormatter] is **immutable and
 * thread-safe**: [DateTimeFormatter.withZone] and [DateTimeFormatter.withLocale]
 * return new instances rather than mutating the shared one. Applying both per
 * call is what makes the rendering follow the device. That also retires a
 * **latent — not live** thread-safety hazard: `SimpleDateFormat` is not
 * thread-safe and `TIME_FORMAT` was shared across three ViewModels, but all
 * three read it from `stateIn(viewModelScope)` with no `flowOn`, so every call
 * was Main-confined and no interleaving could actually occur. Nothing enforced
 * that confinement; [DateTimeFormatter] makes it a non-question.
 *
 * Not for anything that leaves the device. Wire formats must carry an explicit
 * offset — see `UsageMcpProvider`'s `ISO_OFFSET_DATE_TIME`.
 */
internal object DisplayDateFormat {

    /** `2023-11-14 22:13:20.000` — audit-detail full timestamp. */
    private val AUDIT_TIMESTAMP: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")

    /** `22:13` — audit-history and dashboard row time. */
    private val CLOCK_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    /** `November 14, 2023` — audit-history day-group header. */
    private val DAY_HEADER: DateTimeFormatter = DateTimeFormatter.ofPattern("MMMM d, yyyy")

    /** `Nov 14` — rate-limit retry-after dates. */
    private val SHORT_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d")

    /** `Nov 14, 2023` — authorized-client dates. */
    private val SHORT_DATE_WITH_YEAR: DateTimeFormatter =
        DateTimeFormatter.ofPattern("MMM d, yyyy")

    fun auditTimestamp(epochMillis: Long): String = render(AUDIT_TIMESTAMP, epochMillis)

    fun clockTime(epochMillis: Long): String = render(CLOCK_TIME, epochMillis)

    fun shortDate(epochMillis: Long): String = render(SHORT_DATE, epochMillis)

    fun shortDateWithYear(epochMillis: Long): String = render(SHORT_DATE_WITH_YEAR, epochMillis)

    /**
     * Renders an already-resolved calendar day. The caller decides which day an
     * instant falls on (that needs the zone too — see [deviceZone]); this only
     * applies the device locale to the label.
     */
    fun dayHeader(date: LocalDate): String = DAY_HEADER.withLocale(deviceLocale()).format(date)

    /**
     * The device's current zone, read fresh. Use this — never a captured zone —
     * whenever an instant has to be bucketed into a calendar day.
     */
    fun deviceZone(): ZoneId = ZoneId.systemDefault()

    private fun render(formatter: DateTimeFormatter, epochMillis: Long): String = formatter
        .withLocale(deviceLocale())
        .withZone(deviceZone())
        .format(Instant.ofEpochMilli(epochMillis))

    private fun deviceLocale(): Locale = Locale.getDefault()
}
