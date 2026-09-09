package com.rousecontext.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Enqueues the periodic [SecurityCheckWorker] (self-cert fingerprint match + CT log
 * monitoring). Extracted out of `RouseApplication` so the constraints are unit-testable
 * in isolation — the `Application` subclass drives real Koin + Firebase init and is
 * awkward to exercise under Robolectric just for a scheduling assertion.
 *
 * Mirrors [CertRenewalScheduler.enqueuePeriodic]:
 * - `NetworkType.CONNECTED` — required. Without it the worker fires offline or behind
 *   a captive portal; the captive portal's HTML response gets mis-reported as a
 *   "crt.sh returned HTTP 404" alert, spamming the user on every network transition
 *   (issue #330).
 * - `requiresBatteryNotLow=true` — consistent with renewal; no reason to burn the
 *   last 10% on a monitoring check.
 * - Charging is NOT required: this is a routine check, not a heavy task.
 */
object SecurityCheckScheduler {

    /**
     * Unique-work slot name. Kept as the hyphenated form used by production since
     * the pre-extraction code in `RouseApplication` — changing the slot would orphan
     * any already-scheduled work on existing installs until the next Android
     * process recycle. Intentionally different from [SecurityCheckWorker.WORK_NAME]
     * (underscore), which names the opportunistic one-time run from
     * `TunnelForegroundService`.
     */
    const val WORK_NAME = "security-check"

    /**
     * Enqueue the periodic security-check. Safe to call every app startup.
     *
     * @param intervalHours how often the worker runs. Read from user preferences.
     * @param flexHours how much slack WorkManager has to align with other jobs.
     *   Caller is responsible for enforcing the `>= 1` minimum; this method accepts
     *   the value as-is so the test can drive representative values.
     */
    fun enqueuePeriodic(context: Context, intervalHours: Int, flexHours: Int) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true)
            .build()
        val request = PeriodicWorkRequestBuilder<SecurityCheckWorker>(
            intervalHours.toLong(),
            TimeUnit.HOURS,
            flexHours.toLong(),
            TimeUnit.HOURS
        )
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }

    /**
     * Cancel the periodic security check. Backs the `Never` option in the
     * Settings check-interval control.
     *
     * This removes the *scheduled* work only. It is not on its own sufficient
     * to stop the checks: `TunnelForegroundService` also enqueues a one-time
     * run on tunnel connect, in a different unique-work slot, which this cannot
     * reach. [SecurityCheckWorker] therefore enforces the setting again when it
     * runs. Removing either half leaves network egress the user asked to stop.
     */
    fun cancelPeriodic(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }

    /**
     * Flex window for a given cadence: a quarter of the interval, at least an
     * hour. Shared by the two callers that enqueue this work — app startup and
     * the Settings control — so they cannot drift apart.
     */
    fun flexFor(intervalHours: Int): Int = (intervalHours / FLEX_DIVISOR).coerceAtLeast(1)

    private const val FLEX_DIVISOR = 4
}
