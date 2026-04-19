package com.rousecontext.app

import com.rousecontext.api.CrashReporter
import io.mockk.mockk
import io.mockk.verify
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Regression tests for issue #233: crash reporting collection must be gated
 * to the release build variant. Debug builds stay silent so local repros
 * don't pollute the Firebase console dashboard, and release builds collect
 * by default until the user opts out.
 *
 * `configureCrashReporting` is deliberately a pure function that just
 * forwards the build-variant decision to [CrashReporter.setCollectionEnabled],
 * so we can exercise it directly on an [RouseApplication] instance without
 * booting the rest of the Application lifecycle (Koin, FCM, WorkManager).
 */
@RunWith(RobolectricTestRunner::class)
class RouseApplicationCrashReportingTest {

    @Test
    fun `debug build disables Crashlytics collection`() {
        val crashReporter = mockk<CrashReporter>(relaxed = true)
        val application = RouseApplication()

        application.configureCrashReporting(
            crashReporter = crashReporter,
            isDebugBuild = true
        )

        verify(exactly = 1) { crashReporter.setCollectionEnabled(false) }
    }

    @Test
    fun `release build enables Crashlytics collection`() {
        val crashReporter = mockk<CrashReporter>(relaxed = true)
        val application = RouseApplication()

        application.configureCrashReporting(
            crashReporter = crashReporter,
            isDebugBuild = false
        )

        verify(exactly = 1) { crashReporter.setCollectionEnabled(true) }
    }
}
