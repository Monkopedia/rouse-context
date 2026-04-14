package com.rousecontext.work

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.rousecontext.notifications.SecurityCheckNotifier
import com.rousecontext.notifications.SecurityCheckNotifier.SecurityCheck
import com.rousecontext.tunnel.SecurityCheckResult

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
 * Results are stored in SharedPreferences for UI consumption.
 * Alerts and warnings are posted by [SecurityCheckNotifier] using stable ids per
 * (check, severity) pair, so repeated runs replace the existing notification rather
 * than stacking or clobbering prior runs' unrelated notifications.
 * Always returns [Result.success] since the checks handle their own graceful degradation.
 */
class SecurityCheckWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    /** Injected by WorkerFactory in production, set directly in tests. */
    lateinit var selfCertVerifier: SecurityCheckSource

    /** Injected by WorkerFactory in production, set directly in tests. */
    lateinit var ctLogMonitor: SecurityCheckSource

    /** Injected by WorkerFactory in production, set directly in tests. */
    lateinit var notifier: SecurityCheckNotifier

    override suspend fun doWork(): Result {
        Log.d(TAG, "Starting security checks")

        val selfCertResult = selfCertVerifier.check()
        val ctResult = ctLogMonitor.check()

        val prefs = applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putLong(KEY_LAST_CHECK_TIME, System.currentTimeMillis())
            .putString(KEY_SELF_CERT_RESULT, resultToString(selfCertResult))
            .putString(KEY_CT_LOG_RESULT, resultToString(ctResult))
            .apply()

        handleResult(SecurityCheck.SELF_CERT, "Self-cert verification", selfCertResult)
        handleResult(SecurityCheck.CT_LOG, "CT log check", ctResult)

        Log.d(TAG, "Security checks complete: self=$selfCertResult, ct=$ctResult")
        return Result.success()
    }

    private fun handleResult(check: SecurityCheck, checkName: String, result: SecurityCheckResult) {
        when (result) {
            is SecurityCheckResult.Verified -> {
                Log.d(TAG, "$checkName: verified")
            }

            is SecurityCheckResult.Warning -> {
                Log.w(TAG, "$checkName: warning - ${result.reason}")
                notifier.postInfo(check, result.reason)
            }

            is SecurityCheckResult.Alert -> {
                Log.e(TAG, "$checkName: ALERT - ${result.reason}")
                notifier.postAlert(check, result.reason)
            }
        }
    }

    companion object {
        const val TAG = "SecurityCheckWorker"
        const val WORK_NAME = "security_check"
        const val PREFS_NAME = "security_check_prefs"

        const val KEY_LAST_CHECK_TIME = "last_check_time"
        const val KEY_SELF_CERT_RESULT = "self_cert_result"
        const val KEY_CT_LOG_RESULT = "ct_log_result"

        private fun resultToString(result: SecurityCheckResult): String = when (result) {
            is SecurityCheckResult.Verified -> "verified"
            is SecurityCheckResult.Warning -> "warning"
            is SecurityCheckResult.Alert -> "alert"
        }
    }
}
