package com.rousecontext.app.ui.screenshots

import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Guards the repo-wide test-JVM timezone pin declared in the root
 * `build.gradle.kts` (issue #633).
 *
 * Several *display* formatters inherit the JVM default zone on purpose —
 * `SimpleDateFormat(pattern, Locale.getDefault())` in `AuditDetailScreen` and
 * `AuditHistoryViewModel`, `ZoneId.systemDefault()` in the audit-history day
 * grouping, and the `MMM d` / `MMM d, yyyy` formatters in the onboarding and
 * integration view models. Device-local time is the right product behaviour, so
 * those stay as they are; the determinism has to come from the test JVM.
 *
 * Without the pin the goldens are deterministic per host but different across
 * hosts, so `recordRoborazziDebug` on a non-UTC machine looks clean locally and
 * fails `verifyRoborazziDebug` on a UTC CI runner — the failure mode that took
 * three investigations to identify. Only `AuditDetailScreen`'s formatter reaches
 * a golden today; the rest are latent and would surface the same way the moment
 * a fixture or a screen starts rendering them. Asserting the JVM default zone
 * covers all of them at once, and turns "somebody deleted the pin" into an
 * immediate local failure instead of a mystery red board later.
 */
class ScreenshotTimeZonePinTest {

    @Test
    fun `test JVM default timezone is pinned to UTC`() {
        assertEquals(
            "Test JVM default timezone is not UTC. The `user.timezone` pin in the root " +
                "build.gradle.kts (issue #633) is missing or overridden, so screenshot " +
                "goldens recorded here will not match goldens recorded elsewhere.",
            "UTC",
            TimeZone.getDefault().id
        )
    }
}
