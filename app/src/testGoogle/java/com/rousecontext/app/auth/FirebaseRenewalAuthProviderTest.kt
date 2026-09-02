package com.rousecontext.app.auth

import com.rousecontext.work.DeviceKeystoreSigner
import com.rousecontext.work.FirebaseCredentials
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Unit tests for [FirebaseRenewalAuthProvider] using injected fakes for both the
 * Firebase token source and the Keystore signer. Real FirebaseAuth + Keystore paths are
 * covered by the thin [DefaultFirebaseIdTokenSource] / `AndroidKeystoreSigner` classes.
 *
 * Robolectric is used only so `android.util.Log` calls resolve to no-ops (the provider logs
 * on each non-success path). No Firebase or Keystore infrastructure is exercised here.
 *
 * Lives in the `testGoogle` source set (issue #476): [FirebaseRenewalAuthProvider] is a
 * `google`-flavor class, so its test compiles only against the google variant.
 *
 * The cancellation tests and the `IllegalStateException` tests are a discriminating PAIR
 * (issue #670). `CancellationException` extends `IllegalStateException` on the JVM, so a
 * guard mis-written as `catch (e: IllegalStateException) { throw e }` passes every
 * cancellation test while wrongly rethrowing genuine signing/fetch failures. The
 * `returns null when token fetch throws` / `signCsr returns null when Keystore signing
 * throws` cases deliberately use `IllegalStateException` — not `RuntimeException` — so
 * they are the half that catches that widening. Do not weaken them to `RuntimeException`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
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

    /**
     * The live half of issue #670. `acquireFirebaseCredentials` runs under
     * `CertRenewalWorker`, a `CoroutineWorker` whose coroutine WorkManager cancels when it
     * stops the worker, and `tokenSource.fetch()` is a real suspension point
     * (`getIdToken(false).await()`), so a stopped worker takes delivery of cancellation
     * right inside the `try`. Before the fix that surfaced as "Failed to fetch Firebase ID
     * token" followed by the more misleading "No Firebase user / ID token available",
     * and execution continued past the point where it should have unwound.
     */
    @Test
    fun `cancelling the worker scope mid-fetch propagates instead of deferring renewal`() =
        runTest {
            val entered = CompletableDeferred<Unit>()
            val provider = FirebaseRenewalAuthProvider(
                signer = FakeSigner { error("signer must not be reached after cancellation") },
                tokenSource = {
                    entered.complete(Unit)
                    awaitCancellation()
                }
            )

            // null == acquireFirebaseCredentials returned normally, i.e. it swallowed the
            // cancellation and let the renewal flow continue past worker teardown.
            val escaped = CompletableDeferred<Throwable?>()
            val job = launch {
                try {
                    provider.acquireFirebaseCredentials(CSR_DER)
                    escaped.complete(null)
                } catch (e: CancellationException) {
                    escaped.complete(e)
                    throw e
                }
            }

            entered.await()
            job.cancel()
            job.join()

            assertTrue(
                "cancellation must propagate out of acquireFirebaseCredentials, but it " +
                    "returned normally (swallowed by the broad catch)",
                escaped.await() is CancellationException
            )
        }

    /**
     * Defence in depth (issue #670). `DeviceKeystoreSigner.sign` is NOT a suspend function,
     * so nothing inside this `try` is a suspension point and cooperative cancellation cannot
     * be delivered here today — unlike the fetch above. But `RenewalAuthProvider.signCsr` is
     * declared `suspend` in the interface, so a signer that suspends (or a `withContext`
     * added around this call) would silently convert the broad catch into a swallow, and
     * `signCsr` is invoked from `acquireFirebaseCredentials` after a real suspension point.
     * The guard is cheap and the pin keeps it in place.
     */
    @Test
    fun `signCsr rethrows cancellation instead of deferring renewal`() = runBlocking {
        val provider = FirebaseRenewalAuthProvider(
            signer = FakeSigner { throw CancellationException("worker stopped") },
            tokenSource = { error("token source must not be consulted for signCsr") }
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

    private companion object {
        val CSR_DER = byteArrayOf(0x30, 0x02, 0xDE.toByte(), 0xAD.toByte())
    }
}

private class FakeSigner(private val op: (ByteArray) -> String) : DeviceKeystoreSigner {
    override fun sign(data: ByteArray): String = op(data)
}
