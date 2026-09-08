package com.rousecontext.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.rousecontext.api.CrashReporter
import com.rousecontext.app.state.AppStatePreferences
import com.rousecontext.app.support.CrashReportingPreference
import com.rousecontext.app.testing.RecordingCrashReporter
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Regression tests for the app-start crash-reporting hook (issues #233, #546).
 *
 * `RouseApplication.onCreate` re-affirms collection on every launch. It used to
 * re-affirm it from `BuildConfig.DEBUG`, which is what made a Settings toggle
 * unimplementable: the stored choice would be overwritten on the next start.
 * These tests pin that the startup hook now reads the STORED preference, so a
 * relaunch preserves the user's answer instead of resetting it.
 *
 * `configureCrashReporting` is called directly on a [RouseApplication] instance
 * rather than through the Application lifecycle, so no Koin / WorkManager / FCM
 * boot is needed.
 */
@RunWith(RobolectricTestRunner::class)
class RouseApplicationCrashReportingTest {

    private lateinit var context: Context

    @Before
    fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        AppStatePreferences(context).reset()
    }

    @Test
    fun `app start leaves collection off when the user has never opted in`() = runTest {
        val crashReporter = RecordingCrashReporter()

        RouseApplication().configureCrashReporting(fossPreference(crashReporter))

        assertEquals(listOf(false), crashReporter.collectionEnabledCalls)
    }

    @Test
    fun `app start re-enables collection the user opted into`() = runTest {
        fossPreference(RecordingCrashReporter()).setOptIn(true)

        val crashReporter = RecordingCrashReporter()
        RouseApplication().configureCrashReporting(fossPreference(crashReporter))

        assertEquals(listOf(true), crashReporter.collectionEnabledCalls)
    }

    @Test
    fun `app start keeps google collecting by default`() = runTest {
        val crashReporter = RecordingCrashReporter()

        RouseApplication().configureCrashReporting(
            CrashReportingPreference(
                preferences = AppStatePreferences(context),
                crashReporter = crashReporter,
                requiresOptIn = false,
                isDebugBuild = false
            )
        )

        assertEquals(listOf(true), crashReporter.collectionEnabledCalls)
    }

    @Test
    fun `app start never collects in a debug build`() = runTest {
        fossPreference(RecordingCrashReporter()).setOptIn(true)

        val crashReporter = RecordingCrashReporter()
        RouseApplication().configureCrashReporting(
            fossPreference(crashReporter, isDebugBuild = true)
        )

        assertEquals(listOf(false), crashReporter.collectionEnabledCalls)
    }

    private fun fossPreference(crashReporter: CrashReporter, isDebugBuild: Boolean = false) =
        CrashReportingPreference(
            preferences = AppStatePreferences(context),
            crashReporter = crashReporter,
            requiresOptIn = true,
            isDebugBuild = isDebugBuild
        )
}
