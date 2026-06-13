package com.rousecontext.app.auth

import android.util.Log
import com.rousecontext.tunnel.DeviceCredential
import com.rousecontext.tunnel.DeviceKeyManager
import com.rousecontext.tunnel.KeypairAuth
import com.rousecontext.tunnel.KeypairProof
import com.rousecontext.work.DeviceKeystoreSigner

/**
 * `foss`-flavor [DeviceCredentialProvider] backed by the hardware-backed device
 * keypair (issue #462). There is no Firebase: the device proves identity by
 * signing a short-lived registration proof with its Keystore key.
 *
 * The same credential shape serves both registration and provisioning because
 * the device key is stable. Round-1 `/register` consumes the public key + proof;
 * round-2 `/register/certs` is identified purely by the CSR's key thumbprint and
 * ignores the proof, so re-building one for provisioning is harmless.
 *
 * Returning `null` (Keystore locked / unavailable) is the correct transient
 * failure mode — callers retry later.
 */
class KeypairDeviceCredentialProvider(
    private val deviceKeyManager: DeviceKeyManager,
    private val signer: DeviceKeystoreSigner
) : DeviceCredentialProvider {

    override suspend fun forRegistration(): DeviceCredential? = buildCredential()

    override suspend fun forProvisioning(): DeviceCredential? = buildCredential()

    private fun buildCredential(): DeviceCredential.Keypair? = try {
        val publicKeyDer = deviceKeyManager.getOrCreateKeyPair().public.encoded
        val timestampSecs = System.currentTimeMillis() / MILLIS_PER_SECOND
        val nonce = KeypairAuth.randomNonce()
        val message = KeypairAuth.canonicalMessage(
            KeypairAuth.PURPOSE_REGISTER,
            timestampSecs,
            nonce
        )
        val signature = signer.sign(message)
        DeviceCredential.Keypair(
            publicKeyDer = publicKeyDer,
            registerProof = KeypairProof(timestampSecs, nonce, signature)
        )
    } catch (e: Exception) {
        Log.w(TAG, "Failed to build keypair credential; deferring", e)
        null
    }

    private companion object {
        const val TAG = "KeypairCredential"
        const val MILLIS_PER_SECOND = 1000L
    }
}
