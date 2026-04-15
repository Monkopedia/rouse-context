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

        (preferences ?: injectedPreferences).recordCheck(
            lastCheckAt = System.currentTimeMillis(),
            selfCertResult = resultToString(selfCertResult),
            ctLogResult = resultToString(ctResult)
        )

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

        private fun resultToString(result: SecurityCheckResult): String = when (result) {
            is SecurityCheckResult.Verified -> "verified"
            is SecurityCheckResult.Warning -> "warning"
            is SecurityCheckResult.Alert -> "alert"
        }
    }
}
