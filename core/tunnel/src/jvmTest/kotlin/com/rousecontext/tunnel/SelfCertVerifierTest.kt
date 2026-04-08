package com.rousecontext.tunnel

import java.security.MessageDigest
import java.security.cert.X509Certificate
import kotlin.test.Test
import kotlin.test.assertIs
import kotlinx.coroutines.runBlocking

class SelfCertVerifierTest {

    private fun sha256Hex(data: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(data).joinToString(":") { "%02X".format(it) }
    }

    private fun generateSelfSignedCert(cn: String = "test.rousecontext.com"): X509Certificate {
        val tempFile = java.io.File.createTempFile("test-cert-", ".p12")
        tempFile.deleteOnExit()
        tempFile.delete()

        val process = ProcessBuilder(
            "keytool",
            "-genkeypair",
            "-alias", "test",
            "-keyalg", "RSA",
            "-keysize", "2048",
            "-sigalg", "SHA256withRSA",
            "-dname", "CN=$cn",
            "-validity", "365",
            "-storetype", "PKCS12",
            "-keystore", tempFile.absolutePath,
            "-storepass", "test123",
            "-keypass", "test123"
        )
            .redirectErrorStream(true)
            .start()

        process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()
        require(exitCode == 0) { "keytool failed" }

        val keyStore = java.security.KeyStore.getInstance("PKCS12")
        tempFile.inputStream().use { keyStore.load(it, "test123".toCharArray()) }
        tempFile.delete()

        return keyStore.getCertificate("test") as X509Certificate
    }

    @Test
    fun `fingerprint matches provisioned cert - verified`(): Unit = runBlocking {
        val cert = generateSelfSignedCert()
        val fingerprint = sha256Hex(cert.encoded)
        val store = SecurityCertificateStore(
            certChain = listOf(cert.encoded),
            knownFingerprints = mutableSetOf(fingerprint)
        )
        val verifier = SelfCertVerifier(store)

        val result = verifier.verify(listOf(cert.encoded))

        assertIs<SecurityCheckResult.Verified>(result)
    }

    @Test
    fun `fingerprint mismatch - alert`(): Unit = runBlocking {
        val cert = generateSelfSignedCert()
        val store = SecurityCertificateStore(
            certChain = listOf(cert.encoded),
            knownFingerprints = mutableSetOf("AA:BB:CC:FAKE:FINGERPRINT")
        )
        val verifier = SelfCertVerifier(store)

        val result = verifier.verify(listOf(cert.encoded))

        assertIs<SecurityCheckResult.Alert>(result)
    }

    @Test
    fun `during renewal window both old and new fingerprints accepted`(): Unit = runBlocking {
        val oldCert = generateSelfSignedCert("old.rousecontext.com")
        val newCert = generateSelfSignedCert("new.rousecontext.com")
        val oldFingerprint = sha256Hex(oldCert.encoded)
        val newFingerprint = sha256Hex(newCert.encoded)
        val store = SecurityCertificateStore(
            certChain = listOf(newCert.encoded),
            knownFingerprints = mutableSetOf(oldFingerprint, newFingerprint)
        )
        val verifier = SelfCertVerifier(store)

        // New cert matches one of the known fingerprints
        val result = verifier.verify(listOf(newCert.encoded))
        assertIs<SecurityCheckResult.Verified>(result)

        // Old cert also matches
        val resultOld = verifier.verify(listOf(oldCert.encoded))
        assertIs<SecurityCheckResult.Verified>(resultOld)
    }

    @Test
    fun `network error during store access - warning not alert`(): Unit = runBlocking {
        val store = FailingCertificateStore()
        val verifier = SelfCertVerifier(store)

        val result = verifier.verify(listOf(ByteArray(100)))

        assertIs<SecurityCheckResult.Warning>(result)
    }

    @Test
    fun `cert chain with intermediate - checks leaf only`(): Unit = runBlocking {
        val leafCert = generateSelfSignedCert("leaf.rousecontext.com")
        val intermediateCert = generateSelfSignedCert("intermediate.rousecontext.com")
        val leafFingerprint = sha256Hex(leafCert.encoded)
        val store = SecurityCertificateStore(
            certChain = listOf(leafCert.encoded, intermediateCert.encoded),
            knownFingerprints = mutableSetOf(leafFingerprint)
        )
        val verifier = SelfCertVerifier(store)

        // Chain has leaf + intermediate; only leaf fingerprint is checked
        val result = verifier.verify(listOf(leafCert.encoded, intermediateCert.encoded))

        assertIs<SecurityCheckResult.Verified>(result)
    }
}

/**
 * CertificateStore for security monitoring tests.
 * Implements the full interface but only uses the security-monitoring subset.
 */
class SecurityCertificateStore(
    private val certChain: List<ByteArray>? = null,
    private val knownFingerprints: MutableSet<String> = mutableSetOf(),
    private val subdomain: String? = "test"
) : CertificateStore {
    override suspend fun getCertChain(): List<ByteArray>? = certChain
    override suspend fun getPrivateKeyBytes(): ByteArray? = null
    override suspend fun storeCertChain(chain: List<ByteArray>) {}
    override suspend fun getCertExpiry(): Long? = null
    override suspend fun getKnownFingerprints(): Set<String> = knownFingerprints
    override suspend fun storeFingerprint(fingerprint: String) {
        knownFingerprints.add(fingerprint)
    }
    override suspend fun storeCertificate(pemChain: String) {}
    override suspend fun getCertificate(): String? = null
    override suspend fun storeClientCertificate(pemChain: String) {}
    override suspend fun getClientCertificate(): String? = null
    override suspend fun storeRelayCaCert(pem: String) {}
    override suspend fun getRelayCaCert(): String? = null
    override suspend fun storeSubdomain(subdomain: String) {}
    override suspend fun getSubdomain(): String? = subdomain
    override suspend fun storeSecretPrefix(prefix: String) {}
    override suspend fun getSecretPrefix(): String? = null
    override suspend fun storePrivateKey(pemKey: String) {}
    override suspend fun getPrivateKey(): String? = null
    override suspend fun clear() {}
}

/** CertificateStore that throws on fingerprint access, simulating a storage error. */
class FailingCertificateStore : CertificateStore {
    override suspend fun getCertChain(): List<ByteArray>? = null
    override suspend fun getPrivateKeyBytes(): ByteArray? = null
    override suspend fun storeCertChain(chain: List<ByteArray>) {}
    override suspend fun getCertExpiry(): Long? = null
    override suspend fun getKnownFingerprints(): Set<String> {
        throw java.io.IOException("Storage unavailable")
    }
    override suspend fun storeFingerprint(fingerprint: String) {
        throw java.io.IOException("Storage unavailable")
    }
    override suspend fun storeCertificate(pemChain: String) {}
    override suspend fun getCertificate(): String? = null
    override suspend fun storeClientCertificate(pemChain: String) {}
    override suspend fun getClientCertificate(): String? = null
    override suspend fun storeRelayCaCert(pem: String) {}
    override suspend fun getRelayCaCert(): String? = null
    override suspend fun storeSubdomain(subdomain: String) {}
    override suspend fun getSubdomain(): String? = null
    override suspend fun storeSecretPrefix(prefix: String) {}
    override suspend fun getSecretPrefix(): String? = null
    override suspend fun storePrivateKey(pemKey: String) {}
    override suspend fun getPrivateKey(): String? = null
    override suspend fun clear() {}
}
