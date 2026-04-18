package com.rousecontext.work

import com.rousecontext.tunnel.CertificateStore
import com.rousecontext.tunnel.CtLogFetcher
import com.rousecontext.tunnel.CtLogMonitor
import com.rousecontext.tunnel.SecurityCheckResult
import com.rousecontext.tunnel.SelfCertVerifier
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [StoredCertVerifierSource] and [CtLogMonitorSource] that cover
 * issue #228 -- the "pre-onboarding" classification paths MUST produce
 * [SecurityCheckResult.Skipped] rather than [SecurityCheckResult.Warning]
 * so the worker does not fire a user notification for a state that is
 * simply "not set up yet."
 */
class SecurityCheckSourcesTest {

    @Test
    fun `stored cert source returns Skipped when chain is null (pre-onboarding)`() = runBlocking {
        val store = SourcesTestStore(certChain = null, subdomain = null)
        val source = StoredCertVerifierSource(store, SelfCertVerifier(store))

        val result = source.check()

        assertTrue(
            "expected Skipped, got $result",
            result is SecurityCheckResult.Skipped
        )
    }

    @Test
    fun `stored cert source returns Skipped when chain is empty`() = runBlocking {
        val store = SourcesTestStore(certChain = emptyList(), subdomain = null)
        val source = StoredCertVerifierSource(store, SelfCertVerifier(store))

        val result = source.check()

        assertTrue(
            "expected Skipped, got $result",
            result is SecurityCheckResult.Skipped
        )
    }

    @Test
    fun `stored cert source still returns Warning when chain fetch throws`() = runBlocking {
        // Genuine I/O failure must still surface a warning, not a skip.
        val store = ThrowingStore()
        val source = StoredCertVerifierSource(store, SelfCertVerifier(store))

        val result = source.check()

        assertTrue(
            "expected Warning, got $result",
            result is SecurityCheckResult.Warning
        )
    }

    @Test
    fun `ct log monitor source returns Skipped when subdomain is null`() = runBlocking {
        val store = SourcesTestStore(certChain = null, subdomain = null)
        val monitor = CtLogMonitor(
            certificateStore = store,
            ctLogFetcher = object : CtLogFetcher {
                override suspend fun fetch(domain: String): String = "[]"
            },
            expectedIssuers = setOf("C=US, O=Let's Encrypt, CN=R3"),
            baseDomain = "rousecontext.com"
        )
        val source = CtLogMonitorSource(monitor)

        val result = source.check()

        assertTrue(
            "expected Skipped, got $result",
            result is SecurityCheckResult.Skipped
        )
    }
}

/** Minimal CertificateStore for source-level tests. */
private class SourcesTestStore(
    private val certChain: List<ByteArray>?,
    private val subdomain: String?
) : CertificateStore {
    override suspend fun getCertChain(): List<ByteArray>? = certChain
    override suspend fun getPrivateKeyBytes(): ByteArray? = null
    override suspend fun storeCertChain(chain: List<ByteArray>) = Unit
    override suspend fun getCertExpiry(): Long? = null
    override suspend fun getKnownFingerprints(): Set<String> = emptySet()
    override suspend fun storeFingerprint(fingerprint: String) = Unit
    override suspend fun hasFingerprintBootstrapMarker(): Boolean = false
    override suspend fun writeFingerprintBootstrapMarker() = Unit
    override suspend fun storeCertificate(pemChain: String) = Unit
    override suspend fun getCertificate(): String? = null
    override suspend fun storeClientCertificate(pemChain: String) = Unit
    override suspend fun getClientCertificate(): String? = null
    override suspend fun storeRelayCaCert(pem: String) = Unit
    override suspend fun getRelayCaCert(): String? = null
    override suspend fun storeSubdomain(subdomain: String) = Unit
    override suspend fun getSubdomain(): String? = subdomain
    override suspend fun storeIntegrationSecrets(secrets: Map<String, String>) = Unit
    override suspend fun getIntegrationSecrets(): Map<String, String>? = null
    override suspend fun clear() = Unit
    override suspend fun clearCertificates() = Unit
}

/** CertificateStore that throws on getCertChain, simulating a real storage failure. */
private class ThrowingStore : CertificateStore {
    override suspend fun getCertChain(): List<ByteArray>? =
        throw java.io.IOException("Storage unavailable")
    override suspend fun getPrivateKeyBytes(): ByteArray? = null
    override suspend fun storeCertChain(chain: List<ByteArray>) = Unit
    override suspend fun getCertExpiry(): Long? = null
    override suspend fun getKnownFingerprints(): Set<String> = emptySet()
    override suspend fun storeFingerprint(fingerprint: String) = Unit
    override suspend fun hasFingerprintBootstrapMarker(): Boolean = false
    override suspend fun writeFingerprintBootstrapMarker() = Unit
    override suspend fun storeCertificate(pemChain: String) = Unit
    override suspend fun getCertificate(): String? = null
    override suspend fun storeClientCertificate(pemChain: String) = Unit
    override suspend fun getClientCertificate(): String? = null
    override suspend fun storeRelayCaCert(pem: String) = Unit
    override suspend fun getRelayCaCert(): String? = null
    override suspend fun storeSubdomain(subdomain: String) = Unit
    override suspend fun getSubdomain(): String? = null
    override suspend fun storeIntegrationSecrets(secrets: Map<String, String>) = Unit
    override suspend fun getIntegrationSecrets(): Map<String, String>? = null
    override suspend fun clear() = Unit
    override suspend fun clearCertificates() = Unit
}
