package com.rousecontext.work

import com.rousecontext.api.CrashReporter
import com.rousecontext.tunnel.TunnelError
import com.rousecontext.tunnel.TunnelState
import java.io.IOException
import java.net.ConnectException
import javax.net.ssl.SSLHandshakeException
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Unit-level companion to [TunnelBoundaryFailureReportingTest]: the same
 * three-way policy, pinned per exception type and — crucially — pinned on the
 * *propagation* half of the cancellation rule, which the service-level test
 * cannot observe.
 *
 * `.claude/rules/coroutines.md`: "Respect cancellation." Not reporting a
 * `CancellationException` is only half the fix; a boundary that quietly
 * swallows one turns a cancelled operation into a normally-completed one.
 *
 * Robolectric because [reportTunnelFailure] calls `android.util.Log`.
 */
@RunWith(RobolectricTestRunner::class)
class TunnelFailureReportingTest {

    // -------------------------------------------------------- classification

    @Test
    fun `cancellation classifies as Cancellation`() {
        assertEquals(
            TunnelFailureKind.Cancellation,
            classifyTunnelFailure(CancellationException("scope shutting down"))
        )
    }

    @Test
    fun `an unhandled TLS state classifies as a defect`() {
        assertEquals(
            TunnelFailureKind.Defect,
            classifyTunnelFailure(TunnelError.UnhandledTlsState("wrap: BUFFER_OVERFLOW"))
        )
    }

    @Test
    fun `peer and transport errors classify as PeerOrTransport`() {
        val routine = listOf(
            TunnelError.TlsHandshakeFailed("peer aborted mid-handshake"),
            TunnelError.ConnectionFailed("relay unreachable"),
            TunnelError.WebSocketClosed("closed by remote"),
            TunnelError.StreamRefused(1u, "refused"),
            TunnelError.StreamReset(1u, "reset"),
            IOException("stream closed"),
            ConnectException("connection refused"),
            SSLHandshakeException("no cipher suites in common")
        )
        assertEquals(
            routine.map { TunnelFailureKind.PeerOrTransport },
            routine.map { classifyTunnelFailure(it) }
        )
    }

    @Test
    fun `anything unanticipated classifies as a defect`() {
        // The quiet set is a closed allowlist and the default is "loud". A fix
        // that stops reporting everything, and reports nothing, would be worse
        // than the bug it replaced (#642).
        val defects = listOf(
            IllegalStateException("No TLS certificate available for server accept"),
            TunnelError.ProtocolError("bad frame type"),
            TunnelError.InternalError("relay internal error"),
            TunnelError.CertificateError("keystore unreadable"),
            TunnelError.InvalidStateTransition(TunnelState.ACTIVE, TunnelState.CONNECTING),
            NullPointerException("npe"),
            RuntimeException("something nobody thought about")
        )
        assertEquals(
            defects.map { TunnelFailureKind.Defect },
            defects.map { classifyTunnelFailure(it) }
        )
    }

    // ---------------------------------------------------------- the policy

    @Test
    fun `cancellation propagates out of the boundary rather than being swallowed`() {
        val reporter = RecordingCrashReporter()
        val cancellation = CancellationException("tunnel scope shutting down")

        val thrown = assertThrows(CancellationException::class.java) {
            reporter.reportTunnelFailure("tag", "handleStream failed", cancellation)
        }

        assertEquals(
            "The original CancellationException must propagate unchanged so the " +
                "coroutine completes as cancelled.",
            cancellation,
            thrown
        )
        assertEquals(emptyList<Throwable>(), reporter.reported)
    }

    @Test
    fun `a defect is reported and a peer event is not`() {
        val reporter = RecordingCrashReporter()
        val defect = TunnelError.UnhandledTlsState("wrap: BUFFER_UNDERFLOW")

        reporter.reportTunnelFailure("tag", "handleStream failed", defect)
        reporter.reportTunnelFailure(
            "tag",
            "handleStream failed",
            TunnelError.TlsHandshakeFailed("peer aborted")
        )
        reporter.reportTunnelFailure("tag", "handleStream failed", IOException("closed"))

        assertEquals(listOf<Throwable>(defect), reporter.reported)
    }

    private class RecordingCrashReporter : CrashReporter {
        val reported = mutableListOf<Throwable>()
        override fun logCaughtException(throwable: Throwable) {
            reported += throwable
        }
        override fun log(message: String) = Unit
        override fun setCollectionEnabled(enabled: Boolean) = Unit
    }
}
