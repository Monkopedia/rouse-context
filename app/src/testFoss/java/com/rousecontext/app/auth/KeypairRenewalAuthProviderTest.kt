package com.rousecontext.app.auth

import com.rousecontext.work.DeviceKeystoreSigner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Pins both directions of [KeypairRenewalAuthProvider]'s broad catches (issue #670) —
 * the `foss` counterpart of `FirebaseRenewalAuthProviderTest`.
 *
 * Reachability differs from the `google` side and the comments at the guards say so.
 * Neither `try` in this class contains a suspension point: [DeviceKeystoreSigner.sign] is
 * a plain `fun`, and `KeypairAuth.randomNonce` / `canonicalMessage` /
 * `System.currentTimeMillis` are all non-suspend. A `suspend` function whose `try` has no
 * suspension point never takes delivery of cooperative cancellation, so these guards are
 * defence in depth rather than live-bug fixes. They still matter, because
 * `RenewalAuthProvider` declares both methods `suspend`: a signer that suspends, or a
 * `withContext` added around the signing call, would silently turn the broad catch into a
 * swallow under `CertRenewalWorker` (a `CoroutineWorker`).
 *
 * The cancellation cases and the `IllegalStateException` cases are a discriminating PAIR.
 * `CancellationException` extends `IllegalStateException` on the JVM, so a guard
 * mis-written as `catch (e: IllegalStateException) { throw e }` passes every cancellation
 * case here while wrongly rethrowing genuine Keystore failures. The ISE cases are the half
 * that catches that widening — do not weaken them to `RuntimeException`.
 *
 * Robolectric only so `android.util.Log` resolves to no-ops.
 */
@RunWith(RobolectricTestRunner::class)
class KeypairRenewalAuthProviderTest {

    @Test
    fun `signCsr returns base64 signature on success`() = runBlocking {
        val provider = KeypairRenewalAuthProvider(FakeSigner { "mtls-sig-b64" })

        assertEquals("mtls-sig-b64", provider.signCsr(CSR_DER))
    }

    @Test
    fun `signCsr returns null when Keystore signing throws`() = runBlocking {
        val provider = KeypairRenewalAuthProvider(
            FakeSigner { throw IllegalStateException("keystore boom") }
        )

        assertNull(provider.signCsr(CSR_DER))
    }

    @Test
    fun `signCsr rethrows cancellation instead of deferring renewal`() = runBlocking {
        val provider = KeypairRenewalAuthProvider(
            FakeSigner { throw CancellationException("worker stopped") }
        )

        val thrown: Throwable? = try {
            provider.signCsr(CSR_DER)
            null
        } catch (e: CancellationException) {
            e
        }

        assertTrue(
            "cancellation must propagate out of signCsr, but it returned null " +
                "(swallowed by the broad catch)",
            thrown is CancellationException
        )
    }

    @Test
    fun `acquireKeypairRenewalCredentials returns signed CSR and proof on success`() = runBlocking {
        val provider = KeypairRenewalAuthProvider(FakeSigner { "sig" })

        val creds = provider.acquireKeypairRenewalCredentials(CSR_DER)

        assertNotNull(creds)
        assertEquals("sig", creds?.csrSignature)
        assertEquals("sig", creds?.proof?.signature)
    }

    @Test
    fun `acquireKeypairRenewalCredentials returns null when Keystore signing throws`() =
        runBlocking {
            val provider = KeypairRenewalAuthProvider(
                FakeSigner { throw IllegalStateException("keystore boom") }
            )

            assertNull(provider.acquireKeypairRenewalCredentials(CSR_DER))
        }

    @Test
    fun `acquireKeypairRenewalCredentials rethrows cancellation instead of deferring`() =
        runBlocking {
            val provider = KeypairRenewalAuthProvider(
                FakeSigner { throw CancellationException("worker stopped") }
            )

            val thrown: Throwable? = try {
                provider.acquireKeypairRenewalCredentials(CSR_DER)
                null
            } catch (e: CancellationException) {
                e
            }

            assertTrue(
                "cancellation must propagate out of acquireKeypairRenewalCredentials, " +
                    "but it returned null (swallowed by the broad catch)",
                thrown is CancellationException
            )
        }

    private companion object {
        val CSR_DER = byteArrayOf(0x30, 0x02, 0xDE.toByte(), 0xAD.toByte())
    }
}

private class FakeSigner(private val op: (ByteArray) -> String) : DeviceKeystoreSigner {
    override fun sign(data: ByteArray): String = op(data)
}
