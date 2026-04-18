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
    fun `ct check warning - notifier postInfo invoked for CT_LOG`() = runBlocking {
        val worker = buildWorker(
            selfCertResult = SecurityCheckResult.Verified,
            ctResult = SecurityCheckResult.Warning("could not reach CT log")
        )

        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals("verified", prefs.selfCertResult())
        assertEquals("warning", prefs.ctLogResult())
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
    fun `network warning on both - notifier postInfo invoked for each check`() = runBlocking {
        val worker = buildWorker(
            selfCertResult = SecurityCheckResult.Warning("network unreachable"),
            ctResult = SecurityCheckResult.Warning("could not reach CT log")
        )

        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.success(), result)
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
