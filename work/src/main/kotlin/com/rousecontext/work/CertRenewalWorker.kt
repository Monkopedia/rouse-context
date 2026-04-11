package com.rousecontext.work

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/**
 * Interface for certificate expiry checking and renewal.
 * Decouples the worker from the full [com.rousecontext.tunnel.CertificateStore].
 */
interface CertRenewalStore {
    /** Returns certificate expiry as epoch millis, or null if no cert. */
    suspend fun getCertExpiry(): Long?

    /** Attempt to renew the certificate. Throws [CertRenewalException] on failure. */
    suspend fun renewCertificate()
}

/** Thrown when certificate renewal fails. */
class CertRenewalException(message: String, cause: Throwable? = null) :
    Exception(message, cause)

/**
 * WorkManager worker that checks certificate expiry and triggers renewal
 * when the cert expires within [RENEWAL_WINDOW_DAYS] days.
 *
 * The [certificateStore] must be set before [doWork] is called. In production,
 * this is done via a custom WorkerFactory. In tests, set it directly.
 */
class CertRenewalWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    /** Injected by WorkerFactory in production, set directly in tests. */
    lateinit var certificateStore: CertRenewalStore

    override suspend fun doWork(): Result {
        val expiry = certificateStore.getCertExpiry()
            ?: return Result.success() // No cert, nothing to renew

        val daysUntilExpiry = (expiry - System.currentTimeMillis()) /
            (24 * 60 * 60 * 1000L)

        if (daysUntilExpiry >= RENEWAL_WINDOW_DAYS) {
            Log.d(TAG, "Certificate valid for $daysUntilExpiry more days, no renewal needed")
            return Result.success()
        }

        Log.i(TAG, "Certificate expires in $daysUntilExpiry days, attempting renewal")

        return try {
            certificateStore.renewCertificate()
            Log.i(TAG, "Certificate renewed successfully")
            Result.success()
        } catch (e: CertRenewalException) {
            Log.w(TAG, "Certificate renewal failed, will retry", e)
            Result.retry()
        }
    }

    companion object {
        const val TAG = "CertRenewalWorker"
        const val WORK_NAME = "cert_renewal"

        /** Renew when certificate expires within this many days. */
        const val RENEWAL_WINDOW_DAYS = 14L
    }
}
