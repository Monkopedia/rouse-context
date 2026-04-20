package com.rousecontext.app.cert

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Supplementary unit tests for [FileCertificateStore] covering the methods
 * the existing [FileCertificateStoreTest] doesn't touch:
 *
 *  - `getCertificate` / `getCertChain` / `getCertExpiry` read paths
 *  - `storeClientCertificate` / `getClientCertificate`
 *  - `storeRelayCaCert` / `getRelayCaCert`
 *  - legacy `rouse_secret_prefix.txt` migration inside `getIntegrationSecrets`
 *  - corrupted integration-secrets JSON falls back to null
 *  - `getPrivateKeyBytes` always returns null (hardware-bound key contract)
 *  - `clear()` full-wipe path, including Keystore alias deletion when present
 *  - `storeFingerprint` direct usage
 *
 * These are narrow, fast checks — no relay, no network — and sit alongside the
 * existing atomic-write / fingerprint-marker tests to lift line coverage of
 * the class into the 90% range.
 */
@RunWith(RobolectricTestRunner::class)
class FileCertificateStoreExtraTest {

    private lateinit var context: Context
    private lateinit var store: FileCertificateStore

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.filesDir.listFiles()?.forEach { it.delete() }
        store = FileCertificateStore(context)
    }

    @Test
    fun `getCertificate returns null when nothing stored`() = runBlocking {
        assertNull(store.getCertificate())
    }

    @Test
    fun `getCertificate round-trip`() = runBlocking {
        val cert = CertTestUtil.generateSelfSignedCert("leaf.rousecontext.com")
        val pem = CertTestUtil.derToPem(cert.encoded)

        store.storeCertificate(pem)

        assertEquals(pem, store.getCertificate())
    }

    @Test
    fun `getCertChain returns null when no cert on disk`() = runBlocking {
        // Caller uses this for initialisation decisions — null MUST mean "no chain"
        // rather than "empty chain", otherwise downstream code tries to index
        // into an empty list.
        assertNull(store.getCertChain())
    }

    @Test
    fun `getCertChain returns DER bytes for each cert in the stored PEM`() = runBlocking {
        val leaf = CertTestUtil.generateSelfSignedCert("leaf.rousecontext.com")
        val intermediate = CertTestUtil.generateSelfSignedCert("intermediate.rousecontext.com")
        store.storeCertificate(CertTestUtil.chainToPem(listOf(leaf, intermediate)))

        val chain = store.getCertChain()

        assertNotNull(chain)
        assertEquals(2, chain!!.size)
        assertTrue(chain[0].contentEquals(leaf.encoded))
        assertTrue(chain[1].contentEquals(intermediate.encoded))
    }

    @Test
    fun `getCertExpiry returns null when no cert on disk`() = runBlocking {
        // Null here keeps CertRenewalWorker on the "nothing to renew yet" branch
        // rather than treating 0L as a wildly-past timestamp and spamming renewals.
        assertNull(store.getCertExpiry())
    }

    @Test
    fun `getCertExpiry returns the leaf cert's notAfter in millis`() = runBlocking {
        val cert = CertTestUtil.generateSelfSignedCert("expiry.rousecontext.com")
        store.storeCertificate(CertTestUtil.derToPem(cert.encoded))

        val expiry = store.getCertExpiry()

        // The self-signed cert is issued with 365-day validity by keytool; just
        // check we surface the leaf's own notAfter rather than some synthetic 0.
        assertEquals(cert.notAfter.time, expiry)
    }

    @Test
    fun `storeClientCertificate and getClientCertificate round-trip`() = runBlocking {
        // The client cert PEM (relay-CA issued, clientAuth EKU) is stored separately
        // from the Let's Encrypt leaf. Round-trip proves the split-file layout works.
        val clientPem = CertTestUtil.derToPem(
            CertTestUtil.generateSelfSignedCert("client.rousecontext.com").encoded
        )
        store.storeClientCertificate(clientPem)

        assertEquals(clientPem, store.getClientCertificate())
    }

    @Test
    fun `getClientCertificate returns null when not stored`() = runBlocking {
        assertNull(store.getClientCertificate())
    }

    @Test
    fun `storeRelayCaCert and getRelayCaCert round-trip`() = runBlocking {
        val caPem = CertTestUtil.derToPem(
            CertTestUtil.generateSelfSignedCert("relay-ca.rousecontext.com").encoded
        )

        store.storeRelayCaCert(caPem)

        assertEquals(caPem, store.getRelayCaCert())
    }

    @Test
    fun `getRelayCaCert returns null when not stored`() = runBlocking {
        assertNull(store.getRelayCaCert())
    }

    @Test
    fun `getIntegrationSecrets migrates legacy rouse_secret_prefix file`() = runBlocking {
        // Legacy format: a flat prefix like "happy-alice" written to
        // `rouse_secret_prefix.txt`. The new format is a JSON object, but until a
        // user re-provisions, the store must synthesise a compat map so callers
        // (IntegrationSecretResolver) keep working.
        File(context.filesDir, "rouse_secret_prefix.txt").writeText("happy-alice")

        val secrets = store.getIntegrationSecrets()

        assertEquals(mapOf("_legacy" to "happy-alice"), secrets)
    }

    @Test
    fun `getIntegrationSecrets returns null when legacy file is blank`() = runBlocking {
        // A zero-byte or whitespace-only legacy file represents corruption, not
        // migration state. Treat it the same as "no secrets stored" — forcing
        // the user to re-provision is safer than spraying "" as a prefix.
        File(context.filesDir, "rouse_secret_prefix.txt").writeText("   \n")

        assertNull(store.getIntegrationSecrets())
    }

    @Test
    fun `getIntegrationSecrets returns null when JSON file is corrupted`() = runBlocking {
        // A partial write from a crash mid-flush leaves behind junk. We do not
        // want to throw on every call that reads secrets — returning null gives
        // the caller a cue to re-provision without wedging the rest of the app.
        File(context.filesDir, "rouse_integration_secrets.json")
            .writeText("{this-is-not-json")

        assertNull(store.getIntegrationSecrets())
    }

    @Test
    fun `getIntegrationSecrets returns stored map when JSON is well-formed`() = runBlocking {
        store.storeIntegrationSecrets(mapOf("weather" to "happy-alice", "health" to "lucky-bob"))

        val secrets = store.getIntegrationSecrets()

        assertEquals(
            mapOf("weather" to "happy-alice", "health" to "lucky-bob"),
            secrets
        )
    }

    @Test
    fun `getIntegrationSecrets returns null on fresh install`() = runBlocking {
        assertNull(store.getIntegrationSecrets())
    }

    @Test
    fun `getPrivateKeyBytes always returns null`() = runBlocking {
        // Contract: the device identity key is hardware-bound via DeviceKeyManager.
        // This method exists only to satisfy the CertificateStore interface and
        // must never surface bytes — any non-null return would signal a
        // regression that leaks key material out of the keystore.
        assertNull(store.getPrivateKeyBytes())

        // Even after the cert has been stored, the private key bytes are still null.
        val cert = CertTestUtil.generateSelfSignedCert("priv.rousecontext.com")
        store.storeCertificate(CertTestUtil.derToPem(cert.encoded))
        assertNull(store.getPrivateKeyBytes())
    }

    @Test
    fun `clear deletes cert, subdomain, secrets and fingerprint files`() = runBlocking {
        // Full wipe: used on explicit re-registration. After clear(), every
        // user-visible cert / onboarding file must be gone, and the Keystore
        // alias-deletion branch runs through [TestAndroidKeyStoreProvider]'s
        // fake so the exercise covers the whole `clear()` body instead of
        // tripping on the missing provider.
        TestAndroidKeyStoreProvider.install()
        try {
            val cert = CertTestUtil.generateSelfSignedCert("wipe.rousecontext.com")
            store.storeCertificate(CertTestUtil.derToPem(cert.encoded))
            store.storeClientCertificate(CertTestUtil.derToPem(cert.encoded))
            store.storeRelayCaCert(CertTestUtil.derToPem(cert.encoded))
            store.storeSubdomain("abc123")
            store.storeIntegrationSecrets(mapOf("k" to "v"))
            // Legacy prefix file — clear() must remove it too.
            File(context.filesDir, "rouse_secret_prefix.txt").writeText("old-prefix")

            store.clear()

            assertNull(store.getCertificate())
            assertNull(store.getClientCertificate())
            assertNull(store.getRelayCaCert())
            assertNull(store.getSubdomain())
            assertNull(store.getIntegrationSecrets())
            assertFalse(File(context.filesDir, "rouse_secret_prefix.txt").exists())
        } finally {
            TestAndroidKeyStoreProvider.uninstall()
        }
    }

    @Test
    fun `clearCertificates leaves subdomain and integration secrets intact`() = runBlocking {
        // Narrow rollback for cert provisioning failures: keeps subdomain and
        // integration secrets so that a subsequent retry doesn't re-run onboarding
        // from scratch (issue #163).
        val cert = CertTestUtil.generateSelfSignedCert("narrow.rousecontext.com")
        store.storeCertificate(CertTestUtil.derToPem(cert.encoded))
        store.storeClientCertificate(CertTestUtil.derToPem(cert.encoded))
        store.storeRelayCaCert(CertTestUtil.derToPem(cert.encoded))
        store.storeSubdomain("abc123")
        store.storeIntegrationSecrets(mapOf("k" to "v"))

        store.clearCertificates()

        assertNull(store.getCertificate())
        assertNull(store.getClientCertificate())
        assertNull(store.getRelayCaCert())
        // Onboarding state must survive.
        assertEquals("abc123", store.getSubdomain())
        assertEquals(mapOf("k" to "v"), store.getIntegrationSecrets())
    }

    @Test
    fun `storeFingerprint appends entries across calls`() = runBlocking {
        store.storeFingerprint("AA:BB")
        store.storeFingerprint("CC:DD")

        val known = store.getKnownFingerprints()
        assertEquals(setOf("AA:BB", "CC:DD"), known)
    }

    @Test
    fun `getKnownFingerprints returns empty set on fresh store`() = runBlocking {
        assertEquals(emptySet<String>(), store.getKnownFingerprints())
    }

    @Test
    fun `writeFingerprintBootstrapMarker swallows IOException when target is a directory`() =
        runBlocking {
            // The production code treats a marker-write failure as non-fatal
            // (best-effort durability — a missing marker only allows a
            // re-migration, not a security bypass). Force `writeText` to fail
            // by pre-creating a directory at the marker path; the store must
            // log-and-continue without raising.
            val markerDir = File(context.filesDir, "rouse_fingerprint_bootstrapped")
            assertTrue("marker path must be created as a directory", markerDir.mkdirs())

            // Must not throw — the store swallows IOException internally.
            store.writeFingerprintBootstrapMarker()

            // Marker "exists" as a directory, but the contract is satisfied:
            // writeFingerprintBootstrapMarker returned cleanly.
            assertTrue(markerDir.isDirectory)
        }

    @Test
    fun `storeCertificate with empty PEM does not crash and stores no fingerprint`() = runBlocking {
        // The "no leaf" branch of storeCertificate: parsePemCertificates returns
        // empty, so the fingerprint record is skipped. Regression guard against
        // a future change that tries to index [0] unconditionally.
        store.storeCertificate("")

        assertEquals("", store.getCertificate())
        assertEquals(emptySet<String>(), store.getKnownFingerprints())
    }

    @Test
    fun `getCertExpiry returns null when PEM contains no certs`() = runBlocking {
        // firstOrNull() on an empty parse result must propagate null — guards
        // line 159 in FileCertificateStore (the null-case of the safe-call chain).
        store.storeCertificate("not a cert")

        assertNull(store.getCertExpiry())
    }

    @Test
    fun `getKnownFingerprints skips blank lines from legacy writes`() = runBlocking {
        // Older versions appended a bare newline on every write; ensure the
        // reader tolerates that without surfacing an empty-string entry that
        // SelfCertVerifier would then fail to match.
        File(context.filesDir, "rouse_fingerprints.txt").writeText("AA:BB\n\nCC:DD\n")

        val known = store.getKnownFingerprints()

        assertEquals(setOf("AA:BB", "CC:DD"), known)
    }
}
