package com.rousecontext.app.support

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.rousecontext.api.CrashReporter
import com.rousecontext.app.state.AppStatePreferences
import com.rousecontext.app.testing.RecordingCrashReporter
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Issue #546: crash reporting is opt-in on the FOSS distribution, and only
 * ever opt-in.
 *
 * These run against the real DataStore so the "survives a relaunch" case is a
 * genuine write-then-reread rather than a mock replaying a value.
 */
@RunWith(RobolectricTestRunner::class)
class CrashReportingPreferenceTest {

    private lateinit var context: Context

    @Before
    fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        AppStatePreferences(context).reset()
    }

    @Test
    fun `fresh install collects nothing`() = runTest {
        val reporter = RecordingCrashReporter()

        fossPreference(reporter).applyToReporter()

        assertEquals(listOf(false), reporter.collectionEnabledCalls)
    }

    @Test
    fun `fresh install has not been asked and has not opted in`() = runTest {
        val preference = fossPreference(RecordingCrashReporter())

        assertTrue(preference.shouldAskForConsent())
        assertFalse(AppStatePreferences(context).crashReportingOptIn())
    }

    @Test
    fun `opting in enables collection immediately`() = runTest {
        val reporter = RecordingCrashReporter()

        fossPreference(reporter).setOptIn(true)

        assertEquals(listOf(true), reporter.collectionEnabledCalls)
    }

    @Test
    fun `opting in persists the choice`() = runTest {
        fossPreference(RecordingCrashReporter()).setOptIn(true)

        assertTrue(AppStatePreferences(context).crashReportingOptIn())
    }

    /**
     * The trap recorded on #546: `RouseApplication.onCreate` re-affirms
     * collection on every launch. While that re-affirmation read
     * `BuildConfig.DEBUG`, a stored preference would have been overwritten on
     * the next start — a toggle that appears to work and silently resets.
     *
     * The second [CrashReportingPreference] here stands in for the next
     * process: new preference object, new [AppStatePreferences], new reporter,
     * reading the same on-disk store.
     */
    @Test
    fun `opting in survives a relaunch`() = runTest {
        fossPreference(RecordingCrashReporter()).setOptIn(true)

        val reporterAfterRelaunch = RecordingCrashReporter()
        fossPreference(reporterAfterRelaunch).applyToReporter()

        assertEquals(listOf(true), reporterAfterRelaunch.collectionEnabledCalls)
    }

    @Test
    fun `opting back out survives a relaunch`() = runTest {
        fossPreference(RecordingCrashReporter()).setOptIn(true)
        fossPreference(RecordingCrashReporter()).setOptIn(false)

        val reporterAfterRelaunch = RecordingCrashReporter()
        fossPreference(reporterAfterRelaunch).applyToReporter()

        assertEquals(listOf(false), reporterAfterRelaunch.collectionEnabledCalls)
    }

    @Test
    fun `marking the consent ask stops the sheet from returning`() = runTest {
        val preference = fossPreference(RecordingCrashReporter())

        preference.markConsentAsked()

        assertFalse(preference.shouldAskForConsent())
    }

    @Test
    fun `marking the consent ask does not turn collection on`() = runTest {
        val reporter = RecordingCrashReporter()
        val preference = fossPreference(reporter)

        preference.markConsentAsked()
        preference.applyToReporter()

        assertEquals(listOf(false), reporter.collectionEnabledCalls)
    }

    @Test
    fun `a debug build sends nothing even after opting in`() = runTest {
        val reporter = RecordingCrashReporter()

        fossPreference(reporter, isDebugBuild = true).setOptIn(true)

        assertEquals(listOf(false), reporter.collectionEnabledCalls)
    }

    /**
     * The google distribution keeps #233's behaviour: release builds collect,
     * and the consent surfaces are absent. Binding it to opt-in without also
     * shipping that UI would disable Crashlytics with no way to turn it back
     * on.
     */
    @Test
    fun `google keeps collecting by default and asks nothing`() = runTest {
        val reporter = RecordingCrashReporter()
        val preference = googlePreference(reporter)

        preference.applyToReporter()

        assertEquals(listOf(true), reporter.collectionEnabledCalls)
        assertFalse(preference.shouldAskForConsent())
        assertFalse(preference.isUserControlled)
    }

    @Test
    fun `foss exposes the user-facing control`() {
        assertTrue(fossPreference(RecordingCrashReporter()).isUserControlled)
    }

    private fun fossPreference(reporter: CrashReporter, isDebugBuild: Boolean = false) =
        CrashReportingPreference(
            preferences = AppStatePreferences(context),
            crashReporter = reporter,
            requiresOptIn = true,
            isDebugBuild = isDebugBuild
        )

    private fun googlePreference(reporter: CrashReporter) = CrashReportingPreference(
        preferences = AppStatePreferences(context),
        crashReporter = reporter,
        requiresOptIn = false,
        isDebugBuild = false
    )
}
