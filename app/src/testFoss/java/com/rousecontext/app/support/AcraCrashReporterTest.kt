package com.rousecontext.app.support

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.acra.ACRA
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Tests for the `foss`-flavor [AcraCrashReporter] (issues #464, #542).
 *
 * Three separate properties are guarded, and they are deliberately split
 * because #542 made one test able to cover all three only by accident:
 *
 * 1. **Off-main reporting (#542)** — `handleSilentException` blocks inside
 *    ACRA's `CrashReportDataFactory.collect`. Callers report from
 *    `lifecycleScope` (`Dispatchers.Main.immediate`), so running collection on
 *    the caller's thread froze main for ~60s and produced a background ANR.
 *
 * 2. **The dispatched body actually runs, and a throw from it does not
 *    escape.** Dispatching into a `SupervisorJob` scope means an uncaught
 *    failure is not propagated to a parent — it reaches the thread's default
 *    uncaught handler and kills the process. The call sites are `catch`
 *    blocks, so an unhandled throw here would turn a handled error fatal.
 *
 * 3. **Graceful degradation (#464)** — ACRA returns a safe no-op
 *    `ErrorReporter` before `ACRA.init`, which the unit-test process never
 *    calls. That is a property of ACRA rather than of this class, so it is
 *    asserted directly against `ACRA.errorReporter` — see the kdoc on that
 *    test for why it is no longer asserted through [AcraCrashReporter].
 *
 * These use [Dispatchers.Unconfined] where the body must be observed, because
 * `launch` returns before the body runs on a real dispatcher: an earlier
 * version of this file cancelled the scope on the next statement and the
 * dispatched work never executed, so the tests passed no matter what the
 * reporter did.
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

    /**
     * The dispatched body must actually execute.
     *
     * Guards the failure this test file previously had: `launch` returns
     * before the body runs, so cancelling the scope on the next statement
     * cancelled the job while it was still queued and the reporter was never
     * invoked at all.
     */
    @Test
    fun `logCaughtException actually invokes the reporter`() = runBlocking {
        var invoked: Throwable? = null
        val boom = IllegalStateException("boom")
        val reporter = AcraCrashReporter(
            scope = CoroutineScope(coroutineContext),
            ioDispatcher = Dispatchers.Unconfined,
            reportSilently = { invoked = it }
        )

        reporter.logCaughtException(boom)

        assertTrue("reporter was never invoked — dispatched body did not run", invoked === boom)
        coroutineContext.cancelChildren()
    }

    /**
     * A throw from the reporter must not escape the coroutine.
     *
     * `scope` is built on a `SupervisorJob`, so an uncaught child failure is
     * not propagated to a parent — it goes to the thread's default uncaught
     * handler and kills the process. Every call site is a `catch` block, so
     * without this the crash reporter would convert a handled error into a
     * fatal one. Remove the `runCatching` in `logCaughtException` and this
     * test goes red: with `Unconfined` the throw surfaces synchronously here.
     */
    @Test
    fun `logCaughtException does not propagate a throwing reporter`() = runBlocking {
        val reporter = AcraCrashReporter(
            scope = CoroutineScope(coroutineContext),
            ioDispatcher = Dispatchers.Unconfined,
            reportSilently = { error("reporter exploded") }
        )

        reporter.logCaughtException(IllegalStateException("boom"))

        coroutineContext.cancelChildren()
    }

    /**
     * #464 guard, asserted where the property actually lives.
     *
     * This used to be `reporter.logCaughtException(...)` asserting "does not
     * throw". After #542 that assertion is true by construction — the call
     * dispatches and returns, and `runCatching` swallows anything the reporter
     * raises — so it could no longer distinguish a working ACRA stub from a
     * broken one. The real #464 property is that `ACRA.errorReporter` returns
     * a safe no-op before `ACRA.init`, so it is asserted against ACRA directly.
     */
    @Test
    fun `ACRA errorReporter degrades gracefully when uninitialized`() {
        ACRA.errorReporter.handleSilentException(IllegalStateException("boom"))
        ACRA.errorReporter.putCustomData("key", "value")
        ACRA.errorReporter.setEnabled(false)
        ACRA.errorReporter.setEnabled(true)
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
