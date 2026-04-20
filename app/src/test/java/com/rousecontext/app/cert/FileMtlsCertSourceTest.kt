package com.rousecontext.app.cert

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.rousecontext.tunnel.DeviceKeyManager
import java.io.File
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Unit tests for [FileMtlsCertSource].
 *
 * The source pairs PEM cert files under `filesDir` with the hardware-backed
 * private key from [DeviceKeyManager]. Tests exercise every observable state
 * the source can be in during onboarding / renewal:
 *
 * - no client cert written yet (pre-/register/certs)
 * - client cert only (CA-less dev relays)
 * - client cert + relay CA (full production chain)
 * - a [DeviceKeyManager] that throws (device keystore was wiped, StrongBox
 *   is gone, etc.)
 *
 * The production implementation rebuilds the identity on every call, so these
 * tests also act as a regression guard against accidental caching.
 */
@RunWith(RobolectricTestRunner::class)
class FileMtlsCertSourceTest {

    private lateinit var context: Context
    private lateinit var keyPair: KeyPair
    private lateinit var deviceKeyManager: DeviceKeyManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // Wipe filesDir so each test starts from a clean slate — unrelated
        // tests in the same Robolectric run share the same filesDir per
        // Application context.
        context.filesDir.listFiles()?.forEach { it.delete() }

        // A software EC keypair stands in for the hardware-backed key the
        // production [AndroidKeystoreDeviceKeyManager] would mint. The source
        // never introspects the private key beyond passing its reference into
        // [com.rousecontext.tunnel.MtlsIdentity], so software-backed is faithful
        // enough for the behaviour under test.
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256r1"))
        keyPair = kpg.generateKeyPair()
        deviceKeyManager = object : DeviceKeyManager {
            override fun getOrCreateKeyPair(): KeyPair = keyPair
        }
    }

    @After
    fun tearDown() {
        context.filesDir.listFiles()?.forEach { it.delete() }
    }

    @Test
    fun `current returns null when client cert file is absent`() {
        // Pre-onboarding: /register/certs has not yet written anything. The
        // tunnel layer asks for an identity and must get null (LazyMtlsKeyManager
        // interprets that as "fall back to plain HTTP / defer").
        val source = FileMtlsCertSource(context, deviceKeyManager)

        assertNull(source.current())
    }

    @Test
    fun `current returns null when client cert file is empty (no BEGIN CERTIFICATE block)`() {
        // If the file exists but parses to no certs (truncated write, bogus
        // content written by a test harness, etc.), the source must fall back
        // to null rather than crash on firstOrNull() of an empty list.
        writeFile(CLIENT_CERT_FILE, "")

        val source = FileMtlsCertSource(context, deviceKeyManager)

        assertNull(source.current())
    }

    @Test
    fun `current returns identity with leaf-only chain when no relay CA`() {
        val leaf = CertTestUtil.generateSelfSignedCert("client.rousecontext.com")
        writeFile(CLIENT_CERT_FILE, CertTestUtil.derToPem(leaf.encoded))

        val source = FileMtlsCertSource(context, deviceKeyManager)
        val identity = source.current()

        assertNotNull(identity)
        assertEquals(1, identity!!.certChain.size)
        assertEquals(leaf, identity.certChain[0])
        assertSame(keyPair.private, identity.privateKey)
    }

    @Test
    fun `current returns identity with leaf+CA chain when relay CA is present`() {
        val leaf = CertTestUtil.generateSelfSignedCert("client.rousecontext.com")
        val ca = CertTestUtil.generateSelfSignedCert("relay-ca.rousecontext.com")
        writeFile(CLIENT_CERT_FILE, CertTestUtil.derToPem(leaf.encoded))
        writeFile(RELAY_CA_FILE, CertTestUtil.derToPem(ca.encoded))

        val source = FileMtlsCertSource(context, deviceKeyManager)
        val identity = source.current()

        assertNotNull(identity)
        // Production relays require leaf-first ordering on the wire; JSSE
        // would otherwise reject the presentation as "chain not terminated".
        assertEquals(
            "Chain MUST be leaf-first, followed by issuer",
            listOf(leaf, ca),
            identity!!.certChain
        )
    }

    @Test
    fun `current falls back to leaf-only when relay CA file is empty`() {
        // Stray/truncated relay-ca file must not corrupt the identity — the
        // production LazyMtlsKeyManager will present the leaf and rely on the
        // relay's configured trust store to accept it. This matches the
        // "CA-less dev relay" shape.
        val leaf = CertTestUtil.generateSelfSignedCert("client.rousecontext.com")
        writeFile(CLIENT_CERT_FILE, CertTestUtil.derToPem(leaf.encoded))
        writeFile(RELAY_CA_FILE, "")

        val source = FileMtlsCertSource(context, deviceKeyManager)
        val identity = source.current()

        assertNotNull(identity)
        assertEquals(listOf(leaf), identity!!.certChain)
    }

    @Test
    fun `current returns null when DeviceKeyManager throws`() {
        // A wiped Keystore alias / revoked StrongBox entry surfaces as an
        // exception from getOrCreateKeyPair(). The source catches it and
        // returns null, preferring "no mTLS this attempt" over a crash.
        val leaf = CertTestUtil.generateSelfSignedCert("client.rousecontext.com")
        writeFile(CLIENT_CERT_FILE, CertTestUtil.derToPem(leaf.encoded))
        val throwingKeyManager = object : DeviceKeyManager {
            override fun getOrCreateKeyPair(): KeyPair = error("keystore missing alias")
        }

        val source = FileMtlsCertSource(context, throwingKeyManager)

        assertNull(source.current())
    }

    @Test
    fun `current reflects disk state on every call (no caching)`() {
        // Onboarding / renewal write the client cert asynchronously; a caller
        // that polls current() must see the transition from null -> Identity
        // without having to reconstruct the source. The production
        // implementation reads the file on every call; this test enforces it.
        val source = FileMtlsCertSource(context, deviceKeyManager)
        assertNull(source.current())

        val leaf = CertTestUtil.generateSelfSignedCert("client.rousecontext.com")
        writeFile(CLIENT_CERT_FILE, CertTestUtil.derToPem(leaf.encoded))

        val identity = source.current()
        assertNotNull(identity)
        assertEquals(listOf(leaf), identity!!.certChain)
    }

    @Test
    fun `current picks the first cert when client cert file contains multiple blocks`() {
        // Defensive: onboarding normally writes exactly the leaf, but a future
        // relay version might write a leaf + issuer in one file. Only the first
        // PEM block (the leaf) is used as the mTLS presentation identity.
        val leaf = CertTestUtil.generateSelfSignedCert("client-leaf.rousecontext.com")
        val second = CertTestUtil.generateSelfSignedCert("client-second.rousecontext.com")
        writeFile(
            CLIENT_CERT_FILE,
            CertTestUtil.chainToPem(listOf(leaf, second))
        )

        val source = FileMtlsCertSource(context, deviceKeyManager)
        val identity = source.current()

        assertNotNull(identity)
        assertTrue(
            "Leaf cert must be the first in the returned chain",
            identity!!.certChain.first() == leaf
        )
    }

    private fun writeFile(name: String, content: String) {
        File(context.filesDir, name).writeText(content)
    }

    private companion object {
        const val CLIENT_CERT_FILE = "rouse_client_cert.pem"
        const val RELAY_CA_FILE = "rouse_relay_ca.pem"
    }
}
