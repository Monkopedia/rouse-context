package com.rousecontext.app.support

import com.google.firebase.crashlytics.FirebaseCrashlytics
import io.mockk.mockk
import io.mockk.verify
import org.junit.Test

/**
 * Ensures the thin Firebase wrapper forwards calls without adding its own
 * logic — so when the :api [com.rousecontext.api.CrashReporter] contract
 * surfaces a bug, it lives in one testable place (the interface) and not
 * scattered through the Firebase-backed impl.
 */
class FirebaseCrashReporterTest {

    @Test
    fun `logCaughtException forwards to FirebaseCrashlytics recordException`() {
        val crashlytics = mockk<FirebaseCrashlytics>(relaxed = true)
        val reporter = FirebaseCrashReporter { crashlytics }
        val cause = IllegalStateException("boom")

        reporter.logCaughtException(cause)

        verify(exactly = 1) { crashlytics.recordException(cause) }
    }

    @Test
    fun `log forwards breadcrumb message to FirebaseCrashlytics`() {
        val crashlytics = mockk<FirebaseCrashlytics>(relaxed = true)
        val reporter = FirebaseCrashReporter { crashlytics }

        reporter.log("cert provisioning step 3")

        verify(exactly = 1) { crashlytics.log("cert provisioning step 3") }
    }

    @Test
    fun `setCollectionEnabled forwards toggle state to FirebaseCrashlytics`() {
        val crashlytics = mockk<FirebaseCrashlytics>(relaxed = true)
        val reporter = FirebaseCrashReporter { crashlytics }

        reporter.setCollectionEnabled(false)
        reporter.setCollectionEnabled(true)

        verify(exactly = 1) { crashlytics.setCrashlyticsCollectionEnabled(false) }
        verify(exactly = 1) { crashlytics.setCrashlyticsCollectionEnabled(true) }
    }
}
