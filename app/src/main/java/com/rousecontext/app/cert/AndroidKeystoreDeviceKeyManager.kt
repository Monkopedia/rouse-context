package com.rousecontext.app.cert

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import android.util.Log
import com.rousecontext.api.CrashReporter
import com.rousecontext.tunnel.DeviceKeyManager
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.PublicKey

/**
 * Hardware-backed [DeviceKeyManager] for Android.
 *
 * Issue #200: generates an ECDSA P-256 device identity keypair in the Android Keystore
 * (alias `rouse_device_key`) on first call and reuses it on every subsequent call.
 *
 * StrongBox-first: if the device advertises a StrongBox Keymaster (Pixel 3+ and many
 * modern phones), the key is provisioned there. Otherwise we fall back to the TEE-backed
 * Keymaster path. Either way the private key material never leaves the secure element --
 * signing (SHA256withECDSA) happens inside the Keystore and only the signature bytes
 * surface in app memory.
 *
 * Alias choice: [KEY_ALIAS] matches the alias the previous encrypted-PEM code path used
 * and the one [com.rousecontext.work.AndroidKeystoreSigner] already reads. That keeps
 * the on-device renewal signing path working without a second migration.
 */
class AndroidKeystoreDeviceKeyManager(
    private val crashReporter: CrashReporter = CrashReporter.NoOp
) : DeviceKeyManager {

    override fun getOrCreateKeyPair(): KeyPair {
        val keyStore = androidKeyStore()
        if (keyStore.containsAlias(KEY_ALIAS)) {
            return loadExisting(keyStore)
        }
        return generateKeyPair()
    }

    private fun loadExisting(keyStore: KeyStore): KeyPair {
        val privateKey = keyStore.getKey(KEY_ALIAS, null) as? PrivateKey
            ?: error("Keystore alias '$KEY_ALIAS' has no private key entry")
        val publicKey: PublicKey = keyStore.getCertificate(KEY_ALIAS).publicKey
        return KeyPair(publicKey, privateKey)
    }

    private fun generateKeyPair(): KeyPair {
        // Prefer StrongBox when available; fall back to TEE on devices that lack a
        // StrongBox or reject the provisioning at generation time. We rely on
        // StrongBoxUnavailableException surfacing from generateKeyPair() rather than
        // pre-checking PackageManager.FEATURE_STRONGBOX_KEYSTORE because the feature
        // flag is conservative -- some devices ship StrongBox without declaring it.
        val strongBoxSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
        if (strongBoxSupported) {
            try {
                return generateKeyPairInternal(strongBox = true)
            } catch (e: StrongBoxUnavailableException) {
                Log.w(
                    TAG,
                    "StrongBox unavailable for $KEY_ALIAS; falling back to TEE-backed key",
                    e
                )
            }
        }
        return generateKeyPairInternal(strongBox = false)
    }

    private fun generateKeyPairInternal(strongBox: Boolean): KeyPair {
        val builder = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
        )
            .setAlgorithmParameterSpec(java.security.spec.ECGenParameterSpec(EC_CURVE))
            .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_NONE)
        if (strongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            builder.setIsStrongBoxBacked(true)
        }
        val spec = builder.build()

        val keyPairGenerator = KeyPairGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_EC,
            ANDROID_KEYSTORE
        )
        keyPairGenerator.initialize(spec)
        val kp = keyPairGenerator.generateKeyPair()
        logKeyInfo(kp, attemptedStrongBox = strongBox)
        return kp
    }

    /**
     * Log where the key ended up: TEE vs StrongBox. On Android 12+ `KeyInfo` exposes
     * `getSecurityLevel()`; on earlier releases we fall back to
     * `isInsideSecureHardware` + `isStrongBoxBacked` (undocumented but widely supported).
     * This is best-effort diagnostic output -- never gates the return value.
     */
    private fun logKeyInfo(keyPair: KeyPair, attemptedStrongBox: Boolean) {
        try {
            val factory = KeyFactory.getInstance(
                keyPair.private.algorithm,
                ANDROID_KEYSTORE
            )
            val keyInfo = factory.getKeySpec(keyPair.private, KeyInfo::class.java)
            val insideSecureHw = keyInfo.isInsideSecureHardware
            val strongBoxBacked = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                keyInfo.isInsideSecureHardware && attemptedStrongBox
            } else {
                false
            }
            Log.i(
                TAG,
                "Device identity key provisioned " +
                    "insideSecureHardware=$insideSecureHw " +
                    "strongBoxBacked=$strongBoxBacked " +
                    "alias=$KEY_ALIAS"
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to inspect KeyInfo for $KEY_ALIAS", e)
            // Keystore provider reflection is best-effort for logging, but a
            // failure here often signals a deeper provider misconfiguration —
            // surface it so we can tell StrongBox/TEE weirdness apart in the
            // field.
            crashReporter.logCaughtException(e)
        }
    }

    private fun androidKeyStore(): KeyStore {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
        keyStore.load(null)
        return keyStore
    }

    companion object {
        const val KEY_ALIAS = "rouse_device_key"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val EC_CURVE = "secp256r1"
        private const val TAG = "DeviceKeyManager"
    }
}
