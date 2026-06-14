package com.rousecontext.app.support

import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Smoke tests for the `foss`-flavor [AcraCrashReporter] (issue #464).
 *
 * These assert the contract that matters in practice: the reporter forwards to
 * ACRA's static [org.acra.ACRA.errorReporter] without ever throwing, even when
 * ACRA has NOT been initialized (the unit-test process never calls
 * `ACRA.init`). ACRA returns a safe no-op `ErrorReporter` stub before init, so
 * a caught-exception report, a breadcrumb, or a collection-toggle from app code
 * degrades gracefully rather than crashing the very flows it's meant to observe.
 *
 * Full end-to-end report delivery (ACRA init in attachBaseContext → HttpSender
 * → relay `/crash`) is exercised by the relay's crash-endpoint tests; here we
 * only guard the device-side binding.
 */
@RunWith(RobolectricTestRunner::class)
class AcraCrashReporterTest {

    private val reporter = AcraCrashReporter()

    @Test
    fun `logCaughtException does not throw when ACRA is uninitialized`() {
        reporter.logCaughtException(IllegalStateException("boom"))
    }

    @Test
    fun `log breadcrumb does not throw when ACRA is uninitialized`() {
        reporter.log("reached checkpoint A")
        reporter.log("reached checkpoint B")
    }

    @Test
    fun `setCollectionEnabled does not throw when ACRA is uninitialized`() {
        reporter.setCollectionEnabled(true)
        reporter.setCollectionEnabled(false)
    }
}
