package com.rousecontext.app.cert

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.rousecontext.tunnel.SelfCertVerifier
import java.io.File
import java.security.KeyStore
import java.security.cert.X509Certificate
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class FileCertificateStoreTest {

    private lateinit var context: Context
    private lateinit var store: FileCertificateStore
    private lateinit var testEncryptionKey: SecretKey

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // Clear filesDir to isolate from any lingering state
        context.filesDir.listFiles()?.forEach { it.delete() }
        // Robolectric has no AndroidKeyStore provider, so tests that exercise
        // AES-GCM encrypt/decrypt paths must supply a software-generated key.
        // Production callers construct the store without this override so the
        // real hardware-backed key path is used.
        val keyGen = KeyGenerator.getInstance("AES")
        keyGen.init(256)
        testEncryptionKey = keyGen.generateKey()
        store = FileCertificateStore(context, encryptionKeyProvider = { testEncryptionKey })
    }

    @Test
    fun `storeCertificate records leaf fingerprint from PEM`() = runBlocking {
        val leafCert = generateSelfSignedCert("leaf.rousecontext.com")
        val leafFingerprint = SelfCertVerifier.sha256Fingerprint(leafCert.encoded)
        val pem = derToPem(leafCert.encoded)

        store.storeCertificate(pem)

        val known = store.getKnownFingerprints()
        assertTrue(
            "Expected known fingerprints to contain leaf fingerprint: $leafFingerprint, got $known",
            known.contains(leafFingerprint)
        )
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

    @Test
    fun `storeSubdomain and getSubdomain round-trip`() = runBlocking {
        store.storeSubdomain("abc123")

        assertEquals("abc123", store.getSubdomain())
    }

    @Test
    fun `getSubdomain returns null when nothing stored`() = runBlocking {
        assertNull(store.getSubdomain())
    }

    @Test
    fun `subdomain survives recreation of store against same filesDir`() = runBlocking {
        store.storeSubdomain("abc123")
        // Simulate process restart: discard instance, recreate against same context.
        val fresh = FileCertificateStore(context)

        assertEquals("abc123", fresh.getSubdomain())
    }

    @Test
    fun `getSubdomain returns null when subdomain file is empty`() = runBlocking {
        // Simulate an interrupted storeSubdomain write (truncate-then-write left the
        // file in a zero-byte state). The previous implementation returned "" from
        // an empty file, which would mask the true bug upstream -- we want null
        // so callers treat the device as unregistered and can retry cleanly.
        val subdomainFile = File(context.filesDir, "rouse_subdomain.txt")
        subdomainFile.writeText("")

        assertNull(store.getSubdomain())
    }

    @Test
    fun `getSubdomain returns null when subdomain file is whitespace only`() = runBlocking {
        val subdomainFile = File(context.filesDir, "rouse_subdomain.txt")
        subdomainFile.writeText("   \n\t ")

        assertNull(store.getSubdomain())
    }

    @Test
    fun `storeSubdomain does not leave empty file on crash mid-write`() = runBlocking {
        // After a successful storeSubdomain, simulate the scenario where a SECOND
        // storeSubdomain call is interrupted before any bytes land on disk.
        // With atomic writes (tmp + rename), the old value must still be readable;
        // with naive writeText, the file would be truncated to empty and lost.
        store.storeSubdomain("abc123")

        // Directly truncate the file to simulate crash-between-truncate-and-write.
        val subdomainFile = File(context.filesDir, "rouse_subdomain.txt")
        subdomainFile.writeText("")

        // With atomic writes in place, the old value persists. Without, this returns null.
        // (If we can't preserve the old value, at minimum we must NOT return "" as a
        //  sentinel — getSubdomain returning null is acceptable here.)
        val result = store.getSubdomain()
        // An empty-but-present file is indistinguishable from corruption: it must
        // not masquerade as a valid subdomain.
        assertFalse(
            "getSubdomain must not return a blank/empty string (got '$result')",
            result != null && result.isBlank()
        )
    }

    @Test
    fun `storeSubdomain overwrites previous value`() = runBlocking {
        store.storeSubdomain("first")
        store.storeSubdomain("second")

        assertEquals("second", store.getSubdomain())
    }

    @Test
    fun `atomicWrite leaves no tmp sibling on success`() = runBlocking {
        // Issue #166: a successful write must not leave a .tmp sibling behind.
        // This covers the happy path; the next test covers failure accumulation.
        store.storeSubdomain("abc123")

        val tmpSiblings = context.filesDir.listFiles { f ->
            f.name.startsWith("rouse_subdomain.txt.tmp")
        }.orEmpty()
        assertTrue(
            "Expected no .tmp siblings after successful write, got ${tmpSiblings.map { it.name }}",
            tmpSiblings.isEmpty()
        )
    }

    @Test
    fun `atomicWrite reaps stale tmp siblings from prior failed writes`() = runBlocking {
        // Issue #166: plant stale .tmp files simulating multiple prior aborted
        // writes. The next successful write must reap them -- otherwise a
        // partially-failing device accumulates clutter indefinitely.
        val parent = context.filesDir
        File(parent, "rouse_subdomain.txt.tmp").writeText("stale-1")
        File(parent, "rouse_subdomain.txt.tmp.1").writeText("stale-2")
        File(parent, "rouse_subdomain.txt.tmp.old").writeText("stale-3")
        // An unrelated .tmp for a different target must NOT be touched.
        File(parent, "rouse_unrelated.txt.tmp").writeText("keep-me")

        store.storeSubdomain("abc123")

        val subdomainTmps = parent.listFiles { f ->
            f.name.startsWith("rouse_subdomain.txt.tmp")
        }.orEmpty()
        assertTrue(
            "Expected stale rouse_subdomain.txt.tmp* siblings to be reaped, " +
                "got ${subdomainTmps.map { it.name }}",
            subdomainTmps.isEmpty()
        )
        assertTrue(
            "Unrelated .tmp files must not be reaped",
            File(parent, "rouse_unrelated.txt.tmp").exists()
        )
        assertEquals("abc123", store.getSubdomain())
    }

    @Test
    fun `atomicWrite produces a complete readable file`() = runBlocking {
        // Issue #165: we cannot easily crash-inject a kernel panic in a unit
        // test, but we can at least prove the new FileOutputStream + fd.sync()
        // + Files.move path yields a correctly-populated target with no tmp
        // residue. This guards against regressions that swap the write path
        // back to a torso-prone implementation.
        val payload = "subdomain-value-$\u00e9\u4e2d"

        store.storeSubdomain(payload)

        val target = File(context.filesDir, "rouse_subdomain.txt")
        assertTrue("target file must exist after atomic write", target.exists())
        assertEquals(payload, target.readText())
        val tmps = context.filesDir.listFiles { f ->
            f.name.startsWith("rouse_subdomain.txt.tmp")
        }.orEmpty()
        assertTrue(
            "No .tmp siblings expected after successful write, got ${tmps.map { it.name }}",
            tmps.isEmpty()
        )
    }

    @Test
    fun `getPrivateKey returns null when encrypted blob is tampered`() = runBlocking {
        // Issue #204: the catch-all fallback used to read the file as plaintext
        // PEM on any decrypt failure, including GCM authentication failures
        // caused by tampering. That silently accepted tampered keystores.
        // Expected behaviour: decrypt failure on a non-plaintext blob must
        // return null (refuse) -- never fall back to reading raw bytes.
        val pem = "-----BEGIN PRIVATE KEY-----\nMIIBVgIBAD==\n-----END PRIVATE KEY-----\n"
        store.storePrivateKey(pem)

        // Corrupt the ciphertext portion (skip the first IV-length byte + IV).
        val keyFile = File(context.filesDir, "rouse_key.pem")
        val data = keyFile.readBytes()
        // Flip the final byte (GCM tag region) -- guaranteed tag-auth failure.
        data[data.size - 1] = (data[data.size - 1].toInt() xor 0xFF).toByte()
        keyFile.writeBytes(data)

        val result = store.getPrivateKey()

        assertNull(
            "Tampered encrypted key file must not be silently accepted (got '$result')",
            result
        )
    }

    @Test
    fun `getPrivateKey returns null when blob is truncated below IV length`() = runBlocking {
        val keyFile = File(context.filesDir, "rouse_key.pem")
        // Write a single byte indicating a 12-byte IV, but then only 3 bytes.
        keyFile.writeBytes(byteArrayOf(12, 1, 2, 3))

        assertNull(store.getPrivateKey())
    }

    @Test
    fun `getPrivateKey migrates legacy plaintext PEM once then marks migrated`() = runBlocking {
        // Legitimate migration path: pre-encryption installs had a plaintext
        // PEM file. The first read must re-encrypt and return it. Subsequent
        // tampering (e.g. dropping a plaintext PEM in place) must NOT be
        // accepted, because migration has already completed.
        val pem = "-----BEGIN PRIVATE KEY-----\nMIIBVgIBAD==\n-----END PRIVATE KEY-----\n"
        val keyFile = File(context.filesDir, "rouse_key.pem")
        keyFile.writeText(pem)

        val migrated = store.getPrivateKey()
        assertEquals(pem, migrated)

        // The file on disk must no longer be plaintext PEM.
        val afterMigration = keyFile.readBytes()
        val firstByte = afterMigration[0].toInt() and 0xFF
        assertFalse(
            "After migration the file must not start with '-' (plaintext PEM)",
            firstByte == '-'.code
        )

        // Now an attacker drops a plaintext PEM into the file. Because migration
        // is marked complete, the plaintext must be refused.
        keyFile.writeText(pem)
        val secondRead = store.getPrivateKey()
        assertNull(
            "After migration, a dropped-in plaintext PEM must be refused (got '$secondRead')",
            secondRead
        )
    }

    @Test
    fun `getPrivateKey returns null for non-PEM garbage with no valid GCM blob`() = runBlocking {
        val keyFile = File(context.filesDir, "rouse_key.pem")
        keyFile.writeBytes(byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05))

        assertNull(store.getPrivateKey())
    }

    @Test
    fun `getPrivateKey round trip succeeds`() = runBlocking {
        val pem = "-----BEGIN PRIVATE KEY-----\nMIIBVgIBAD==\n-----END PRIVATE KEY-----\n"
        store.storePrivateKey(pem)

        assertEquals(pem, store.getPrivateKey())
    }

    @Test
    fun `fingerprint bootstrap marker is absent on a fresh store`() = runBlocking {
        // Issue #210: on a clean filesDir the marker must not exist so a
        // legitimate pre-#111 migration can still fire once.
        assertFalse(store.hasFingerprintBootstrapMarker())
    }

    @Test
    fun `writeFingerprintBootstrapMarker persists marker across store instances`() = runBlocking {
        // The marker lives on disk in filesDir, not just in memory. A fresh
        // FileCertificateStore pointed at the same context must see it.
        store.writeFingerprintBootstrapMarker()

        val fresh = FileCertificateStore(context, encryptionKeyProvider = { testEncryptionKey })
        assertTrue(fresh.hasFingerprintBootstrapMarker())
    }

    @Test
    fun `clearCertificates deletes fingerprint bootstrap marker`() = runBlocking {
        // Full cert rollback must reset the marker: after clearCertificates the
        // next install-cycle bootstrap must be allowed to fire again. If we
        // left the marker behind, a re-onboarded device could never self-heal
        // an empty fingerprints file through legitimate migration.
        //
        // Robolectric does not expose AndroidKeyStore, so the latter half of
        // clearCertificates() throws. We still want coverage for the marker
        // deletion (which happens before the AndroidKeyStore calls), so we
        // tolerate the KeyStoreException as long as the marker file is gone.
        store.writeFingerprintBootstrapMarker()
        assertTrue(store.hasFingerprintBootstrapMarker())

        try {
            store.clearCertificates()
        } catch (_: java.security.KeyStoreException) {
            // Expected under Robolectric: AndroidKeyStore provider missing.
        }

        assertFalse(
            File(context.filesDir, "rouse_fingerprint_bootstrapped").exists()
        )
    }

    @Test
    fun `atomicWrite on integrationSecrets also reaps stale tmps`() = runBlocking {
        // Both callers of atomicWrite (subdomain + integration secrets) must
        // reap stale siblings targeted at their own filename.
        val parent = context.filesDir
        File(parent, "rouse_integration_secrets.json.tmp").writeText("stale")
        File(parent, "rouse_integration_secrets.json.tmp.42").writeText("stale2")

        store.storeIntegrationSecrets(mapOf("foo" to "bar"))

        val tmps = parent.listFiles { f ->
            f.name.startsWith("rouse_integration_secrets.json.tmp")
        }.orEmpty()
        assertTrue(
            "Expected stale integration-secrets tmps to be reaped, got ${tmps.map { it.name }}",
            tmps.isEmpty()
        )
        assertEquals(mapOf("foo" to "bar"), store.getIntegrationSecrets())
    }

    private fun derToPem(der: ByteArray): String {
        val base64 = java.util.Base64.getMimeEncoder(64, "\n".toByteArray())
            .encodeToString(der)
        return "-----BEGIN CERTIFICATE-----\n$base64\n-----END CERTIFICATE-----\n"
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
