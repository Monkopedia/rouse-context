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
import com.rousecontext.tunnel.CertificateStore
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

    /**
     * Enqueue a one-time renewal immediately if the stored cert is near-expiry or
     * already expired (issue #289). Intended to be called from
     * `RouseApplication.onCreate` alongside [enqueuePeriodic] so a user who opens
     * the app with an expired cert doesn't sit stuck waiting for the first
     * periodic tick (up to 24h).
     *
     * Design notes:
     * - Uses [WORK_NAME_IMMEDIATE], a *distinct* unique-work name, so it does
     *   not clobber the periodic schedule installed by [enqueuePeriodic].
     * - Uses [ExistingWorkPolicy.KEEP]: if a previous immediate run is still
     *   pending, don't replace it. The periodic worker is the long-running
     *   safety net — the immediate run is a best-effort kick.
     * - Returns `false` when no immediate enqueue was necessary (cert missing
     *   or valid for longer than [thresholdMillis]); the caller doesn't need
     *   the return, but tests do.
     *
     * @param thresholdMillis how close to expiry triggers an immediate run.
     *   Defaults to [CertRenewalWorker.DEFAULT_RENEWAL_WINDOW_DAYS] days,
     *   matching the worker's internal `renewalWindowDays` so we don't enqueue
     *   a run the worker will then early-out on.
     */
    suspend fun enqueueImmediateIfExpiring(
        context: Context,
        certStore: CertificateStore,
        thresholdMillis: Long = DEFAULT_IMMEDIATE_THRESHOLD_MS,
        now: Long = System.currentTimeMillis()
    ): Boolean {
        val expiry = certStore.getCertExpiry() ?: return false
        val millisUntilExpiry = expiry - now
        if (millisUntilExpiry > thresholdMillis) {
            return false
        }
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true)
            .build()
        val request = OneTimeWorkRequestBuilder<CertRenewalWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.LINEAR, BACKOFF_MINUTES, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            WORK_NAME_IMMEDIATE,
            ExistingWorkPolicy.KEEP,
            request
        )
        return true
    }

    private const val PERIODIC_INTERVAL_HOURS = 24L
    private const val PERIODIC_FLEX_HOURS = 4L
    private const val BACKOFF_MINUTES = 15L

    /**
     * Unique-work slot for the app-start immediate-renewal request (issue #289).
     *
     * Deliberately NOT the same as [CertRenewalWorker.WORK_NAME] (periodic) or
     * [CertRenewalWorker.WORK_NAME_DELAYED] (rate-limit reschedule): those
     * slots have their own lifecycles and replacing them from app start would
     * regress the scheduling contracts covered by #277.
     */
    const val WORK_NAME_IMMEDIATE = "cert_renewal_immediate"

    /** Mirrors [CertRenewalWorker.DEFAULT_RENEWAL_WINDOW_DAYS] in millis. */
    const val DEFAULT_IMMEDIATE_THRESHOLD_MS =
        CertRenewalWorker.DEFAULT_RENEWAL_WINDOW_DAYS * 24L * 60L * 60L * 1000L
}
