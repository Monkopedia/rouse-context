package com.rousecontext.work

import android.util.Base64
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature

/**
 * Production [DeviceKeystoreSigner] backed by the Android Keystore.
 *
 * Uses SHA256withECDSA which matches the P-256 EC key minted by
 * `FileCertificateStore.ensureDeviceKey` under the alias `rouse_device_key`. The produced
 * signature is returned Base64-encoded (no wrap) — the format the relay's `/renew` endpoint
 * expects in the `signature` field (see `docs/design/relay-api.md`).
 */
class AndroidKeystoreSigner(
    private val keyAlias: String = DEFAULT_KEY_ALIAS,
    private val keyStoreProvider: String = ANDROID_KEYSTORE
) : DeviceKeystoreSigner {

    override fun sign(data: ByteArray): String {
        val keyStore = KeyStore.getInstance(keyStoreProvider).apply { load(null) }
        val privateKey = keyStore.getKey(keyAlias, null) as? PrivateKey
            ?: error("Keystore alias '$keyAlias' has no private key entry")
        val signatureBytes = Signature.getInstance(SIGNATURE_ALGORITHM).run {
            initSign(privateKey)
            update(data)
            sign()
        }
        return Base64.encodeToString(signatureBytes, Base64.NO_WRAP)
    }

    companion object {
        /** Matches `FileCertificateStore.KEY_ALIAS`; the device EC key minted at onboarding. */
        const val DEFAULT_KEY_ALIAS = "rouse_device_key"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val SIGNATURE_ALGORITHM = "SHA256withECDSA"
    }
}
