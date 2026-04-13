package com.rousecontext.work

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.rousecontext.tunnel.CertRenewalFlow
import com.rousecontext.tunnel.CertificateStore
import com.rousecontext.tunnel.RenewalResult
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.koin.core.qualifier.named

/**
 * Firebase credentials usable for cert renewal when the cert has already expired.
 *
 * - [token]: a fresh Firebase ID token (JWT).
 * - [signature]: Base64-encoded SHA256withECDSA signature over the new CSR's DER bytes,
 *   using the device private key stored in the Android Keystore.
 */
data class FirebaseCredentials(val token: String, val signature: String)

/**
 * Acquires credentials for the expired-cert renewal path.
 * Production implementation must bridge FirebaseAuth (for [FirebaseCredentials.token]) and
 * Android Keystore signing (for [FirebaseCredentials.signature]). If either is unavailable
 * the implementation returns `null`, and the worker will defer to the next scheduled run.
 */
interface RenewalAuthProvider {
    suspend fun acquireFirebaseCredentials(): FirebaseCredentials?
}

/**
 * Minimal renewal surface used by the worker. Production binding is [CertRenewalFlow];
 * tests use a lightweight fake. Having a seam here keeps the worker's decision logic
 * independent of the mock-relay infrastructure in `core/tunnel`'s test source set.
 */
interface CertRenewer {
    suspend fun renewWithMtls(baseDomain: String): RenewalResult
    suspend fun renewWithFirebase(
        firebaseToken: String,
        signature: String,
        baseDomain: String
    ): RenewalResult
}

/** Adapter that exposes [CertRenewalFlow] as a [CertRenewer]. */
class CertRenewalFlowRenewer(private val flow: CertRenewalFlow) : CertRenewer {
    override suspend fun renewWithMtls(baseDomain: String): RenewalResult =
        flow.renewWithMtls(baseDomain)

    override suspend fun renewWithFirebase(
        firebaseToken: String,
        signature: String,
        baseDomain: String
    ): RenewalResult = flow.renewWithFirebase(firebaseToken, signature, baseDomain)
}

/**
 * Periodic worker that keeps the device certificate alive.
 *
 * Decision logic:
 * - No cert stored: skip.
 * - Cert expires in more than [renewalWindowDays]: skip.
 * - Cert expiring within [renewalWindowDays] (but not yet expired): mTLS renew.
 * - Cert already expired: Firebase-signature renew, if [authProvider] can supply credentials.
 *
 * On [RenewalResult.RateLimited], the worker invokes [rescheduleWithDelay] with the relay's
 * `retry_after` and returns [Result.retry]; callers inject a callback that enqueues a delayed
 * one-time work copy. Other recoverable errors (network / retry-after-absent rate-limit) also
 * return [Result.retry]; terminal errors (mismatched CN, key generation failure) are recorded
 * and return [Result.success] since the next periodic tick will pick them up.
 *
 * All collaborators can be injected via setters for tests. In production they are resolved
 * lazily from Koin on first access inside [doWork].
 */
class CertRenewalWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params),
    KoinComponent {

    // --- Collaborators (injected by Koin in production, overridden in tests) ---

    private val injectedRenewer: CertRenewer by inject()
    private val injectedStore: CertificateStore by inject()
    private val injectedAuthProvider: RenewalAuthProvider by inject()
    private val injectedBaseDomain: String by inject(named(KOIN_BASE_DOMAIN_NAME))

    var renewer: CertRenewer? = null
    var certificateStore: CertificateStore? = null
    var authProvider: RenewalAuthProvider? = null
    var baseDomain: String? = null

    var clock: () -> Long = { System.currentTimeMillis() }
    var renewalWindowDays: Long = DEFAULT_RENEWAL_WINDOW_DAYS
    var rescheduleWithDelay: (Long) -> Unit = { delaySeconds ->
        CertRenewalScheduler.enqueueDelayed(applicationContext, delaySeconds)
    }

    override suspend fun doWork(): Result {
        val store = certificateStore ?: injectedStore
        val expiry = store.getCertExpiry()
        if (expiry == null) {
            Log.d(TAG, "No cert stored, nothing to renew")
            recordLastAttempt(Outcome.SKIP_NO_CERT)
            return Result.success()
        }

        val now = clock()
        val millisUntilExpiry = expiry - now
        val daysUntilExpiry = millisUntilExpiry / MS_PER_DAY

        val expired = millisUntilExpiry <= 0L

        if (!expired && daysUntilExpiry > renewalWindowDays) {
            Log.d(TAG, "Cert valid for $daysUntilExpiry more days, skipping")
            recordLastAttempt(Outcome.SKIP_VALID)
            return Result.success()
        }

        val activeRenewer = renewer ?: injectedRenewer
        val domain = baseDomain ?: injectedBaseDomain

        val result = if (expired) {
            Log.i(TAG, "Cert expired (by ${-daysUntilExpiry} days), attempting Firebase renewal")
            val auth = authProvider ?: injectedAuthProvider
            val credentials = auth.acquireFirebaseCredentials()
            if (credentials == null) {
                Log.w(TAG, "Cert expired but Firebase credentials unavailable, will retry later")
                recordLastAttempt(Outcome.EXPIRED_NO_AUTH)
                return Result.retry()
            }
            activeRenewer.renewWithFirebase(credentials.token, credentials.signature, domain)
        } else {
            Log.i(TAG, "Cert expires in $daysUntilExpiry days, attempting mTLS renewal")
            activeRenewer.renewWithMtls(domain)
        }

        return handleResult(result)
    }

    private fun handleResult(result: RenewalResult): Result = when (result) {
        is RenewalResult.Success -> {
            Log.i(TAG, "Cert renewed successfully")
            recordLastAttempt(Outcome.SUCCESS)
            Result.success()
        }
        is RenewalResult.NoCertificate -> {
            Log.w(TAG, "Cert renewal reported no certificate")
            recordLastAttempt(Outcome.SKIP_NO_CERT)
            Result.success()
        }
        is RenewalResult.CertExpired -> {
            // mTLS path noticed an expired cert; next periodic run will take the Firebase path.
            Log.w(TAG, "mTLS renewal rejected: cert reported as expired")
            recordLastAttempt(Outcome.EXPIRED_MTLS_REJECTED)
            Result.retry()
        }
        is RenewalResult.RateLimited -> {
            val retryAfter = result.retryAfterSeconds ?: DEFAULT_RATE_LIMIT_BACKOFF_SECONDS
            Log.w(TAG, "Cert renewal rate-limited, retry after ${retryAfter}s")
            recordLastAttempt(Outcome.RATE_LIMITED)
            rescheduleWithDelay(retryAfter)
            Result.retry()
        }
        is RenewalResult.RelayError -> {
            Log.w(TAG, "Cert renewal relay error: ${result.statusCode} ${result.message}")
            recordLastAttempt(Outcome.RELAY_ERROR)
            Result.retry()
        }
        is RenewalResult.NetworkError -> {
            Log.w(TAG, "Cert renewal network error", result.cause)
            recordLastAttempt(Outcome.NETWORK_ERROR)
            Result.retry()
        }
        is RenewalResult.KeyGenerationFailed -> {
            Log.e(TAG, "Cert renewal key generation failed", result.cause)
            recordLastAttempt(Outcome.KEY_GEN_FAILED)
            Result.success() // Terminal: retry on next periodic tick.
        }
        is RenewalResult.CnMismatch -> {
            Log.e(
                TAG,
                "Cert renewal CN mismatch: expected=${result.expected} actual=${result.actual}"
            )
            recordLastAttempt(Outcome.CN_MISMATCH)
            Result.success() // Terminal: do not retry-storm.
        }
    }

    private fun recordLastAttempt(outcome: Outcome) {
        val prefs = applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putLong(KEY_LAST_ATTEMPT_TIME, clock())
            .putString(KEY_LAST_OUTCOME, outcome.name)
            .apply()
    }

    /** Enumerated outcomes persisted for UI visibility. */
    enum class Outcome {
        SUCCESS,
        SKIP_VALID,
        SKIP_NO_CERT,
        RATE_LIMITED,
        NETWORK_ERROR,
        RELAY_ERROR,
        EXPIRED_NO_AUTH,
        EXPIRED_MTLS_REJECTED,
        KEY_GEN_FAILED,
        CN_MISMATCH
    }

    companion object {
        const val TAG = "CertRenewalWorker"
        const val WORK_NAME = "cert_renewal"
        const val WORK_NAME_DELAYED = "cert_renewal_delayed"

        const val PREFS_NAME = "cert_renewal_prefs"
        const val KEY_LAST_ATTEMPT_TIME = "last_attempt_time"
        const val KEY_LAST_OUTCOME = "last_outcome"

        /** Renew when certificate expires within this many days (ACME ~90d lifetime). */
        const val DEFAULT_RENEWAL_WINDOW_DAYS = 21L

        /** Fallback retry-after when the relay omits the header. */
        const val DEFAULT_RATE_LIMIT_BACKOFF_SECONDS = 60L * 60L

        const val KOIN_BASE_DOMAIN_NAME = "baseDomain"

        private const val MS_PER_DAY = 24L * 60L * 60L * 1000L
    }
}
