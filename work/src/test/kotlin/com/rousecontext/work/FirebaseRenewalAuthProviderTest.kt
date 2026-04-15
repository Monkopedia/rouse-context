package com.rousecontext.work

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Unit tests for [FirebaseRenewalAuthProvider] using injected fakes for both the
 * Firebase token source and the Keystore signer. Real FirebaseAuth + Keystore paths are
 * covered by the thin [DefaultFirebaseIdTokenSource] / [AndroidKeystoreSigner] classes.
 *
 * Robolectric is used only so `android.util.Log` calls resolve to no-ops (the provider logs
 * on each non-success path). No Firebase or Keystore infrastructure is exercised here.
 */
@RunWith(RobolectricTestRunner::class)
class FirebaseRenewalAuthProviderTest {

    @Test
    fun `returns credentials when token and signature succeed`() = runBlocking {
        val provider = FirebaseRenewalAuthProvider(
            signer = FakeSigner { "base64-sig" },
            tokenSource = { "id-token" }
        )

        val creds = provider.acquireFirebaseCredentials(CSR_DER)

        assertEquals(FirebaseCredentials("id-token", "base64-sig"), creds)
    }

    @Test
    fun `returns null when Firebase user is not signed in`() = runBlocking {
        val provider = FirebaseRenewalAuthProvider(
            signer = FakeSigner { error("signer must not be invoked when token is missing") },
            tokenSource = { null }
        )

        val creds = provider.acquireFirebaseCredentials(CSR_DER)

        assertNull(creds)
    }

    @Test
    fun `returns null when Firebase returns empty token`() = runBlocking {
        val provider = FirebaseRenewalAuthProvider(
            signer = FakeSigner { error("signer must not be invoked when token is empty") },
            tokenSource = { "" }
        )

        val creds = provider.acquireFirebaseCredentials(CSR_DER)

        assertNull(creds)
    }

    @Test
    fun `returns null when token fetch throws`() = runBlocking {
        val provider = FirebaseRenewalAuthProvider(
            signer = FakeSigner { error("signer must not be invoked when token fetch fails") },
            tokenSource = { throw IllegalStateException("firebase boom") }
        )

        val creds = provider.acquireFirebaseCredentials(CSR_DER)

        assertNull(creds)
    }

    @Test
    fun `returns null when Keystore signing fails`() = runBlocking {
        val provider = FirebaseRenewalAuthProvider(
            signer = FakeSigner { throw IllegalStateException("keystore boom") },
            tokenSource = { "id-token" }
        )

        val creds = provider.acquireFirebaseCredentials(CSR_DER)

        assertNull(creds)
    }

    @Test
    fun `signCsr returns base64 signature on success`() = runBlocking {
        val provider = FirebaseRenewalAuthProvider(
            signer = FakeSigner { "mtls-sig-b64" },
            tokenSource = { error("token source must not be consulted for signCsr") }
        )

        assertEquals("mtls-sig-b64", provider.signCsr(CSR_DER))
    }

    @Test
    fun `signCsr returns null when Keystore signing throws`() = runBlocking {
        val provider = FirebaseRenewalAuthProvider(
            signer = FakeSigner { throw IllegalStateException("keystore boom") },
            tokenSource = { error("token source must not be consulted for signCsr") }
        )

        assertNull(provider.signCsr(CSR_DER))
    }

    @Test
    fun `passes CSR DER bytes to the signer unchanged`() = runBlocking {
        var observed: ByteArray? = null
        val provider = FirebaseRenewalAuthProvider(
            signer = FakeSigner { bytes ->
                observed = bytes
                "sig"
            },
            tokenSource = { "tok" }
        )

        provider.acquireFirebaseCredentials(CSR_DER)

        assertEquals(CSR_DER.toList(), observed?.toList())
    }

    private companion object {
        val CSR_DER = byteArrayOf(0x30, 0x02, 0xDE.toByte(), 0xAD.toByte())
    }
}

private class FakeSigner(private val op: (ByteArray) -> String) : DeviceKeystoreSigner {
    override fun sign(data: ByteArray): String = op(data)
}
