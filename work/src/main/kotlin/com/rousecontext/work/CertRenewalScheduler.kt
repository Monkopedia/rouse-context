package com.rousecontext.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Enqueues the [CertRenewalWorker] — both the daily periodic job and delayed retries
 * requested by the relay's `retry_after` semantics.
 *
 * Constraints:
 * - Unmetered network preferred to avoid incidental mobile data costs for a background job.
 * - `requiresBatteryNotLow=true` so the check doesn't fire on a dying phone.
 * - Charging is NOT required: the cert MUST be renewed even if the device never charges inside
 *   the renewal window.
 *
 * WorkManager's own linear backoff handles transient network errors; the explicit delayed
 * reschedule exists so we can honor server-mandated retry-after values precisely.
 */
object CertRenewalScheduler {

    /** Enqueue the periodic daily renewal check. Safe to call every app startup. */
    fun enqueuePeriodic(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true)
            .build()
        val request = PeriodicWorkRequestBuilder<CertRenewalWorker>(
            PERIODIC_INTERVAL_HOURS,
            TimeUnit.HOURS,
            PERIODIC_FLEX_HOURS,
            TimeUnit.HOURS
        )
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.LINEAR, BACKOFF_MINUTES, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            CertRenewalWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }

    /**
     * Enqueue a delayed one-time renewal attempt, used when the relay rate-limits us and
     * provides a specific `retry_after`.
     */
    fun enqueueDelayed(context: Context, delaySeconds: Long) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true)
            .build()
        val request = OneTimeWorkRequestBuilder<CertRenewalWorker>()
            .setInitialDelay(delaySeconds, TimeUnit.SECONDS)
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            CertRenewalWorker.WORK_NAME_DELAYED,
            ExistingWorkPolicy.REPLACE,
            request
        )
    }

    private const val PERIODIC_INTERVAL_HOURS = 24L
    private const val PERIODIC_FLEX_HOURS = 4L
    private const val BACKOFF_MINUTES = 15L
}
