package com.rousecontext.app.support

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Tests for the `foss`-flavor [AcraCrashReporter] (issues #464, #542).
 *
 * Two contracts are guarded here:
 *
 * 1. **Graceful degradation (#464)** — the reporter forwards to ACRA's static
 *    [org.acra.ACRA.errorReporter] without ever throwing, even when ACRA has
 *    NOT been initialized (the unit-test process never calls `ACRA.init`).
 *    ACRA returns a safe no-op `ErrorReporter` stub before init, so a
 *    caught-exception report, a breadcrumb, or a collection-toggle from app
 *    code degrades gracefully rather than crashing the very flows it observes.
 *
 * 2. **Off-main reporting (#542)** — `handleSilentException` blocks the calling
 *    thread inside ACRA's `CrashReportDataFactory.collect`. Callers report from
 *    `lifecycleScope` (`Dispatchers.Main.immediate`), so doing that work on the
 *    caller's thread froze main for ~60s and produced a background ANR. The
 *    reporter must never run collection on the caller's thread.
 *
 * Full end-to-end report delivery (ACRA init in attachBaseContext → HttpSender
 * → relay `/crash`) is exercised by the relay's crash-endpoint tests; here we
 * only guard the device-side binding.
 */
@RunWith(RobolectricTestRunner::class)
class AcraCrashReporterTest {

    /**
     * #542 regression guard.
     *
     * Asserts on thread *identity* rather than elapsed time: the defect is
     * "collection runs on the caller's thread", and identity states that
     * directly. It also means the pre-fix implementation fails by assertion
     * rather than by hanging — a fake that blocked to prove the point would
     * deadlock the old code and read as a flake rather than a failure.
     */
    @Test
    fun `logCaughtException does not run ACRA collection on the caller's thread`() = runBlocking {
        val reportingThread = CompletableDeferred<String>()
        val reporter = AcraCrashReporter(
            scope = CoroutineScope(coroutineContext),
            ioDispatcher = Dispatchers.IO,
            reportSilently = { reportingThread.complete(Thread.currentThread().name) }
        )

        val callingThread = Thread.currentThread().name
        reporter.logCaughtException(IllegalStateException("boom"))

        val actual = withTimeout(TIMEOUT_MS) { reportingThread.await() }
        assertNotEquals(
            "ACRA collection blocks; it must not run on the caller's thread (#542)",
            callingThread,
            actual
        )

        coroutineContext.cancelChildren()
    }

    @Test
    fun `logCaughtException does not throw when ACRA is uninitialized`() = runBlocking {
        val reporter = AcraCrashReporter(scope = CoroutineScope(coroutineContext))
        reporter.logCaughtException(IllegalStateException("boom"))
        coroutineContext.cancelChildren()
    }

    @Test
    fun `log breadcrumb does not throw when ACRA is uninitialized`() = runBlocking {
        val reporter = AcraCrashReporter(scope = CoroutineScope(coroutineContext))
        reporter.log("reached checkpoint A")
        reporter.log("reached checkpoint B")
        coroutineContext.cancelChildren()
    }

    @Test
    fun `setCollectionEnabled does not throw when ACRA is uninitialized`() = runBlocking {
        val reporter = AcraCrashReporter(scope = CoroutineScope(coroutineContext))
        reporter.setCollectionEnabled(true)
        reporter.setCollectionEnabled(false)
        coroutineContext.cancelChildren()
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
    }
}
