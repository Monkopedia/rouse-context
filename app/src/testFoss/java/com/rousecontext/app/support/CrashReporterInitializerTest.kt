package com.rousecontext.app.support

import org.acra.ACRAConstants
import org.acra.ReportField
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Regression guard for issue #583.
 *
 * `CrashReporterInitializer` used to express "don't collect logcat" as
 * `withLogcatArguments(emptyList())`. That reads like the field is off, but
 * ACRA gates each collector on `config.reportContent.contains(field)`
 * (`BaseReportFieldCollector.shouldCollect`) — the arguments are not a gate.
 * `LOGCAT` stayed in the report content, `LogCatCollector` still ran, and the
 * empty argument list stripped the `-t` that made the `logcat` subprocess
 * exit, so it streamed forever and the collector blocked on a read that never
 * saw EOF. On device that was an ANR loop roughly every 65 seconds.
 *
 * The comment claiming logcat was disabled is what let that survive from
 * 2026-06-14 to 2026-08-19, so the property is asserted against the built
 * [org.acra.config.CoreConfiguration] rather than trusted to prose.
 */
@RunWith(RobolectricTestRunner::class)
class CrashReporterInitializerTest {

    @Test
    fun `effective config excludes LOGCAT from the report content`() {
        val reportContent = CrashReporterInitializer.buildConfiguration().reportContent

        assertFalse(
            "ReportField.LOGCAT must not be in the report content — it is what runs " +
                "LogCatCollector, and a never-exiting `logcat` hangs the report (#583)",
            ReportField.LOGCAT in reportContent
        )
    }

    /**
     * Guards the assertion above against becoming vacuous.
     *
     * If a future ACRA release drops `LOGCAT` from its defaults, the exclusion
     * test would pass without the production code doing anything, and the
     * subtraction in `buildConfiguration` could be deleted unnoticed.
     */
    @Test
    fun `ACRA's own defaults still include LOGCAT`() {
        assertTrue(
            "ACRA no longer collects logcat by default; the LOGCAT exclusion in " +
                "CrashReporterInitializer is now a no-op and this guard has no teeth",
            ReportField.LOGCAT in ACRAConstants.DEFAULT_REPORT_FIELDS
        )
    }

    /**
     * The exclusion must be surgical: dropping one field, not narrowing the
     * report to the point where crashes are no longer triageable.
     */
    @Test
    fun `effective config keeps every other default field`() {
        val reportContent = CrashReporterInitializer.buildConfiguration().reportContent

        assertEquals(
            ACRAConstants.DEFAULT_REPORT_FIELDS - ReportField.LOGCAT,
            reportContent
        )
    }
}
