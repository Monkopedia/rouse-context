package com.rousecontext.app.support

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.acra.ACRA
import org.acra.builder.ReportExecutor
import org.acra.reporter.ErrorReporterImpl
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Issue #546: ACRA must come out of [CrashReporterInitializer.initialize]
 * **disabled**.
 *
 * `initialize` runs in `Application.attachBaseContext`; the DataStore-backed
 * re-affirmation is a posted message plus an async preference read later. While
 * this line read `!BuildConfig.DEBUG`, a crash inside that window would be
 * reported from a release build without consent.
 *
 * ## Why this reads a private field
 *
 * [org.acra.ErrorReporter] exposes `setEnabled` and no getter, and the obvious
 * behavioural probe does not work: measured under Robolectric,
 * `handleSilentException` on an **enabled** reporter writes no report file at
 * all, so counting ACRA's on-disk queue passes identically either way — a green
 * that proves nothing. `ReportExecutor.isEnabled` is public and is the flag
 * `setEnabled` actually writes; only the hop to it is private. If ACRA renames
 * either, this test throws rather than quietly passing.
 *
 * ## Why the control is in the same test method
 *
 * `ACRA.init` is process-global and one-shot — `initialize` returns early once
 * `ACRA.isInitialised` — and Robolectric shares that static state across the
 * methods of a class. Split across two `@Test`s, whichever ran second observed
 * the first one's ACRA. One method fixes the order by construction.
 *
 * ## What this test cannot catch
 *
 * `BuildConfig.DEBUG` is `true` under `testDebugUnitTest`, so the pre-#546
 * expression `!BuildConfig.DEBUG` evaluates to `false` here and is
 * indistinguishable from the correct value. The release variant — the one that
 * shipped the defect — is the one no unit test runs. That is why the production
 * line is an unconditional `false` with no build check to get wrong.
 */
@RunWith(RobolectricTestRunner::class)
class CrashReporterInitializerDisabledTest {

    @Test
    fun `initialize leaves ACRA disabled, and the probe can see it enabled`() {
        val application: Application = ApplicationProvider.getApplicationContext()
        assertFalse(
            "something initialised ACRA before this test, so `initialize` will " +
                "return early and the assertion below would mean nothing",
            ACRA.isInitialised
        )

        CrashReporterInitializer.initialize(application)

        assertFalse(
            "ACRA was left collecting before the user was ever asked (#546)",
            collectionEnabled()
        )

        // Negative control. Without it, a probe that always answered `false` —
        // a renamed flag read through a stale default, a reporter that never
        // initialised — would report the property as held while observing
        // nothing.
        ACRA.errorReporter.setEnabled(true)
        assertTrue(
            "the enabled-state probe never observes `true`, so the assertion " +
                "above is vacuous",
            collectionEnabled()
        )
    }

    /**
     * The flag `ErrorReporter.setEnabled` writes. Reached through
     * `ErrorReporterImpl.reportExecutor`, which is private; the `isEnabled`
     * accessor on the executor itself is public.
     */
    private fun collectionEnabled(): Boolean {
        val reporter = ACRA.errorReporter as ErrorReporterImpl
        val field = ErrorReporterImpl::class.java.getDeclaredField("reportExecutor")
        field.isAccessible = true
        return (field.get(reporter) as ReportExecutor).isEnabled
    }
}
