package com.rousecontext.tunnel

import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking

/**
 * What [SelfCertVerifier] does with a **cancellation** raised by one of the
 * three suspend store calls it guards (#646).
 *
 * All three catches sit around `suspend` members of [CertificateStore], so a
 * scope teardown mid-verify came back as [SecurityCheckResult.Warning] -- the
 * same "security finding about a check that never ran" shape as
 * [CtLogMonitorCancellationTest].
 *
 * The third one is the sharpest: [SelfCertVerifier] backfills a fingerprint and
 * *then* writes the one-shot bootstrap marker, deliberately in that order, so a
 * failed fingerprint write does not claim bootstrap completed (issue #210).
 * Swallowing cancellation between the two turns a teardown into exactly the
 * half-written state that ordering exists to prevent, and reports it as a
 * Warning rather than letting the caller retry.
 *
 * As in [CtLogMonitorCancellationTest], no test cancels the surrounding scope:
 * the collaborator raises cancellation directly so the assertion is about the
 * code under test and not about the test's own teardown.
 */
class SelfCertVerifierCancellationTest {

    @Test
    fun `cancellation from getKnownFingerprints propagates instead of becoming a Warning`() =
        runBlocking {
            val verifier = SelfCertVerifier(
                FingerprintStore(onGetKnown = CancellationException("worker scope torn down"))
            )

            val thrown = assertFailsWith<Throwable> { verifier.verify(listOf(LEAF)) }

            assertTrue(
                thrown is CancellationException,
                "a cancelled verify must propagate cancellation, got " +
                    "${thrown.javaClass.name}: ${thrown.message}"
            )
        }

    @Test
    fun `an IO failure from getKnownFingerprints is still reported as a Warning`(): Unit =
        runBlocking {
            val verifier = SelfCertVerifier(
                FingerprintStore(onGetKnown = IOException("Storage unavailable"))
            )

            val result = verifier.verify(listOf(LEAF))

            assertIs<SecurityCheckResult.Warning>(result)
            assertTrue(
                result.reason.startsWith("Could not retrieve known fingerprints:"),
                "the ordinary failure path must be unchanged, got: ${result.reason}"
            )
        }

    @Test
    fun `cancellation from the bootstrap marker read propagates instead of a Warning`() =
        runBlocking {
            val verifier = SelfCertVerifier(
                FingerprintStore(onMarkerRead = CancellationException("worker scope torn down"))
            )

            val thrown = assertFailsWith<Throwable> { verifier.verify(listOf(LEAF)) }

            assertTrue(
                thrown is CancellationException,
                "a cancelled marker read must propagate cancellation, got " +
                    "${thrown.javaClass.name}: ${thrown.message}"
            )
        }

    @Test
    fun `an IO failure on the bootstrap marker read is still reported as a Warning`(): Unit =
        runBlocking {
            val verifier = SelfCertVerifier(
                FingerprintStore(onMarkerRead = IOException("Storage unavailable"))
            )

            val result = verifier.verify(listOf(LEAF))

            assertIs<SecurityCheckResult.Warning>(result)
            assertTrue(
                result.reason.startsWith("Could not check fingerprint bootstrap marker:"),
                "the ordinary failure path must be unchanged, got: ${result.reason}"
            )
        }

    @Test
    fun `cancellation during the fingerprint backfill propagates instead of a Warning`() =
        runBlocking {
            // Issue #210 ordering: the fingerprint is stored, then the marker is
            // written. A cancellation between them must not be reported as a
            // completed-but-degraded check.
            val verifier = SelfCertVerifier(
                FingerprintStore(onMarkerWrite = CancellationException("worker scope torn down"))
            )

            val thrown = assertFailsWith<Throwable> { verifier.verify(listOf(LEAF)) }

            assertTrue(
                thrown is CancellationException,
                "a cancelled backfill must propagate cancellation, got " +
                    "${thrown.javaClass.name}: ${thrown.message}"
            )
        }

    @Test
    fun `an IO failure during the fingerprint backfill is still reported as a Warning`(): Unit =
        runBlocking {
            val verifier = SelfCertVerifier(
                FingerprintStore(onMarkerWrite = IOException("Storage unavailable"))
            )

            val result = verifier.verify(listOf(LEAF))

            assertIs<SecurityCheckResult.Warning>(result)
            assertTrue(
                result.reason.startsWith("Could not backfill missing fingerprint:"),
                "the ordinary failure path must be unchanged, got: ${result.reason}"
            )
        }

    /**
     * Store with an empty fingerprint set and no bootstrap marker -- the state
     * that drives [SelfCertVerifier] through all three guarded calls -- with an
     * injectable failure at each one.
     */
    private class FingerprintStore(
        private val onGetKnown: Throwable? = null,
        private val onMarkerRead: Throwable? = null,
        private val onMarkerWrite: Throwable? = null
    ) : CertificateStore by SecurityCertificateStore() {
        override suspend fun getKnownFingerprints(): Set<String> {
            onGetKnown?.let { throw it }
            return emptySet()
        }

        override suspend fun hasFingerprintBootstrapMarker(): Boolean {
            onMarkerRead?.let { throw it }
            return false
        }

        override suspend fun storeFingerprint(fingerprint: String) = Unit

        override suspend fun writeFingerprintBootstrapMarker() {
            onMarkerWrite?.let { throw it }
        }
    }

    private companion object {
        /** Any non-empty DER stand-in; only its SHA-256 is ever taken. */
        val LEAF = byteArrayOf(0x30, 0x03, 0x02, 0x01, 0x00)
    }
}
