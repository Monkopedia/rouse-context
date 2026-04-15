package com.rousecontext.work

import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.tasks.await

/**
 * Fetches a fresh Firebase ID token. Extracted so [FirebaseRenewalAuthProvider] can be
 * unit-tested without Robolectric or a live Firebase app — tests inject a fake.
 */
fun interface FirebaseIdTokenSource {
    suspend fun fetch(): String?
}

/**
 * Default [RenewalAuthProvider] implementation bridging [FirebaseAuth] (for the ID token)
 * and the Android Keystore (for the SHA256withECDSA signature over the CSR DER).
 *
 * Returning `null` is the correct failure mode: the worker treats it as a transient
 * condition and retries on the next periodic tick. This keeps us from retry-storming when
 * the user isn't signed in yet or the Keystore is momentarily unavailable.
 */
class FirebaseRenewalAuthProvider(
    private val signer: DeviceKeystoreSigner,
    private val tokenSource: FirebaseIdTokenSource = DefaultFirebaseIdTokenSource
) : RenewalAuthProvider {

    override suspend fun signCsr(csrDer: ByteArray): String? = try {
        signer.sign(csrDer)
    } catch (e: Exception) {
        Log.e(TAG, "Keystore signing failed; deferring renewal", e)
        null
    }

    override suspend fun acquireFirebaseCredentials(csrDer: ByteArray): FirebaseCredentials? {
        val token = try {
            tokenSource.fetch()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch Firebase ID token", e)
            null
        }
        if (token.isNullOrEmpty()) {
            Log.w(TAG, "No Firebase user / ID token available; deferring renewal")
            return null
        }
        val signature = signCsr(csrDer) ?: return null
        return FirebaseCredentials(token = token, signature = signature)
    }

    private companion object {
        const val TAG = "FirebaseRenewalAuth"
    }
}

/** Default source that reads from the process-wide FirebaseAuth singleton. */
internal object DefaultFirebaseIdTokenSource : FirebaseIdTokenSource {
    override suspend fun fetch(): String? =
        FirebaseAuth.getInstance().currentUser?.getIdToken(false)?.await()?.token
}
