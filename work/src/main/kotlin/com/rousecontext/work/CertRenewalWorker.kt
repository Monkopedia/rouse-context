package com.rousecontext.work

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.rousecontext.api.CrashReporter
import com.rousecontext.tunnel.CertRenewalFlow
import com.rousecontext.tunnel.CertificateStore
import com.rousecontext.tunnel.FirebaseRenewalCredentials
import com.rousecontext.tunnel.RenewalResult
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.koin.core.qualifier.named

/**
 * Firebase credentials usable for cert renewal when the cert has already expired.
 *
 * - [token]: a fresh Firebase ID token (JWT).
 * - [signature]: Base64-encoded SHA256withECDSA signature over the raw DER bytes of the
 *   renewal CSR, using the device private key stored in the Android Keystore.
 */
typealias FirebaseCredentials = FirebaseRenewalCredentials

/**
 * Acquires credentials for certificate renewal.
 *
 * The [csrDer] argument is the raw DER-encoded bytes of the renewal CSR that the tunnel
 * layer will submit to the relay; the provider must compute its signature over *those*
 * bytes (not a freshly-generated CSR) so the relay's signature verification succeeds.
 *
 * Production implementation must bridge FirebaseAuth (for [FirebaseCredentials.token]) and
 * Android Keystore signing (for CSR signatures). If either is unavailable the
 * implementation returns `null`, and the worker will defer to the next scheduled run.
 */
interface RenewalAuthProvider {
    /**
     * Sign the renewal CSR with the device's registered private key for the mTLS-equivalent
     * renewal path (cert still valid). Returns the Base64 SHA256withECDSA signature over
     * [csrDer], or `null` if the Keystore signing operation fails transiently.
     */
    suspend fun signCsr(csrDer: ByteArray): String?

    /**
     * Supply a Firebase ID token plus a CSR signature for the expired-cert renewal path.
     * Both are required; returns `null` if either is unavailable.
     */
    suspend fun acquireFirebaseCredentials(csrDer: ByteArray): FirebaseCredentials?
}

/**
 * Minimal renewal surface used by the worker. Production binding is [CertRenewalFlow];
 * tests use a lightweight fake. Having a seam here keeps the worker's decision logic
 * independent of the mock-relay infrastructure in `core/tunnel`'s test source set.
 */
interface CertRenewer {
    /**
     * Renew with the mTLS-equivalent path. Internally the renewer generates the CSR, then
     * calls [authProvider] with the CSR DER to sign the bytes with the registered key.
     */
    suspend fun renewWithMtls(authProvider: RenewalAuthProvider, baseDomain: String): RenewalResult

    /**
     * Renew with the Firebase-signature path. Internally the renewer generates the CSR, then
     * calls [authProvider] with the CSR DER to obtain a signed credentials bundle.
     */
    suspend fun renewWithFirebase(
        authProvider: RenewalAuthProvider,
        baseDomain: String
    ): RenewalResult
}

/** Adapter that exposes [CertRenewalFlow] as a [CertRenewer]. */
class CertRenewalFlowRenewer(private val flow: CertRenewalFlow) : CertRenewer {
    override suspend fun renewWithMtls(
        authProvider: RenewalAuthProvider,
        baseDomain: String
    ): RenewalResult = flow.renewWithMtls(
        csrSigner = { csrDer -> authProvider.signCsr(csrDer) },
        baseDomain = baseDomain
    )

    override suspend fun renewWithFirebase(
        authProvider: RenewalAuthProvider,
        baseDomain: String
    ): RenewalResult = flow.renewWithFirebase(
        credentialsProvider = { csrDer -> authProvider.acquireFirebaseCredentials(csrDer) },
        baseDomain = baseDomain
    )
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
    private val injectedPreferences: CertRenewalPreferences by inject()

    /**
     * Lazy, nullable inject so tests that don't stand up Koin (see
     * `CertRenewalWorkerTest`) don't have to register a [CrashReporter].
     * In production Koin always returns [FirebaseCrashReporter]; in tests
     * this stays null and the getter falls through to [CrashReporter.NoOp].
     */
    private val injectedCrashReporter: CrashReporter? by lazy {
        runCatching { getKoin().get<CrashReporter>() }.getOrNull()
    }

    var renewer: CertRenewer? = null
    var certificateStore: CertificateStore? = null
    var authProvider: RenewalAuthProvider? = null
    var baseDomain: String? = null
    var preferences: CertRenewalPreferences? = null
    var crashReporter: CrashReporter? = null

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

        val auth = authProvider ?: injectedAuthProvider
        val result = if (expired) {
            Log.i(TAG, "Cert expired (by ${-daysUntilExpiry} days), attempting Firebase renewal")
            activeRenewer.renewWithFirebase(auth, domain)
        } else {
            Log.i(TAG, "Cert expires in $daysUntilExpiry days, attempting mTLS renewal")
            activeRenewer.renewWithMtls(auth, domain)
        }

        return handleResult(result)
    }

    private suspend fun handleResult(result: RenewalResult): Result = when (result) {
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
        is RenewalResult.FirebaseAuthUnavailable -> {
            Log.w(TAG, "Cert expired but Firebase credentials unavailable, will retry later")
            recordLastAttempt(Outcome.EXPIRED_NO_AUTH)
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
        is RenewalResult.KeyGenerationFailed -> handleKeyGenerationFailed(result)
        is RenewalResult.CnMismatch -> handleCnMismatch(result)
    }

    /**
     * Terminal: Keystore signing / key-gen regression. Reports the cause to
     * Crashlytics so we notice if a device-OS update is bricking renewals in
     * the field, and returns `success` so the periodic worker schedule picks
     * it up again on the next tick rather than tight-looping via `retry`.
     */
    private suspend fun handleKeyGenerationFailed(
        result: RenewalResult.KeyGenerationFailed
    ): Result {
        Log.e(TAG, "Cert renewal key generation failed", result.cause)
        resolvedCrashReporter().logCaughtException(result.cause)
        recordLastAttempt(Outcome.KEY_GEN_FAILED)
        return Result.success()
    }

    /**
     * Terminal: on-device cert identity disagrees with what the relay has.
     * Reports as a Crashlytics breadcrumb (not an exception — this isn't a
     * throwable condition) so we can diagnose the mismatch. Returns success
     * to avoid a retry-storm since re-running the same request will produce
     * the same mismatch.
     */
    private suspend fun handleCnMismatch(result: RenewalResult.CnMismatch): Result {
        Log.e(
            TAG,
            "Cert renewal CN mismatch: expected=${result.expected} actual=${result.actual}"
        )
        resolvedCrashReporter().log(
            "CertRenewal CN mismatch expected=${result.expected} actual=${result.actual}"
        )
        recordLastAttempt(Outcome.CN_MISMATCH)
        return Result.success()
    }

    private fun resolvedCrashReporter(): CrashReporter =
        crashReporter ?: injectedCrashReporter ?: CrashReporter.NoOp

    private suspend fun recordLastAttempt(outcome: Outcome) {
        (preferences ?: injectedPreferences).recordAttempt(
            attemptAt = clock(),
            outcome = outcome.name
        )
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

        /** Renew when certificate expires within this many days (ACME ~90d lifetime). */
        const val DEFAULT_RENEWAL_WINDOW_DAYS = 21L

        /** Fallback retry-after when the relay omits the header. */
        const val DEFAULT_RATE_LIMIT_BACKOFF_SECONDS = 60L * 60L

        const val KOIN_BASE_DOMAIN_NAME = "baseDomain"

        private const val MS_PER_DAY = 24L * 60L * 60L * 1000L
    }
}
