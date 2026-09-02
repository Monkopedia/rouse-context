package com.rousecontext.app.auth

import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.rousecontext.work.DeviceKeystoreSigner
import com.rousecontext.work.FirebaseCredentials
import com.rousecontext.work.RenewalAuthProvider
import kotlinx.coroutines.CancellationException
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
 * `google`-flavor-only (issue #476): lives in the `:app` `google` source set so the
 * shared `:work` module links no `firebase-auth`. The `foss` flavor binds
 * `KeypairRenewalAuthProvider` instead.
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
    } catch (e: CancellationException) {
        // MUST stay above the broad catch (issue #670): CancellationException is an
        // Exception on the JVM, and extends IllegalStateException, so a broad — or an
        // IllegalStateException — catch swallows it.
        //
        // Defence in depth here, unlike acquireFirebaseCredentials below: nothing inside
        // this try is a suspension point (DeviceKeystoreSigner.sign is a plain `fun`), and
        // a suspend function whose try has no suspension point never takes delivery of
        // cooperative cancellation. But RenewalAuthProvider declares signCsr `suspend`, so
        // a signer that suspends — or a withContext added around this call — would
        // silently convert the catch below into a swallow under CertRenewalWorker, with
        // nothing nearby to say so.
        throw e
    } catch (e: Exception) {
        Log.e(TAG, "Keystore signing failed; deferring renewal", e)
        null
    }

    override suspend fun acquireFirebaseCredentials(csrDer: ByteArray): FirebaseCredentials? {
        val token = try {
            tokenSource.fetch()
        } catch (e: CancellationException) {
            // MUST stay above the broad catch (issue #670). This one is a live bug, not
            // defence in depth: the provider is driven from CertRenewalWorker, a
            // CoroutineWorker whose coroutine WorkManager cancels when it stops the worker,
            // and FirebaseIdTokenSource.fetch() genuinely suspends
            // (getIdToken(false).await()). So a stopped worker took delivery of
            // cancellation right here, was logged as a token-fetch failure, and execution
            // continued past the point where it should have unwound.
            throw e
        } catch (e: Exception) {
            // Return rather than falling through to the isNullOrEmpty branch below, whose
            // "No Firebase user" wording would misreport a fetch failure as "not signed in"
            // (issue #670).
            Log.w(TAG, "Failed to fetch Firebase ID token; deferring renewal", e)
            return null
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
