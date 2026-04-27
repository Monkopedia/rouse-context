package com.rousecontext.work

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import com.rousecontext.notifications.SecurityCheckNotifier
import com.rousecontext.notifications.SecurityCheckNotifier.SecurityCheck
import com.rousecontext.tunnel.SecurityCheckResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SecurityCheckWorkerTest {

    private lateinit var context: Context
    private lateinit var prefs: SecurityCheckPreferences
    private lateinit var fakeNotifier: FakeSecurityCheckNotifier

    @Before
    fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        prefs = SecurityCheckPreferences(context)
        prefs.clearResults()
        fakeNotifier = FakeSecurityCheckNotifier()
    }

    @Test
    fun `both checks pass - preferences updated with verified, no notifications`() = runBlocking {
        val worker = buildWorker(
            selfCertResult = SecurityCheckResult.Verified,
            ctResult = SecurityCheckResult.Verified
        )

        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals("verified", prefs.selfCertResult())
        assertEquals("verified", prefs.ctLogResult())
        assertTrue(prefs.lastCheckAt() > 0)
        assertEquals(emptyList<FakeSecurityCheckNotifier.Call>(), fakeNotifier.calls)
    }

    @Test
    fun `self cert alert - notifier postAlert invoked for SELF_CERT and preferences updated`() =
        runBlocking {
            val worker = buildWorker(
                selfCertResult = SecurityCheckResult.Alert("fingerprint mismatch"),
                ctResult = SecurityCheckResult.Verified
            )

            val result = worker.doWork()

            assertEquals(ListenableWorker.Result.success(), result)
            assertEquals("alert", prefs.selfCertResult())
            assertEquals("verified", prefs.ctLogResult())
            assertEquals(
                listOf(
                    FakeSecurityCheckNotifier.Call.Alert(
                        SecurityCheck.SELF_CERT,
                        "fingerprint mismatch"
                    )
                ),
                fakeNotifier.calls
            )
        }

    @Test
    fun `ct log alert - notifier postAlert invoked for CT_LOG and triggers alert gate`() =
        runBlocking {
            val worker = buildWorker(
                selfCertResult = SecurityCheckResult.Verified,
                ctResult = SecurityCheckResult.Alert("unexpected issuer Evil CA")
            )

            val result = worker.doWork()

            assertEquals(ListenableWorker.Result.success(), result)
            assertEquals("verified", prefs.selfCertResult())
            assertEquals("alert", prefs.ctLogResult())
            assertEquals(
                listOf(
                    FakeSecurityCheckNotifier.Call.Alert(
                        SecurityCheck.CT_LOG,
                        "unexpected issuer Evil CA"
                    )
                ),
                fakeNotifier.calls
            )

            // Simulate the alert gate used by McpSession (see AppModule.kt securityAlertCheck).
            val selfResult = prefs.selfCertResult()
            val ctResult = prefs.ctLogResult()
            val alertGateTriggered = selfResult == "alert" || ctResult == "alert"
            assertTrue(
                "CT log alert must trigger the same alert gate as SelfCertVerifier",
                alertGateTriggered
            )
        }

    @Test
    fun `ct check warning below threshold - no notification`() = runBlocking {
        // Issue #256: a single Warning from a source MUST NOT surface a user
        // notification. crt.sh routinely flaps, and the adjacent retry loop in
        // HttpCtLogFetcher will absorb most transients -- but a genuine flap
        // that survives retries should still debounce across worker runs.
        val worker = buildWorker(
            selfCertResult = SecurityCheckResult.Verified,
            ctResult = SecurityCheckResult.Warning("could not reach CT log")
        )

        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals("verified", prefs.selfCertResult())
        assertEquals("warning", prefs.ctLogResult())
        assertEquals(
            "Single warning MUST NOT fire a notification",
            emptyList<FakeSecurityCheckNotifier.Call>(),
            fakeNotifier.calls
        )
    }

    @Test
    fun `ct check warning fires notification only after threshold consecutive runs`() =
        runBlocking {
            // Three consecutive Warning runs must fire exactly one notification
            // (on the third run). Runs 1 and 2 stay silent.
            repeat(2) {
                val worker = buildWorker(
                    selfCertResult = SecurityCheckResult.Verified,
                    ctResult = SecurityCheckResult.Warning("could not reach CT log")
                )
                worker.doWork()
            }
            assertEquals(
                "Runs below threshold must stay silent",
                emptyList<FakeSecurityCheckNotifier.Call>(),
                fakeNotifier.calls
            )

            val worker = buildWorker(
                selfCertResult = SecurityCheckResult.Verified,
                ctResult = SecurityCheckResult.Warning("could not reach CT log")
            )
            worker.doWork()

            assertEquals(
                listOf(
                    FakeSecurityCheckNotifier.Call.Info(
                        SecurityCheck.CT_LOG,
                        "could not reach CT log"
                    )
                ),
                fakeNotifier.calls
            )
        }

    @Test
    fun `continued warning past threshold does not re-notify until streak breaks`() = runBlocking {
        // Issue #429: streak >= threshold fires once; subsequent runs while
        // the streak is unbroken must stay silent (logged only). User was
        // getting a CT-log Warning notification every interval while crt.sh
        // was flaking — the threshold reaches 3 once, then every later run
        // re-fired because nothing reset the "already notified" state.
        repeat(5) {
            val worker = buildWorker(
                selfCertResult = SecurityCheckResult.Verified,
                ctResult = SecurityCheckResult.Warning("could not reach CT log")
            )
            worker.doWork()
        }

        assertEquals(
            "Streak crossing threshold must fire exactly once, not on every subsequent run",
            listOf(
                FakeSecurityCheckNotifier.Call.Info(
                    SecurityCheck.CT_LOG,
                    "could not reach CT log"
                )
            ),
            fakeNotifier.calls
        )
    }

    @Test
    fun `verified after notified streak resets state and next streak fires again`() = runBlocking {
        // Issue #429: once the streak breaks via Verified, the notified
        // flag must clear so the NEXT streak crossing threshold can fire
        // a fresh notification. Otherwise the user would never hear about
        // a recurring upstream outage.
        repeat(3) {
            val worker = buildWorker(
                selfCertResult = SecurityCheckResult.Verified,
                ctResult = SecurityCheckResult.Warning("first outage")
            )
            worker.doWork()
        }

        val verifiedWorker = buildWorker(
            selfCertResult = SecurityCheckResult.Verified,
            ctResult = SecurityCheckResult.Verified
        )
        verifiedWorker.doWork()

        repeat(3) {
            val worker = buildWorker(
                selfCertResult = SecurityCheckResult.Verified,
                ctResult = SecurityCheckResult.Warning("second outage")
            )
            worker.doWork()
        }

        assertEquals(
            "First streak fires once; Verified resets; second streak fires once",
            listOf(
                FakeSecurityCheckNotifier.Call.Info(
                    SecurityCheck.CT_LOG,
                    "first outage"
                ),
                FakeSecurityCheckNotifier.Call.Info(
                    SecurityCheck.CT_LOG,
                    "second outage"
                )
            ),
            fakeNotifier.calls
        )
    }

    @Test
    fun `warning counter resets on verified result`() = runBlocking {
        // Two warning runs, then a verified run -- counter MUST reset so the
        // NEXT two warnings do not cross the threshold.
        repeat(2) {
            val w = buildWorker(
                selfCertResult = SecurityCheckResult.Verified,
                ctResult = SecurityCheckResult.Warning("flap")
            )
            w.doWork()
        }

        val verifiedWorker = buildWorker(
            selfCertResult = SecurityCheckResult.Verified,
            ctResult = SecurityCheckResult.Verified
        )
        verifiedWorker.doWork()

        // After the reset, one more warning should not fire (counter back to 1).
        val afterResetWorker = buildWorker(
            selfCertResult = SecurityCheckResult.Verified,
            ctResult = SecurityCheckResult.Warning("flap")
        )
        afterResetWorker.doWork()

        assertEquals(
            "Counter must have reset; single warning post-reset MUST NOT fire",
            emptyList<FakeSecurityCheckNotifier.Call>(),
            fakeNotifier.calls
        )
    }

    @Test
    fun `alert fires immediately regardless of warning debounce state`() = runBlocking {
        // Alerts are a different severity -- they MUST fire immediately even
        // when no prior warnings have accumulated. Debouncing applies only to
        // Warning, never to Alert.
        val worker = buildWorker(
            selfCertResult = SecurityCheckResult.Verified,
            ctResult = SecurityCheckResult.Alert("unexpected issuer Evil CA")
        )

        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(
            listOf(
                FakeSecurityCheckNotifier.Call.Alert(
                    SecurityCheck.CT_LOG,
                    "unexpected issuer Evil CA"
                )
            ),
            fakeNotifier.calls
        )
    }

    @Test
    fun `warning counter is per-source - self cert warnings do not block ct log`() = runBlocking {
        // Source counters MUST be independent: two self-cert warnings should
        // not push the ct-log counter toward its threshold.
        repeat(2) {
            val w = buildWorker(
                selfCertResult = SecurityCheckResult.Warning("self cert flap"),
                ctResult = SecurityCheckResult.Verified
            )
            w.doWork()
        }
        assertEquals(
            "Two self-cert warnings alone must stay below threshold",
            emptyList<FakeSecurityCheckNotifier.Call>(),
            fakeNotifier.calls
        )

        val worker = buildWorker(
            selfCertResult = SecurityCheckResult.Verified,
            ctResult = SecurityCheckResult.Warning("ct flap")
        )
        worker.doWork()

        assertEquals(
            "Fresh ct-log warning must not fire just because self-cert had two",
            emptyList<FakeSecurityCheckNotifier.Call>(),
            fakeNotifier.calls
        )
    }

    @Test
    fun `skipped on both - no notifications fired and preferences record skipped`() = runBlocking {
        // Issue #228: "no cert stored" / "no subdomain configured" must NOT
        // surface a notification -- that's a pre-onboarding state, not a
        // security issue.
        val worker = buildWorker(
            selfCertResult = SecurityCheckResult.Skipped("No certificate stored"),
            ctResult = SecurityCheckResult.Skipped("No subdomain configured")
        )

        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals("skipped", prefs.selfCertResult())
        assertEquals("skipped", prefs.ctLogResult())
        assertTrue(prefs.lastCheckAt() > 0)
        assertEquals(
            "Skipped result MUST NOT fire a notification",
            emptyList<FakeSecurityCheckNotifier.Call>(),
            fakeNotifier.calls
        )
    }

    @Test
    fun `network warning on both - both fire once threshold reached on each source`() =
        runBlocking {
            // Repeat three times: both sources cross their warning threshold on
            // the third run and each fires its own notification. Earlier runs
            // stay silent.
            repeat(3) {
                val worker = buildWorker(
                    selfCertResult = SecurityCheckResult.Warning("network unreachable"),
                    ctResult = SecurityCheckResult.Warning("could not reach CT log")
                )
                worker.doWork()
            }

            assertEquals("warning", prefs.selfCertResult())
            assertEquals("warning", prefs.ctLogResult())
            assertEquals(
                listOf(
                    FakeSecurityCheckNotifier.Call.Info(
                        SecurityCheck.SELF_CERT,
                        "network unreachable"
                    ),
                    FakeSecurityCheckNotifier.Call.Info(
                        SecurityCheck.CT_LOG,
                        "could not reach CT log"
                    )
                ),
                fakeNotifier.calls
            )
        }

    private fun buildWorker(
        selfCertResult: SecurityCheckResult,
        ctResult: SecurityCheckResult
    ): SecurityCheckWorker {
        val worker = TestListenableWorkerBuilder<SecurityCheckWorker>(context).build()
        worker.selfCertVerifier = StubSecurityCheck(selfCertResult)
        worker.ctLogMonitor = StubSecurityCheck(ctResult)
        worker.notifier = fakeNotifier
        worker.preferences = prefs
        return worker
    }
}

private class StubSecurityCheck(private val result: SecurityCheckResult) : SecurityCheckSource {
    override suspend fun check(): SecurityCheckResult = result
}

/**
 * Captures [SecurityCheckNotifier] invocations for assertion. Implements the interface
 * directly, so the worker only sees the public API and no actual system NotificationManager
 * calls are made.
 */
private class FakeSecurityCheckNotifier : SecurityCheckNotifier {
    sealed class Call {
        data class Alert(val check: SecurityCheck, val reason: String) : Call()

        data class Info(val check: SecurityCheck, val reason: String) : Call()
    }

    val calls = mutableListOf<Call>()

    override fun postAlert(check: SecurityCheck, reason: String) {
        calls += Call.Alert(check, reason)
    }

    override fun postInfo(check: SecurityCheck, reason: String) {
        calls += Call.Info(check, reason)
    }
}
