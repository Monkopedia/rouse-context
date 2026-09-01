package com.rousecontext.tunnel

import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking

/**
 * What [CtLogMonitor.check] does with a **cancellation** raised by one of the
 * suspend calls it guards (#646).
 *
 * Two of the three broad `catch (Exception)` clauses in `check` sit around a
 * suspend call, so a scope teardown mid-check came back as
 * [SecurityCheckResult.Warning] -- a security *finding* about a check that was
 * simply never run. `.claude/rules/coroutines.md` requires cancellation to
 * propagate.
 *
 * The layer directly below already gets this right: `CompositeCtLogFetcher`
 * rethrows cancellation at both of its catches. Swallowing it one frame up
 * defeats that entirely.
 *
 * No test here cancels the surrounding scope. A cancelled scope would make the
 * assertion ambiguous -- the test's own cancellation is indistinguishable from
 * the one under test -- so each collaborator raises cancellation directly while
 * the job stays active. That is also the shape a stray
 * `java.util.concurrent.CancellationException` takes (a cancelled `Future`
 * inside a store implementation), which is the one vector that survives the
 * three suspend `SecurityCheckPreferences` calls sitting between `check()`
 * returning and `SecurityCheckWorker` reaching `notifier.postInfo`.
 *
 * Each site is pinned in both directions: cancellation must come back as
 * cancellation, and an ordinary I/O failure must still come back as a Warning.
 *
 * The third catch (`json.decodeFromString`) is deliberately unguarded and has
 * no test here: `decodeFromString` is not a suspend function, so no coroutine
 * cancellation can reach that clause. A rethrow there would be dead code of
 * exactly the kind #646's comments warn about.
 */
class CtLogMonitorCancellationTest {

    @Test
    fun `cancellation from getSubdomain propagates instead of becoming a Warning`() = runBlocking {
        val monitor = monitorOver(
            CancellingSubdomainStore(CancellationException("worker scope torn down"))
        )

        val thrown = assertFailsWith<Throwable> { monitor.check() }

        assertTrue(
            thrown is CancellationException,
            "a cancelled CT check must propagate cancellation, got " +
                "${thrown.javaClass.name}: ${thrown.message}"
        )
    }

    @Test
    fun `an IO failure from getSubdomain is still reported as a Warning`(): Unit = runBlocking {
        val monitor = monitorOver(CancellingSubdomainStore(IOException("keystore unavailable")))

        val result = monitor.check()

        assertIs<SecurityCheckResult.Warning>(result)
        assertEquals(
            "Could not retrieve subdomain: keystore unavailable",
            result.reason,
            "the ordinary failure path must be unchanged"
        )
    }

    @Test
    fun `cancellation from the CT log fetcher propagates instead of becoming a Warning`() =
        runBlocking {
            val monitor = monitorOver(
                SecurityCertificateStore(subdomain = "abc123"),
                ThrowingCtLogFetcher(CancellationException("worker scope torn down"))
            )

            val thrown = assertFailsWith<Throwable> { monitor.check() }

            assertTrue(
                thrown is CancellationException,
                "a cancelled CT fetch must propagate cancellation, got " +
                    "${thrown.javaClass.name}: ${thrown.message}"
            )
        }

    @Test
    fun `an IO failure from the CT log fetcher is still reported as a Warning`(): Unit =
        runBlocking {
            val monitor = monitorOver(
                SecurityCertificateStore(subdomain = "abc123"),
                ThrowingCtLogFetcher(IOException("crt.sh unreachable"))
            )

            val result = monitor.check()

            assertIs<SecurityCheckResult.Warning>(result)
            assertEquals(
                "Could not reach CT log service: crt.sh unreachable",
                result.reason,
                "the ordinary failure path must be unchanged"
            )
        }

    private fun monitorOver(store: CertificateStore, fetcher: CtLogFetcher = FakeCtLogFetcher()) =
        CtLogMonitor(
            certificateStore = store,
            ctLogFetcher = fetcher,
            expectedIssuers = setOf("C=US, O=Google Trust Services, CN=WE1"),
            baseDomain = "rousecontext.com"
        )

    /** Store whose `getSubdomain` raises whatever the test hands it. */
    private class CancellingSubdomainStore(private val failure: Throwable) :
        CertificateStore by SecurityCertificateStore() {
        override suspend fun getSubdomain(): String? = throw failure
    }

    /** Fetcher that raises whatever the test hands it. */
    private class ThrowingCtLogFetcher(private val failure: Throwable) : CtLogFetcher {
        override suspend fun fetch(domain: String): String = throw failure
    }
}
