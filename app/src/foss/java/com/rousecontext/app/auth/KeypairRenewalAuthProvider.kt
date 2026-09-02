package com.rousecontext.app.auth

import android.util.Log
import com.rousecontext.tunnel.KeypairAuth
import com.rousecontext.tunnel.KeypairProof
import com.rousecontext.tunnel.KeypairRenewalCredentials
import com.rousecontext.work.DeviceKeystoreSigner
import com.rousecontext.work.FirebaseCredentials
import com.rousecontext.work.RenewalAuthProvider
import kotlinx.coroutines.CancellationException

/**
 * `foss`-flavor [RenewalAuthProvider] backed by the Android Keystore device key
 * (issue #462). No Firebase.
 *
 * - Valid-cert path ([signCsr]): signs the renewal CSR DER, identical to the
 *   `google` flavor — the relay always verifies this against the stored key.
 * - Expired-cert path: instead of a Firebase token, supplies
 *   [acquireKeypairRenewalCredentials] — the CSR signature plus a freshly-signed
 *   [KeypairAuth.PURPOSE_RENEW] proof. [acquireFirebaseCredentials] returns
 *   `null` so the renewer takes the keypair branch.
 *
 * Returning `null` from either acquire path is the correct transient failure
 * mode: the worker retries on its next tick rather than retry-storming.
 *
 * Cancellation is NOT such a failure, so each broad catch is preceded by a
 * `catch (e: CancellationException) { throw e }` (issue #670). Those guards are
 * defence in depth rather than live-bug fixes, unlike the `google`
 * [FirebaseRenewalAuthProvider.acquireFirebaseCredentials] site: no `try` in this
 * class contains a suspension point ([DeviceKeystoreSigner.sign] is a plain `fun`,
 * and `KeypairAuth.randomNonce` / `canonicalMessage` are non-suspend), and a
 * `suspend` function whose `try` has no suspension point never takes delivery of
 * cooperative cancellation. They still earn their place: [RenewalAuthProvider]
 * declares both methods `suspend`, so a signer that suspends — or a `withContext`
 * added around a signing call — would silently turn these catches into swallows
 * under `CertRenewalWorker`, a `CoroutineWorker` that IS cancelled when the system
 * stops it.
 */
class KeypairRenewalAuthProvider(private val signer: DeviceKeystoreSigner) : RenewalAuthProvider {

    override suspend fun signCsr(csrDer: ByteArray): String? = try {
        signer.sign(csrDer)
    } catch (e: CancellationException) {
        // MUST stay above the broad catch (issue #670): CancellationException is an
        // Exception on the JVM, and extends IllegalStateException, so either catch
        // swallows it. Defence in depth — see the class KDoc for why this site cannot
        // take delivery of cancellation today, and why the guard is still worth having.
        throw e
    } catch (e: Exception) {
        Log.e(TAG, "Keystore signing failed; deferring renewal", e)
        null
    }

    override suspend fun acquireFirebaseCredentials(csrDer: ByteArray): FirebaseCredentials? = null

    override suspend fun acquireKeypairRenewalCredentials(
        csrDer: ByteArray
    ): KeypairRenewalCredentials? = try {
        val csrSignature = signer.sign(csrDer)
        val timestampSecs = System.currentTimeMillis() / MILLIS_PER_SECOND
        val nonce = KeypairAuth.randomNonce()
        val proofMessage = KeypairAuth.canonicalMessage(
            KeypairAuth.PURPOSE_RENEW,
            timestampSecs,
            nonce
        )
        val proofSignature = signer.sign(proofMessage)
        KeypairRenewalCredentials(
            csrSignature = csrSignature,
            proof = KeypairProof(timestampSecs, nonce, proofSignature)
        )
    } catch (e: CancellationException) {
        // MUST stay above the broad catch (issue #670). Same reasoning as signCsr: this is
        // the `foss` expired-cert path, and nothing in this try suspends either.
        throw e
    } catch (e: Exception) {
        Log.e(TAG, "Keystore signing failed; deferring renewal", e)
        null
    }

    private companion object {
        const val TAG = "KeypairRenewalAuth"
        const val MILLIS_PER_SECOND = 1000L
    }
}
