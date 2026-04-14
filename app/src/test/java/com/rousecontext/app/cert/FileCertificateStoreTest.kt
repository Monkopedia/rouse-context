package com.rousecontext.app.cert

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.rousecontext.tunnel.SelfCertVerifier
import java.security.KeyStore
import java.security.cert.X509Certificate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class FileCertificateStoreTest {

    private lateinit var store: FileCertificateStore

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        // Clear filesDir to isolate from any lingering state
        context.filesDir.listFiles()?.forEach { it.delete() }
        store = FileCertificateStore(context)
    }

    @Test
    fun `storeCertChain records leaf fingerprint`() = runBlocking {
        val leafCert = generateSelfSignedCert("leaf.rousecontext.com")
        val leafFingerprint = SelfCertVerifier.sha256Fingerprint(leafCert.encoded)

        store.storeCertChain(listOf(leafCert.encoded))

        val known = store.getKnownFingerprints()
        assertTrue(
            "Expected known fingerprints to contain leaf fingerprint: $leafFingerprint, got $known",
            known.contains(leafFingerprint)
        )
    }

    @Test
    fun `storeCertChain twice retains both fingerprints`() = runBlocking {
        val oldCert = generateSelfSignedCert("old.rousecontext.com")
        val newCert = generateSelfSignedCert("new.rousecontext.com")
        val oldFingerprint = SelfCertVerifier.sha256Fingerprint(oldCert.encoded)
        val newFingerprint = SelfCertVerifier.sha256Fingerprint(newCert.encoded)

        store.storeCertChain(listOf(oldCert.encoded))
        store.storeCertChain(listOf(newCert.encoded))

        val known = store.getKnownFingerprints()
        assertTrue(
            "Expected known fingerprints to contain old fingerprint: $oldFingerprint, got $known",
            known.contains(oldFingerprint)
        )
        assertTrue(
            "Expected known fingerprints to contain new fingerprint: $newFingerprint, got $known",
            known.contains(newFingerprint)
        )
    }

    @Test
    fun `storeCertChain does not record intermediate fingerprints`() = runBlocking {
        val leafCert = generateSelfSignedCert("leaf.rousecontext.com")
        val intermediateCert = generateSelfSignedCert("intermediate.rousecontext.com")
        val leafFingerprint = SelfCertVerifier.sha256Fingerprint(leafCert.encoded)
        val intermediateFingerprint = SelfCertVerifier.sha256Fingerprint(intermediateCert.encoded)

        store.storeCertChain(listOf(leafCert.encoded, intermediateCert.encoded))

        val known = store.getKnownFingerprints()
        assertTrue(
            "Expected known fingerprints to contain leaf fingerprint: $leafFingerprint, got $known",
            known.contains(leafFingerprint)
        )
        assertFalse(
            "Expected known fingerprints NOT to contain intermediate fingerprint: " +
                "$intermediateFingerprint, got $known",
            known.contains(intermediateFingerprint)
        )
        assertEquals(1, known.size)
    }

    private fun generateSelfSignedCert(cn: String): X509Certificate {
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

        val keyStore = KeyStore.getInstance("PKCS12")
        tempFile.inputStream().use { keyStore.load(it, "test123".toCharArray()) }
        tempFile.delete()

        return keyStore.getCertificate("test") as X509Certificate
    }
}
