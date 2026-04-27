package com.rousecontext.work

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.rousecontext.notifications.SecurityCheckNotifier
import com.rousecontext.notifications.SecurityCheckNotifier.SecurityCheck
import com.rousecontext.tunnel.SecurityCheckResult
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * Abstraction for a security check that can be verified.
 * Both [com.rousecontext.tunnel.SelfCertVerifier] and [com.rousecontext.tunnel.CtLogMonitor]
 * are wrapped behind this interface so the worker doesn't depend on their constructors directly.
 */
interface SecurityCheckSource {
    suspend fun check(): SecurityCheckResult
}

/**
 * Periodic WorkManager worker that runs certificate security checks:
 * 1. Self-cert verification (fingerprint match against known certs)
 * 2. CT log monitoring (unexpected certificate issuers)
 *
 * Results are stored via [SecurityCheckPreferences] (DataStore-backed) for UI consumption.
 * Alerts and warnings are posted by [SecurityCheckNotifier] using stable ids per
 * (check, severity) pair, so repeated runs replace the existing notification rather
 * than stacking or clobbering prior runs' unrelated notifications.
 *
 * Issue #256: a [SecurityCheckResult.Warning] now debounces across runs -- a
 * notification only surfaces when the SAME source returns Warning on
 * [WARNING_NOTIFICATION_THRESHOLD] consecutive runs. Any non-Warning result
 * (Verified, Alert, Skipped) resets the counter for that source. Alerts still
 * fire immediately: the debounce applies only to Warning.
 *
 * Always returns [Result.success] since the checks handle their own graceful degradation.
 */
class SecurityCheckWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params),
    KoinComponent {

    /** Injected by WorkerFactory in production, set directly in tests. */
    lateinit var selfCertVerifier: SecurityCheckSource

    /** Injected by WorkerFactory in production, set directly in tests. */
    lateinit var ctLogMonitor: SecurityCheckSource

    /** Injected by WorkerFactory in production, set directly in tests. */
    lateinit var notifier: SecurityCheckNotifier

    private val injectedPreferences: SecurityCheckPreferences by inject()

    /** Optional override for tests; production reads via Koin. */
    var preferences: SecurityCheckPreferences? = null

    override suspend fun doWork(): Result {
        Log.d(TAG, "Starting security checks")

        val selfCertResult = selfCertVerifier.check()
        val ctResult = ctLogMonitor.check()
        val prefs = preferences ?: injectedPreferences

        prefs.recordCheck(
            lastCheckAt = System.currentTimeMillis(),
            selfCertResult = resultToString(selfCertResult),
            ctLogResult = resultToString(ctResult)
        )

        handleResult(
            prefs,
            SecurityCheck.SELF_CERT,
            SecurityCheckPreferences.SOURCE_SELF_CERT,
            "Self-cert verification",
            selfCertResult
        )
        handleResult(
            prefs,
            SecurityCheck.CT_LOG,
            SecurityCheckPreferences.SOURCE_CT_LOG,
            "CT log check",
            ctResult
        )

        Log.d(TAG, "Security checks complete: self=$selfCertResult, ct=$ctResult")
        return Result.success()
    }

    private suspend fun handleResult(
        prefs: SecurityCheckPreferences,
        check: SecurityCheck,
        sourceName: String,
        checkName: String,
        result: SecurityCheckResult
    ) {
        when (result) {
            is SecurityCheckResult.Verified -> {
                Log.d(TAG, "$checkName: verified")
                prefs.resetWarningStreak(sourceName)
            }

            is SecurityCheckResult.Skipped -> {
                // Issue #228: pre-onboarding / "not yet configured" states log
                // for diagnostics but MUST NOT fire a user notification.
                Log.d(TAG, "$checkName: skipped - ${result.reason}")
                prefs.resetWarningStreak(sourceName)
            }

            is SecurityCheckResult.Warning -> {
                val streak = prefs.incrementWarningStreak(sourceName)
                val alreadyNotified = prefs.hasNotifiedForStreak(sourceName)
                if (streak >= WARNING_NOTIFICATION_THRESHOLD && !alreadyNotified) {
                    Log.w(TAG, "$checkName: warning (streak=$streak) - ${result.reason}")
                    notifier.postInfo(check, result.reason)
                    prefs.markNotifiedForStreak(sourceName)
                } else if (streak >= WARNING_NOTIFICATION_THRESHOLD) {
                    // Issue #429: streak still above threshold but we already
                    // notified. Don't re-fire every interval — log silently
                    // until the streak breaks (Verified / Skipped) and resets.
                    Log.d(
                        TAG,
                        "$checkName: warning (streak=$streak, already notified) - " +
                            result.reason
                    )
                } else {
                    // Below threshold -- single/transient flaps are absorbed
                    // silently. Issue #256: crt.sh 5xx hiccups should not
                    // notify on first occurrence.
                    Log.d(
                        TAG,
                        "$checkName: warning (streak=$streak, below threshold) - " +
                            result.reason
                    )
                }
            }

            is SecurityCheckResult.Alert -> {
                Log.e(TAG, "$checkName: ALERT - ${result.reason}")
                // Alerts bypass debouncing entirely, but any accumulated
                // warning streak is no longer meaningful.
                prefs.resetWarningStreak(sourceName)
                notifier.postAlert(check, result.reason)
            }
        }
    }

    companion object {
        const val TAG = "SecurityCheckWorker"
        const val WORK_NAME = "security_check"

        /**
         * Minimum consecutive Warning runs from the same source before a user
         * notification fires. Issue #256: covers persistent-but-intermittent
         * outages that the per-request retry in [com.rousecontext.tunnel.HttpCtLogFetcher]
         * cannot absorb on its own.
         */
        const val WARNING_NOTIFICATION_THRESHOLD = 3

        private fun resultToString(result: SecurityCheckResult): String = when (result) {
            is SecurityCheckResult.Verified -> "verified"
            is SecurityCheckResult.Skipped -> "skipped"
            is SecurityCheckResult.Warning -> "warning"
            is SecurityCheckResult.Alert -> "alert"
        }
    }
}
