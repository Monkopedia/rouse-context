package com.rousecontext.work

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Issue #330: verify the periodic [SecurityCheckWorker] is scheduled with a
 * network constraint so it doesn't fire while the device is offline or behind
 * a captive portal. Without `NetworkType.CONNECTED` the worker runs, issues
 * an HTTP request, and captive-portal HTML comes back as a "crt.sh returned
 * HTTP 404" notification — a false positive that spams users on every metro
 * ride or hotel check-in.
 *
 * Mirrors [CertRenewalSchedulerTest] — the renewal worker already had the
 * right constraints and is the pattern we're bringing the security check in
 * line with.
 */
@RunWith(RobolectricTestRunner::class)
class SecurityCheckSchedulerTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var workManager: WorkManager

    @Before
    fun setUp() {
        val config = Configuration.Builder()
            .setExecutor(SynchronousExecutor())
            .setTaskExecutor(SynchronousExecutor())
            .setMinimumLoggingLevel(android.util.Log.DEBUG)
            .build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, config)
        workManager = WorkManager.getInstance(context)
    }

    @After
    fun tearDown() {
        workManager.cancelAllWork().result.get()
    }

    @Test
    fun `enqueuePeriodic schedules unique periodic work with network and battery constraints`() {
        SecurityCheckScheduler.enqueuePeriodic(
            context,
            intervalHours = DEFAULT_INTERVAL_HOURS,
            flexHours = DEFAULT_FLEX_HOURS
        )

        val infos = workManager
            .getWorkInfosForUniqueWork(SecurityCheckScheduler.WORK_NAME)
            .get()

        assertEquals(
            "SecurityCheckScheduler.enqueuePeriodic must create exactly one unique " +
                "work entry under `${SecurityCheckScheduler.WORK_NAME}`",
            1,
            infos.size
        )
        val info = infos.single()
        assertEquals(
            "Freshly-enqueued periodic work should be ENQUEUED (waiting for next run)",
            WorkInfo.State.ENQUEUED,
            info.state
        )
        val constraints = info.constraints
        assertEquals(
            "Security check must require network connectivity — otherwise crt.sh " +
                "lookups hit captive-portal HTML and report false positives (issue #330)",
            NetworkType.CONNECTED,
            constraints.requiredNetworkType
        )
        assertTrue(
            "Security check must not fire on a dying phone — the notification is " +
                "background noise during low-battery states",
            constraints.requiresBatteryNotLow()
        )
        assertFalse(
            "Charging must NOT be required — this is a routine monitoring check, not " +
                "a power-hungry task that needs to wait for the charger",
            constraints.requiresCharging()
        )
    }

    @Test
    fun `enqueuePeriodic replaces existing periodic work in the same slot`() {
        SecurityCheckScheduler.enqueuePeriodic(
            context,
            intervalHours = DEFAULT_INTERVAL_HOURS,
            flexHours = DEFAULT_FLEX_HOURS
        )
        SecurityCheckScheduler.enqueuePeriodic(
            context,
            intervalHours = DEFAULT_INTERVAL_HOURS,
            flexHours = DEFAULT_FLEX_HOURS
        )

        val infos = workManager
            .getWorkInfosForUniqueWork(SecurityCheckScheduler.WORK_NAME)
            .get()

        assertEquals(
            "Re-enqueueing must keep the slot at exactly one entry (UPDATE policy)",
            1,
            infos.size
        )
    }

    private companion object {
        const val DEFAULT_INTERVAL_HOURS = 24
        const val DEFAULT_FLEX_HOURS = 6
    }
}
