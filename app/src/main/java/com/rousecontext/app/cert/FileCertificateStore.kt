package com.rousecontext.app.cert

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.rousecontext.tunnel.CertificateStore
import java.io.File
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * [CertificateStore] implementation that stores PEM certs in filesDir
 * and the private key in Android Keystore (hardware-backed HSM).
 */
class FileCertificateStore(private val context: Context) : CertificateStore {

    private val filesDir get() = context.filesDir
    private val certFile get() = File(filesDir, CERT_PEM_FILE)
    private val subdomainFile get() = File(filesDir, SUBDOMAIN_FILE)
    private val integrationSecretsFile get() = File(filesDir, INTEGRATION_SECRETS_FILE)
    private val legacySecretPrefixFile get() = File(filesDir, LEGACY_SECRET_PREFIX_FILE)
    private val fingerprintsFile get() = File(filesDir, FINGERPRINTS_FILE)

    override suspend fun storeCertificate(pemChain: String) {
        certFile.writeText(pemChain)
    }

    override suspend fun getCertificate(): String? =
        if (certFile.exists()) certFile.readText() else null

    override suspend fun storeClientCertificate(pemChain: String) {
        File(filesDir, CLIENT_CERT_PEM_FILE).writeText(pemChain)
    }

    override suspend fun getClientCertificate(): String? {
        val f = File(filesDir, CLIENT_CERT_PEM_FILE)
        return if (f.exists()) f.readText() else null
    }

    override suspend fun storeRelayCaCert(pem: String) {
        File(filesDir, RELAY_CA_PEM_FILE).writeText(pem)
    }

    override suspend fun getRelayCaCert(): String? {
        val f = File(filesDir, RELAY_CA_PEM_FILE)
        return if (f.exists()) f.readText() else null
    }

    override suspend fun storeSubdomain(subdomain: String) {
        subdomainFile.writeText(subdomain)
    }

    override suspend fun getSubdomain(): String? =
        if (subdomainFile.exists()) subdomainFile.readText().trim() else null

    override suspend fun storeIntegrationSecrets(secrets: Map<String, String>) {
        val json = JsonObject(secrets.mapValues { (_, v) -> JsonPrimitive(v) })
        integrationSecretsFile.writeText(json.toString())
    }

    override suspend fun getIntegrationSecrets(): Map<String, String>? {
        if (integrationSecretsFile.exists()) {
            return try {
                val text = integrationSecretsFile.readText().trim()
                val obj = Json.parseToJsonElement(text).jsonObject
                obj.mapValues { (_, v) -> v.jsonPrimitive.content }
            } catch (_: Exception) {
                null
            }
        }
        // Migration: read legacy secret_prefix file and synthesize a map.
        // The old format was "{adjective}-{noun}" where noun is the integration name.
        // Without knowing which integrations exist, return the raw prefix for all
        // lookups -- callers will match by integration name suffix.
        if (legacySecretPrefixFile.exists()) {
            val prefix = legacySecretPrefixFile.readText().trim()
            if (prefix.isNotEmpty()) {
                return mapOf("_legacy" to prefix)
            }
        }
        return null
    }

    override suspend fun storePrivateKey(pemKey: String) {
        val encryptionKey = getOrCreateEncryptionKey()
        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, encryptionKey)
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(pemKey.toByteArray(Charsets.UTF_8))
        // Write IV length (1 byte), IV, then ciphertext
        val keyFile = File(filesDir, KEY_PEM_FILE)
        keyFile.outputStream().use { out ->
            out.write(iv.size)
            out.write(iv)
            out.write(ciphertext)
        }
    }

    override suspend fun getPrivateKey(): String? {
        val keyFile = File(filesDir, KEY_PEM_FILE)
        if (!keyFile.exists()) return null
        return try {
            val data = keyFile.readBytes()
            if (data.isEmpty()) return null
            val ivLen = data[0].toInt() and 0xFF
            if (data.size < 1 + ivLen) return null
            val iv = data.copyOfRange(1, 1 + ivLen)
            val ciphertext = data.copyOfRange(1 + ivLen, data.size)
            val encryptionKey = getOrCreateEncryptionKey()
            val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, encryptionKey, GCMParameterSpec(GCM_TAG_LENGTH, iv))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        } catch (_: Exception) {
            // Fall back to reading as plaintext PEM for migration from unencrypted storage
            val text = keyFile.readText()
            if (text.contains("BEGIN") && text.contains("PRIVATE KEY")) {
                // Re-encrypt in place for future reads
                storePrivateKey(text)
                text
            } else {
                null
            }
        }
    }

    override suspend fun getCertChain(): List<ByteArray>? {
        val pem = getCertificate() ?: return null
        return parsePemCertificates(pem).map { it.encoded }
    }

    override suspend fun getPrivateKeyBytes(): ByteArray? {
        // Android Keystore does not allow exporting private key bytes.
        // Return null to signal that the key is hardware-bound.
        return null
    }

    override suspend fun storeCertChain(chain: List<ByteArray>) {
        val factory = CertificateFactory.getInstance("X.509")
        val pemBuilder = StringBuilder()
        for (der in chain) {
            val cert = factory.generateCertificate(der.inputStream())
            pemBuilder.append("-----BEGIN CERTIFICATE-----\n")
            pemBuilder.append(
                java.util.Base64.getMimeEncoder(LINE_LENGTH, "\n".toByteArray())
                    .encodeToString(cert.encoded)
            )
            pemBuilder.append("\n-----END CERTIFICATE-----\n")
        }
        storeCertificate(pemBuilder.toString())
        // Record the leaf cert fingerprint so SelfCertVerifier can confirm
        // the relay is presenting our provisioned certificate. Without this,
        // getKnownFingerprints() stays empty and every security check alerts.
        // During renewal, both old and new fingerprints accumulate in the file
        // (no pruning) — at ~1 entry per 90-day renewal this is not a concern.
        chain.firstOrNull()?.let { leafDer ->
            storeFingerprint(sha256Fingerprint(leafDer))
        }
    }

    override suspend fun getCertExpiry(): Long? {
        val pem = getCertificate() ?: return null
        val certs = parsePemCertificates(pem)
        return certs.firstOrNull()?.notAfter?.time
    }

    override suspend fun getKnownFingerprints(): Set<String> {
        if (!fingerprintsFile.exists()) return emptySet()
        return fingerprintsFile.readText()
            .lines()
            .filter { it.isNotBlank() }
            .toSet()
    }

    override suspend fun storeFingerprint(fingerprint: String) {
        fingerprintsFile.appendText("$fingerprint\n")
    }

    override suspend fun clear() {
        certFile.delete()
        File(filesDir, CLIENT_CERT_PEM_FILE).delete()
        File(filesDir, RELAY_CA_PEM_FILE).delete()
        File(filesDir, KEY_PEM_FILE).delete()
        subdomainFile.delete()
        integrationSecretsFile.delete()
        legacySecretPrefixFile.delete()
        fingerprintsFile.delete()
        val keyStore = androidKeyStore()
        if (keyStore.containsAlias(KEY_ALIAS)) {
            keyStore.deleteEntry(KEY_ALIAS)
        }
        if (keyStore.containsAlias(ENCRYPTION_KEY_ALIAS)) {
            keyStore.deleteEntry(ENCRYPTION_KEY_ALIAS)
        }
    }

    private fun getOrCreateEncryptionKey(): SecretKey {
        val keyStore = androidKeyStore()
        val existingKey = keyStore.getKey(ENCRYPTION_KEY_ALIAS, null) as? SecretKey
        if (existingKey != null) return existingKey

        val spec = KeyGenParameterSpec.Builder(
            ENCRYPTION_KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(AES_KEY_SIZE)
            .build()

        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEYSTORE
        )
        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }

    private fun ensureKeyPairExists() {
        val keyStore = androidKeyStore()
        if (keyStore.containsAlias(KEY_ALIAS)) return

        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
        )
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setKeySize(EC_KEY_SIZE)
            .build()

        val keyPairGenerator = KeyPairGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_EC,
            ANDROID_KEYSTORE
        )
        keyPairGenerator.initialize(spec)
        keyPairGenerator.generateKeyPair()
    }

    private fun parsePemCertificates(pem: String): List<X509Certificate> {
        val factory = CertificateFactory.getInstance("X.509")
        val certs = mutableListOf<X509Certificate>()
        val regex = Regex(
            "-----BEGIN CERTIFICATE-----(.+?)-----END CERTIFICATE-----",
            RegexOption.DOT_MATCHES_ALL
        )
        for (match in regex.findAll(pem)) {
            val base64 = match.groupValues[1].replace("\\s".toRegex(), "")
            val der = java.util.Base64.getDecoder().decode(base64)
            val cert = factory.generateCertificate(der.inputStream()) as X509Certificate
            certs.add(cert)
        }
        return certs
    }

    private fun androidKeyStore(): KeyStore {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
        keyStore.load(null)
        return keyStore
    }

    companion object {
        private const val CERT_PEM_FILE = "rouse_cert.pem"
        private const val CLIENT_CERT_PEM_FILE = "rouse_client_cert.pem"
        private const val RELAY_CA_PEM_FILE = "rouse_relay_ca.pem"
        private const val KEY_PEM_FILE = "rouse_key.pem"
        private const val SUBDOMAIN_FILE = "rouse_subdomain.txt"
        private const val INTEGRATION_SECRETS_FILE = "rouse_integration_secrets.json"
        private const val LEGACY_SECRET_PREFIX_FILE = "rouse_secret_prefix.txt"
        private const val FINGERPRINTS_FILE = "rouse_fingerprints.txt"
        private const val KEY_ALIAS = "rouse_device_key"
        private const val ENCRYPTION_KEY_ALIAS = "rouse_key_encryption_key"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val AES_GCM_TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_LENGTH = 128
        private const val AES_KEY_SIZE = 256
        private const val LINE_LENGTH = 64
        private const val EC_KEY_SIZE = 256

        /** Compute SHA-256 fingerprint of a DER-encoded certificate. */
        fun sha256Fingerprint(derBytes: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256")
            return digest.digest(derBytes)
                .joinToString(":") { "%02X".format(it) }
        }
    }
}
