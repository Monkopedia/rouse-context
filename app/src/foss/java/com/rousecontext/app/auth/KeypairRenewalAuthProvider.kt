package com.rousecontext.app.auth

import android.util.Log
import com.rousecontext.tunnel.KeypairAuth
import com.rousecontext.tunnel.KeypairProof
import com.rousecontext.tunnel.KeypairRenewalCredentials
import com.rousecontext.work.DeviceKeystoreSigner
import com.rousecontext.work.FirebaseCredentials
import com.rousecontext.work.RenewalAuthProvider

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
 */
class KeypairRenewalAuthProvider(private val signer: DeviceKeystoreSigner) : RenewalAuthProvider {

    override suspend fun signCsr(csrDer: ByteArray): String? = try {
        signer.sign(csrDer)
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
    } catch (e: Exception) {
        Log.e(TAG, "Keystore signing failed; deferring renewal", e)
        null
    }

    private companion object {
        const val TAG = "KeypairRenewalAuth"
        const val MILLIS_PER_SECOND = 1000L
    }
}
