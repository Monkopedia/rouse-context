package com.rousecontext.notifications.capture

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts and decrypts string fields using AES-GCM with a key stored in
 * Android Keystore. Used to protect sensitive notification and audit data at rest.
 *
 * The encrypted output is base64-encoded: `iv:ciphertext` where both parts are
 * base64url-encoded. The IV is 12 bytes (AES-GCM standard).
 */
@Suppress("UnusedPrivateProperty")
class FieldEncryptor(private val context: Context) {

    private val secretKey: SecretKey = getOrCreateKey()

    /**
     * Encrypts a plaintext string. Returns a base64-encoded string containing
     * the IV and ciphertext, or null if the input is null.
     */
    fun encrypt(plaintext: String?): String? {
        if (plaintext == null) return null
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val encoder = Base64.getUrlEncoder().withoutPadding()
        return "${encoder.encodeToString(iv)}:${encoder.encodeToString(ciphertext)}"
    }

    /**
     * Decrypts an encrypted string previously produced by [encrypt].
     * Returns null if the input is null or decryption fails.
     */
    fun decrypt(encrypted: String?): String? {
        if (encrypted == null) return null
        return try {
            val parts = encrypted.split(":", limit = 2)
            if (parts.size != 2) return null
            val decoder = Base64.getUrlDecoder()
            val iv = decoder.decode(parts[0])
            val ciphertext = decoder.decode(parts[1])
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_LENGTH, iv))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "rouse_field_encryption_key"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_LENGTH = 128

        private fun getOrCreateKey(): SecretKey {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
            keyStore.load(null)

            val existingKey = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
            if (existingKey != null) return existingKey

            val spec = KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()

            val keyGenerator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                ANDROID_KEYSTORE
            )
            keyGenerator.init(spec)
            return keyGenerator.generateKey()
        }
    }
}
