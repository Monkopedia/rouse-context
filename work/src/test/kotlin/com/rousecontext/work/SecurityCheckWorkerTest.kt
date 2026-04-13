package com.rousecontext.work

import android.app.NotificationManager
import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import com.rousecontext.tunnel.SecurityCheckResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class SecurityCheckWorkerTest {

    private lateinit var context: Context
    private lateinit var prefs: SharedPreferences
    private lateinit var notificationManager: NotificationManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        prefs = context.getSharedPreferences(
            SecurityCheckWorker.PREFS_NAME,
            Context.MODE_PRIVATE
        )
        prefs.edit().clear().commit()
        notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    @Test
    fun `both checks pass - preferences updated with verified`() = runBlocking {
        val worker = buildWorker(
            selfCertResult = SecurityCheckResult.Verified,
            ctResult = SecurityCheckResult.Verified
        )

        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals("verified", prefs.getString(SecurityCheckWorker.KEY_SELF_CERT_RESULT, null))
        assertEquals("verified", prefs.getString(SecurityCheckWorker.KEY_CT_LOG_RESULT, null))
        assert(prefs.getLong(SecurityCheckWorker.KEY_LAST_CHECK_TIME, 0L) > 0)
        assertEquals(0, shadowOf(notificationManager).size())
    }

    @Test
    fun `self cert alert - notification posted and preferences updated`() = runBlocking {
        val worker = buildWorker(
            selfCertResult = SecurityCheckResult.Alert("fingerprint mismatch"),
            ctResult = SecurityCheckResult.Verified
        )

        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals("alert", prefs.getString(SecurityCheckWorker.KEY_SELF_CERT_RESULT, null))
        assertEquals("verified", prefs.getString(SecurityCheckWorker.KEY_CT_LOG_RESULT, null))
        assertEquals(1, shadowOf(notificationManager).size())
    }

    @Test
    fun `ct log alert - preferences updated with alert and triggers alert gate`() = runBlocking {
        val worker = buildWorker(
            selfCertResult = SecurityCheckResult.Verified,
            ctResult = SecurityCheckResult.Alert("unexpected issuer Evil CA")
        )

        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals("verified", prefs.getString(SecurityCheckWorker.KEY_SELF_CERT_RESULT, null))
        assertEquals("alert", prefs.getString(SecurityCheckWorker.KEY_CT_LOG_RESULT, null))
        assertEquals(1, shadowOf(notificationManager).size())

        // Simulate the alert gate used by McpSession (see AppModule.kt securityAlertCheck).
        val selfResult = prefs.getString(SecurityCheckWorker.KEY_SELF_CERT_RESULT, "") ?: ""
        val ctResult = prefs.getString(SecurityCheckWorker.KEY_CT_LOG_RESULT, "") ?: ""
        val alertGateTriggered = selfResult == "alert" || ctResult == "alert"
        assert(alertGateTriggered) {
            "CT log alert must trigger the same alert gate as SelfCertVerifier"
        }
    }

    @Test
    fun `ct check warning - preferences updated with warning`() = runBlocking {
        val worker = buildWorker(
            selfCertResult = SecurityCheckResult.Verified,
            ctResult = SecurityCheckResult.Warning("could not reach CT log")
        )

        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals("verified", prefs.getString(SecurityCheckWorker.KEY_SELF_CERT_RESULT, null))
        assertEquals("warning", prefs.getString(SecurityCheckWorker.KEY_CT_LOG_RESULT, null))
        assertEquals(1, shadowOf(notificationManager).size())
    }

    @Test
    fun `network error on both - warning not alert`() = runBlocking {
        val worker = buildWorker(
            selfCertResult = SecurityCheckResult.Warning("network unreachable"),
            ctResult = SecurityCheckResult.Warning("could not reach CT log")
        )

        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals("warning", prefs.getString(SecurityCheckWorker.KEY_SELF_CERT_RESULT, null))
        assertEquals("warning", prefs.getString(SecurityCheckWorker.KEY_CT_LOG_RESULT, null))
        assertEquals(2, shadowOf(notificationManager).size())
    }

    private fun buildWorker(
        selfCertResult: SecurityCheckResult,
        ctResult: SecurityCheckResult
    ): SecurityCheckWorker {
        val worker = TestListenableWorkerBuilder<SecurityCheckWorker>(context).build()
        worker.selfCertVerifier = StubSecurityCheck(selfCertResult)
        worker.ctLogMonitor = StubSecurityCheck(ctResult)
        return worker
    }
}

private class StubSecurityCheck(private val result: SecurityCheckResult) : SecurityCheckSource {
    override suspend fun check(): SecurityCheckResult = result
}
