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

/**
 * [CertificateStore] implementation that stores PEM certs in filesDir
 * and the private key in Android Keystore (hardware-backed HSM).
 */
class FileCertificateStore(
    private val context: Context
) : CertificateStore {

    private val filesDir get() = context.filesDir
    private val certFile get() = File(filesDir, CERT_PEM_FILE)
    private val subdomainFile get() = File(filesDir, SUBDOMAIN_FILE)
    private val fingerprintsFile get() = File(filesDir, FINGERPRINTS_FILE)

    override suspend fun storeCertificate(pemChain: String) {
        certFile.writeText(pemChain)
    }

    override suspend fun getCertificate(): String? {
        return if (certFile.exists()) certFile.readText() else null
    }

    override suspend fun storeSubdomain(subdomain: String) {
        subdomainFile.writeText(subdomain)
    }

    override suspend fun getSubdomain(): String? {
        return if (subdomainFile.exists()) subdomainFile.readText().trim() else null
    }

    override suspend fun storePrivateKey(pemKey: String) {
        // No-op on Android: the private key lives in the hardware-backed Keystore.
        // Ensure the key alias exists for signing operations.
        ensureKeyPairExists()
    }

    override suspend fun getPrivateKey(): String? {
        // Android Keystore keys cannot be exported as PEM.
        // Return the alias as a reference so callers know a key exists.
        val keyStore = androidKeyStore()
        return if (keyStore.containsAlias(KEY_ALIAS)) KEY_ALIAS else null
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
        subdomainFile.delete()
        fingerprintsFile.delete()
        val keyStore = androidKeyStore()
        if (keyStore.containsAlias(KEY_ALIAS)) {
            keyStore.deleteEntry(KEY_ALIAS)
        }
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
        private const val SUBDOMAIN_FILE = "rouse_subdomain.txt"
        private const val FINGERPRINTS_FILE = "rouse_fingerprints.txt"
        private const val KEY_ALIAS = "rouse_device_key"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
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
