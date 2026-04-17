package com.rousecontext.app.cert

import java.security.KeyStore
import java.security.Signature
import java.security.interfaces.ECPublicKey
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Robolectric-level tests for [AndroidKeystoreDeviceKeyManager].
 *
 * Robolectric's AndroidKeyStore provider does not expose a real TEE/StrongBox, so these
 * tests cannot assert `isInsideSecureHardware == true` in the usual sense. They do cover
 * the logic that must work identically regardless of backend:
 * - first call mints an EC P-256 keypair under [AndroidKeystoreDeviceKeyManager.KEY_ALIAS]
 * - subsequent calls return the same keypair (idempotency guarantee)
 * - the returned private key can produce SHA256withECDSA signatures that verify under
 *   the returned public key -- i.e. the `KeyPair` is self-consistent
 *
 * The StrongBox-vs-TEE fallback path is exercised by hand on real hardware (see adolin
 * smoke-test instructions in issue #200). Robolectric does not simulate the
 * StrongBoxUnavailableException code path faithfully.
 */
@RunWith(RobolectricTestRunner::class)
class AndroidKeystoreDeviceKeyManagerTest {

    private lateinit var manager: AndroidKeystoreDeviceKeyManager

    @Before
    fun setUp() {
        // Robolectric ships without the AndroidKeyStore provider by default. When the
        // provider is missing, every Keystore-backed test is a no-op rather than a
        // useful assertion; skip gracefully so CI does not red-flag legitimate envs.
        assumeTrue(
            "AndroidKeyStore provider not available in this Robolectric runtime",
            isAndroidKeyStoreAvailable()
        )
        // Wipe our alias so each test starts from a clean slate.
        clearAlias()
        manager = AndroidKeystoreDeviceKeyManager()
    }

    private fun isAndroidKeyStoreAvailable(): Boolean = try {
        KeyStore.getInstance(AndroidKeystoreDeviceKeyManager.ANDROID_KEYSTORE).load(null)
        true
    } catch (_: Throwable) {
        false
    }

    @After
    fun tearDown() {
        if (isAndroidKeyStoreAvailable()) {
            clearAlias()
        }
    }

    @Test
    fun `first call generates a new EC P-256 keypair under the device alias`() {
        val keyStore = androidKeyStore()
        assertTrue(
            "Precondition: alias must not exist before first call",
            !keyStore.containsAlias(AndroidKeystoreDeviceKeyManager.KEY_ALIAS)
        )

        val keyPair = manager.getOrCreateKeyPair()

        assertNotNull("public key must be non-null", keyPair.public)
        assertNotNull("private key must be non-null", keyPair.private)
        assertTrue(
            "Alias must be provisioned after getOrCreateKeyPair",
            androidKeyStore().containsAlias(AndroidKeystoreDeviceKeyManager.KEY_ALIAS)
        )
        assertTrue(
            "Generated public key must be EC",
            keyPair.public is ECPublicKey
        )
        val ecPub = keyPair.public as ECPublicKey
        assertEquals(
            "Key must be P-256 (field size 256 bits)",
            P256_FIELD_SIZE_BITS,
            ecPub.params.curve.field.fieldSize
        )
    }

    @Test
    fun `second call returns the same keypair as the first`() {
        val first = manager.getOrCreateKeyPair()
        val second = manager.getOrCreateKeyPair()

        // Issue #199 / #200 contract: the registration keypair is reused on every
        // renewal so the relay's pinned public key stays valid. Byte-for-byte DER
        // equality is the strictest readable check.
        assertTrue(
            "Second call must return the same public key DER",
            first.public.encoded.contentEquals(second.public.encoded)
        )
    }

    @Test
    fun `returned keypair produces a valid SHA256withECDSA signature`() {
        val keyPair = manager.getOrCreateKeyPair()
        val payload = "csr-der-bytes-stand-in".toByteArray()

        val signer = Signature.getInstance("SHA256withECDSA")
        signer.initSign(keyPair.private)
        signer.update(payload)
        val signatureBytes = signer.sign()

        val verifier = Signature.getInstance("SHA256withECDSA")
        verifier.initVerify(keyPair.public)
        verifier.update(payload)
        assertTrue(
            "Signature produced by the keypair's private half must verify under " +
                "the public half",
            verifier.verify(signatureBytes)
        )
    }

    private fun androidKeyStore(): KeyStore {
        val keyStore = KeyStore.getInstance(AndroidKeystoreDeviceKeyManager.ANDROID_KEYSTORE)
        keyStore.load(null)
        return keyStore
    }

    private fun clearAlias() {
        val keyStore = androidKeyStore()
        if (keyStore.containsAlias(AndroidKeystoreDeviceKeyManager.KEY_ALIAS)) {
            keyStore.deleteEntry(AndroidKeystoreDeviceKeyManager.KEY_ALIAS)
        }
    }

    private companion object {
        const val P256_FIELD_SIZE_BITS = 256
    }
}
