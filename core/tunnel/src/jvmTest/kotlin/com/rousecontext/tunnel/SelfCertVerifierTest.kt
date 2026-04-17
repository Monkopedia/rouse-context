package com.rousecontext.tunnel

import java.security.MessageDigest
import java.security.cert.X509Certificate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
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

    @Test
    fun `empty known fingerprints with valid cert - backfills and verifies (issue 180)`(): Unit =
        runBlocking {
            // Simulates the upgrade path from a pre-#111 build: a provisioned leaf
            // cert is on disk but rouse_fingerprints.txt is missing/empty. Without
            // self-healing this would be permanent Alert -> 503 lockout.
            val cert = generateSelfSignedCert()
            val backing = mutableSetOf<String>()
            val store = SecurityCertificateStore(
                certChain = listOf(cert.encoded),
                knownFingerprints = backing
            )
            val verifier = SelfCertVerifier(store)

            val result = verifier.verify(listOf(cert.encoded))

            assertIs<SecurityCheckResult.Verified>(result)
            val expectedFingerprint = sha256Hex(cert.encoded)
            assertEquals(setOf(expectedFingerprint), backing)
            // The one-shot bootstrap marker must have been written so the next
            // corruption of the fingerprint file cannot silently re-backfill.
            assertTrue(store.bootstrapMarkerPresent)
            // Second call must also be Verified using the backfilled fingerprint,
            // confirming persistence not just in-memory evaluation.
            assertIs<SecurityCheckResult.Verified>(verifier.verify(listOf(cert.encoded)))
        }

    @Test
    fun `empty known fingerprints after bootstrap marker exists - alert not backfill`(): Unit =
        runBlocking {
            // Issue #210: once the one-shot bootstrap has fired, an empty
            // fingerprints file can only mean corruption / partial-snapshot
            // restore / buggy cleanup. We must NOT silently re-trust whatever
            // cert is presented -- that would defeat pinning.
            val cert = generateSelfSignedCert()
            val backing = mutableSetOf<String>()
            val store = SecurityCertificateStore(
                certChain = listOf(cert.encoded),
                knownFingerprints = backing,
                bootstrapMarkerPresent = true
            )
            val verifier = SelfCertVerifier(store)

            val result = verifier.verify(listOf(cert.encoded))

            assertIs<SecurityCheckResult.Alert>(result)
            // Corruption path must NOT write into the store.
            assertEquals(emptySet<String>(), backing)
        }

    @Test
    fun `bootstrap marker does not affect non-empty fingerprint match`(): Unit = runBlocking {
        // The marker only gates the empty-set backfill branch. Normal verified
        // paths (known fingerprint already recorded) must behave identically
        // regardless of whether the marker exists.
        val cert = generateSelfSignedCert()
        val fingerprint = sha256Hex(cert.encoded)
        val store = SecurityCertificateStore(
            certChain = listOf(cert.encoded),
            knownFingerprints = mutableSetOf(fingerprint),
            bootstrapMarkerPresent = true
        )
        val verifier = SelfCertVerifier(store)

        val result = verifier.verify(listOf(cert.encoded))

        assertIs<SecurityCheckResult.Verified>(result)
    }

    @Test
    fun `bootstrap marker does not affect non-empty fingerprint mismatch`(): Unit = runBlocking {
        // Non-empty garbage must Alert whether or not the marker is set --
        // the guard is strictly for the empty-set path.
        val cert = generateSelfSignedCert()
        val store = SecurityCertificateStore(
            certChain = listOf(cert.encoded),
            knownFingerprints = mutableSetOf("AA:BB:CC:FAKE:FINGERPRINT"),
            bootstrapMarkerPresent = true
        )
        val verifier = SelfCertVerifier(store)

        val result = verifier.verify(listOf(cert.encoded))

        assertIs<SecurityCheckResult.Alert>(result)
    }

    @Test
    fun `empty known fingerprints does not backfill when chain is empty`(): Unit = runBlocking {
        val backing = mutableSetOf<String>()
        val store = SecurityCertificateStore(
            certChain = null,
            knownFingerprints = backing
        )
        val verifier = SelfCertVerifier(store)

        val result = verifier.verify(emptyList())

        assertIs<SecurityCheckResult.Alert>(result)
        assertEquals(emptySet<String>(), backing)
    }

    @Test
    fun `garbage fingerprints content - still alerts on mismatch (no backfill)`(): Unit =
        runBlocking {
            // Non-empty but corrupt/garbage fingerprints must NOT silently pass.
            // Only a truly empty set triggers trust-on-first-sight backfill.
            val cert = generateSelfSignedCert()
            val backing = mutableSetOf("not-a-real-fingerprint", "###garbage###")
            val store = SecurityCertificateStore(
                certChain = listOf(cert.encoded),
                knownFingerprints = backing
            )
            val verifier = SelfCertVerifier(store)

            val result = verifier.verify(listOf(cert.encoded))

            assertIs<SecurityCheckResult.Alert>(result)
            // The garbage entries must remain; we must not overwrite existing state.
            assertEquals(setOf("not-a-real-fingerprint", "###garbage###"), backing)
        }
}

/**
 * CertificateStore for security monitoring tests.
 * Implements the full interface but only uses the security-monitoring subset.
 */
class SecurityCertificateStore(
    private val certChain: List<ByteArray>? = null,
    private val knownFingerprints: MutableSet<String> = mutableSetOf(),
    private val subdomain: String? = "test",
    bootstrapMarkerPresent: Boolean = false
) : CertificateStore {
    var bootstrapMarkerPresent: Boolean = bootstrapMarkerPresent
        private set

    override suspend fun getCertChain(): List<ByteArray>? = certChain
    override suspend fun getPrivateKeyBytes(): ByteArray? = null
    override suspend fun storeCertChain(chain: List<ByteArray>) {}
    override suspend fun getCertExpiry(): Long? = null
    override suspend fun getKnownFingerprints(): Set<String> = knownFingerprints
    override suspend fun storeFingerprint(fingerprint: String) {
        knownFingerprints.add(fingerprint)
    }
    override suspend fun hasFingerprintBootstrapMarker(): Boolean = bootstrapMarkerPresent
    override suspend fun writeFingerprintBootstrapMarker() {
        bootstrapMarkerPresent = true
    }
    override suspend fun storeCertificate(pemChain: String) {}
    override suspend fun getCertificate(): String? = null
    override suspend fun storeClientCertificate(pemChain: String) {}
    override suspend fun getClientCertificate(): String? = null
    override suspend fun storeRelayCaCert(pem: String) {}
    override suspend fun getRelayCaCert(): String? = null
    override suspend fun storeSubdomain(subdomain: String) {}
    override suspend fun getSubdomain(): String? = subdomain
    override suspend fun storeIntegrationSecrets(secrets: Map<String, String>) {}
    override suspend fun getIntegrationSecrets(): Map<String, String>? = null

    // storePrivateKey / getPrivateKey inherit the deprecated default no-op/null impls.
    override suspend fun clear() = Unit
    override suspend fun clearCertificates() = Unit
}

/** CertificateStore that throws on fingerprint access, simulating a storage error. */
class FailingCertificateStore : CertificateStore {
    override suspend fun getCertChain(): List<ByteArray>? = null
    override suspend fun getPrivateKeyBytes(): ByteArray? = null
    override suspend fun storeCertChain(chain: List<ByteArray>) {}
    override suspend fun getCertExpiry(): Long? = null
    override suspend fun getKnownFingerprints(): Set<String> =
        throw java.io.IOException("Storage unavailable")
    override suspend fun storeFingerprint(fingerprint: String): Unit =
        throw java.io.IOException("Storage unavailable")
    override suspend fun hasFingerprintBootstrapMarker(): Boolean =
        throw java.io.IOException("Storage unavailable")
    override suspend fun writeFingerprintBootstrapMarker(): Unit =
        throw java.io.IOException("Storage unavailable")
    override suspend fun storeCertificate(pemChain: String) {}
    override suspend fun getCertificate(): String? = null
    override suspend fun storeClientCertificate(pemChain: String) {}
    override suspend fun getClientCertificate(): String? = null
    override suspend fun storeRelayCaCert(pem: String) {}
    override suspend fun getRelayCaCert(): String? = null
    override suspend fun storeSubdomain(subdomain: String) {}
    override suspend fun getSubdomain(): String? = null
    override suspend fun storeIntegrationSecrets(secrets: Map<String, String>) {}
    override suspend fun getIntegrationSecrets(): Map<String, String>? = null

    // storePrivateKey / getPrivateKey inherit the deprecated default no-op/null impls.
    override suspend fun clear() = Unit
    override suspend fun clearCertificates() = Unit
}
