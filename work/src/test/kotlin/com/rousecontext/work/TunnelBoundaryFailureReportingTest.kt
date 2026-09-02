package com.rousecontext.work

import android.app.Notification
import android.content.pm.ServiceInfo
import android.os.Looper
import android.util.Log
import com.rousecontext.api.CrashReporter
import com.rousecontext.bridge.SessionHandler
import com.rousecontext.mcp.core.ProviderRegistry
import com.rousecontext.notifications.ForegroundNotifier
import com.rousecontext.notifications.NotificationChannels
import com.rousecontext.notifications.SessionSummaryNotifier
import com.rousecontext.tunnel.MuxStream
import com.rousecontext.tunnel.TunnelClient
import com.rousecontext.tunnel.TunnelError
import com.rousecontext.tunnel.TunnelState
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import java.io.IOException
import kotlin.time.Duration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.core.qualifier.named
import org.koin.dsl.module
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowLog

/**
 * The service-boundary end of the discrimination #616/#626/#630/#639/#643/#647
 * built *inside* the TLS layer.
 *
 * `TunnelForegroundService.collectIncomingSessions` wraps every
 * `sessionHandler.handleStream(stream)` call. Whatever that wrapper does is the
 * last word on whether an exception is heard: the TLS layer can classify all it
 * likes, but if the boundary funnels every `Exception` into
 * `CrashReporter.logCaughtException` then a peer hanging up mid-handshake and a
 * genuine `UnhandledTlsState` defect are indistinguishable in the crash channel
 * — and noise in that channel is how a real defect stops being read (#642).
 *
 * Three categories, three outcomes. All three are asserted here, because a fix
 * that gets it to *two* is the likely failure mode in both directions: report
 * everything (the bug) or report nothing (worse than the bug).
 *
 * | throwable                        | crash report | Log.e |
 * |----------------------------------|--------------|-------|
 * | `TunnelError.UnhandledTlsState`  | yes          | yes   |
 * | `TunnelError.TlsHandshakeFailed` | no           | no    |
 * | `IOException`                    | no           | no    |
 * | `CancellationException`          | no           | no    |
 */
@RunWith(RobolectricTestRunner::class)
class TunnelBoundaryFailureReportingTest {

    private lateinit var tunnelClient: FakeTunnelClient
    private lateinit var sessionHandler: SessionHandler
    private lateinit var crashReporter: RecordingCrashReporter

    @Before
    fun setUp() {
        ShadowLog.clear()
        tunnelClient = FakeTunnelClient()
        sessionHandler = mockk(relaxed = true)
        crashReporter = RecordingCrashReporter()

        mockkObject(ForegroundNotifier)
        every { ForegroundNotifier.build(any(), any()) } returns stubNotification()
        mockkObject(NotificationChannels)
        every { NotificationChannels.createAll(any()) } returns Unit

        runCatching { stopKoin() }
        startKoin {
            modules(
                module {
                    single<TunnelClient> { tunnelClient }
                    single { sessionHandler }
                    single { WakelockManager(FakeWakeLockHandle()) }
                    single {
                        IdleTimeoutManager(
                            timeoutProvider = { _ -> Long.MAX_VALUE },
                            onTimeout = { }
                        )
                    }
                    single<ProviderRegistry> {
                        mockk {
                            every { enabledPaths() } returns setOf("health")
                            every { providerForPath(any()) } returns null
                            coEvery { awaitReady() } returns Unit
                            every { awaitReadyBlocking(any()) } returns true
                        }
                    }
                    single { mockk<SessionSummaryNotifier>(relaxed = true) }
                    single { mockk<SecurityCheckPreferences>(relaxed = true) }
                    single<String>(named("relayUrl")) { "wss://test.rousecontext.com" }
                    single<CrashReporter> { crashReporter }
                    single<ConnectPushReporter> { ConnectPushReporter { } }
                    single<FgsTypeSelector> {
                        FgsTypeSelector { ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC }
                    }
                }
            )
        }
    }

    @After
    fun tearDown() {
        stopKoin()
        unmockkObject(ForegroundNotifier)
        unmockkObject(NotificationChannels)
    }

    // ---------------------------------------------------------- the defect

    @Test
    fun `an UnhandledTlsState defect out of handleStream still reaches the crash reporter`() {
        // This is the case the crash channel exists for, and the one a careless
        // "stop reporting so much" fix silently deletes. It must be green both
        // before and after the #642 change.
        val defect = TunnelError.UnhandledTlsState("wrap returned BUFFER_OVERFLOW")

        deliverStreamFailingWith(defect)

        assertEquals(
            "A TunnelError.UnhandledTlsState escaping handleStream is a defect in our " +
                "own TLS layer, not anything the peer did. It must stay loud.",
            listOf<Throwable>(defect),
            crashReporter.reported
        )
        assertTrue(
            "A defect must also be logged at ERROR. Saw: ${errorLogs()}",
            errorLogs().isNotEmpty()
        )
    }

    // ------------------------------------------------- ordinary peer events

    @Test
    fun `a peer aborting mid-handshake is not crash-reported`() {
        // A client that dials the relay and walks away mid-TLS (cancelled MCP
        // client, port scanner, health probe, flaky mobile network) is routine
        // peer behaviour. One non-fatal per occurrence is noise, and noise here
        // is how a real UnhandledTlsState stops being read (#642).
        deliverStreamFailingWith(
            TunnelError.TlsHandshakeFailed("peer sent close_notify mid-handshake")
        )

        assertEquals(
            "TunnelError.TlsHandshakeFailed is the peer hanging up, not our defect.",
            emptyList<Throwable>(),
            crashReporter.reported
        )
        assertEquals(
            "A routine peer abort must not be logged at ERROR either.",
            emptyList<String>(),
            errorLogs()
        )
    }

    @Test
    fun `a plain IOException from the peer is not crash-reported`() {
        // Plain IOException is what an ordinary disconnect looks like all the
        // way down -- SuspendTlsSession.write throws exactly that for a dead
        // peer, which is precisely why TunnelError.UnhandledTlsState had to be
        // its own type (see its kdoc).
        deliverStreamFailingWith(IOException("stream closed"))

        assertEquals(
            "A bare IOException cannot be told apart from a routine hang-up, so it " +
                "is treated as one.",
            emptyList<Throwable>(),
            crashReporter.reported
        )
        assertEquals(
            "A routine peer disconnect must not be logged at ERROR.",
            emptyList<String>(),
            errorLogs()
        )
    }

    // ------------------------------------------------ collector survival

    @Test
    fun `an ordinary session failure does not stop the collector`() {
        // Migrated from `:core:bridge`'s TunnelSessionManagerDefectVisibilityTest
        // when that class was deleted (#671). It was pinned there against
        // TunnelSessionManager -- a collector nothing ever constructed -- so
        // the property read as covered while the collector that actually runs
        // had no test for it at all. This is that test, on the shipped path.
        //
        // The tests above all deliver exactly one stream, so every one of them
        // stays green on a boundary that handles its first failure and then
        // stops collecting forever. Only a second stream can tell the
        // difference, which is why this one emits two.
        val handled = mutableListOf<UInt>()
        coEvery { sessionHandler.handleStream(any()) } coAnswers {
            val stream = firstArg<MuxStream>()
            handled += stream.id
            if (stream.id == FIRST_STREAM_ID) {
                throw IOException("Connection reset by peer")
            }
        }

        val controller = Robolectric.buildService(TunnelForegroundService::class.java)
        controller.create()
        drainMain()

        tunnelClient.incoming.tryEmit(StubMuxStream(FIRST_STREAM_ID))
        drainMain()
        drainMain()

        tunnelClient.incoming.tryEmit(StubMuxStream(SECOND_STREAM_ID))
        drainMain()
        drainMain()

        assertEquals(
            "The collector stopped after one ordinary session failure. A peer " +
                "hanging up is normal and frequent; if it ends collection then " +
                "every later stream is dropped in silence with the service " +
                "still sitting in the foreground looking connected.",
            listOf(FIRST_STREAM_ID, SECOND_STREAM_ID),
            handled
        )
        assertEquals(
            "Surviving the failure must not mean reporting it: a routine " +
                "disconnect stays out of the crash channel (#642).",
            emptyList<Throwable>(),
            crashReporter.reported
        )
    }

    // ------------------------------------------------------- cancellation

    @Test
    fun `cancellation is neither crash-reported nor logged as an error`() {
        // #647 made cancellation arrive *recognisable* out of accept/connect.
        // This is the boundary that has to stop filing it as a failure: the
        // scope shutting down is not a failure at all. CancellationException
        // extends IllegalStateException extends ... extends Exception, so the
        // untyped catch swallowed it straight into a crash report.
        deliverStreamFailingWith(CancellationException("tunnel scope shutting down"))

        assertEquals(
            "Cancellation is the scope shutting down, not a failure. It must never " +
                "reach the crash channel.",
            emptyList<Throwable>(),
            crashReporter.reported
        )
        assertEquals(
            "Cancellation must never be logged as an error either.",
            emptyList<String>(),
            errorLogs()
        )
    }

    // ------------------------------------------------------------ helpers

    /**
     * Starts the service, pushes one incoming mux stream at it, and arranges for
     * [failure] to escape `sessionHandler.handleStream`.
     */
    private fun deliverStreamFailingWith(failure: Throwable) {
        coEvery { sessionHandler.handleStream(any()) } throws failure

        val controller = Robolectric.buildService(TunnelForegroundService::class.java)
        controller.create()
        drainMain()

        tunnelClient.incoming.tryEmit(StubMuxStream())
        drainMain()
        drainMain()
    }

    /** ERROR-level log lines emitted by the service under test. */
    private fun errorLogs(): List<String> = ShadowLog.getLogs()
        .filter { it.type == Log.ERROR && it.tag == "TunnelForegroundService" }
        .map { it.msg }

    private fun drainMain() {
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun stubNotification(): Notification = Notification.Builder(
        androidx.test.core.app.ApplicationProvider.getApplicationContext(),
        "test_channel"
    )
        .setSmallIcon(android.R.drawable.ic_dialog_info)
        .build()

    private class RecordingCrashReporter : CrashReporter {
        val reported = mutableListOf<Throwable>()
        override fun logCaughtException(throwable: Throwable) {
            reported += throwable
        }
        override fun log(message: String) = Unit
        override fun setCollectionEnabled(enabled: Boolean) = Unit
    }

    private class StubMuxStream(override val id: UInt = 7u) : MuxStream {
        override val incoming: Flow<ByteArray> = emptyFlow()
        override val isClosed: Boolean = false
        override suspend fun send(data: ByteArray) = Unit
        override suspend fun close() = Unit
        override suspend fun read(): ByteArray = ByteArray(0)
    }

    private class FakeTunnelClient : TunnelClient {
        val stateFlow = MutableStateFlow(TunnelState.DISCONNECTED)
        val incoming = MutableSharedFlow<MuxStream>(extraBufferCapacity = 8)
        override val state: StateFlow<TunnelState> = stateFlow
        override val errors: SharedFlow<TunnelError> = MutableSharedFlow()
        override val incomingSessions: Flow<MuxStream> = incoming
        override suspend fun connect(url: String) {
            stateFlow.value = TunnelState.CONNECTED
        }
        override suspend fun disconnect() {
            stateFlow.value = TunnelState.DISCONNECTED
        }
        override suspend fun sendFcmToken(token: String) = Unit
        override suspend fun sendPushEndpoint(kind: String, value: String) = Unit
        override suspend fun healthCheck(timeout: Duration): Boolean = true
    }

    private class FakeWakeLockHandle : WakeLockHandle {
        override var isHeld: Boolean = false
        override fun acquire() {
            isHeld = true
        }
        override fun release() {
            isHeld = false
        }
    }

    private companion object {
        const val FIRST_STREAM_ID: UInt = 1u
        const val SECOND_STREAM_ID: UInt = 2u
    }
}
