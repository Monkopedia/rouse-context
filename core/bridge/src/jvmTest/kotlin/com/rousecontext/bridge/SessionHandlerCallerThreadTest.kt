package com.rousecontext.bridge

import com.rousecontext.tunnel.MuxDemux
import com.rousecontext.tunnel.MuxFrame
import com.rousecontext.tunnel.TlsAcceptor
import io.ktor.network.selector.SelectorManager
import java.net.ProtocolFamily
import java.net.ServerSocket
import java.nio.channels.DatagramChannel
import java.nio.channels.Pipe
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.channels.spi.AbstractSelector
import java.nio.channels.spi.SelectorProvider
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import javax.net.ssl.SSLContext
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Rule
import org.junit.rules.Timeout

/**
 * Regression gate for the 1.0.13 `NetworkOnMainThreadException` crash: #781
 * swapped a blocking `java.net.Socket` (connected under
 * `withContext(Dispatchers.IO)`) for a Ktor socket, whose `connect` runs
 * `SocketChannel.connect` synchronously on the CALLING thread. Production
 * calls [SessionHandler.handleStream] from `lifecycleScope` -- the Android
 * main thread -- so StrictMode killed every incoming session.
 *
 * The JVM has no StrictMode, so the test plays its part: [handleStream] is
 * called from a single-thread dispatcher standing in for the main thread, and
 * every blocking or network seam records the thread it ran on.
 *
 * The connect is observed through the [SelectorManager]'s provider. Ktor's
 * `tcpConnect` calls `provider.openSocketChannel()` and then
 * `SocketChannel.connect` in one stretch with no suspension point between
 * them (checked against ktor-network 3.4.2 bytecode), so the thread that opens
 * the channel is the thread that connects it.
 */
class SessionHandlerCallerThreadTest {

    @get:Rule
    val timeout: Timeout = Timeout.seconds(TEST_TIMEOUT_SECONDS)

    /** Compared by identity: coroutine debug mode rewrites thread names. */
    @Volatile
    private var fakeMainThread: Thread? = null
    private val fakeMain: ExecutorService = Executors.newSingleThreadExecutor {
        Thread(it, FAKE_MAIN).apply { isDaemon = true }.also { t -> fakeMainThread = t }
    }
    private val fakeMainDispatcher = fakeMain.asCoroutineDispatcher()

    /** Stand-in for the local MCP server: connections land in its backlog and sit. */
    private val silentMcpServer = ServerSocket(0)

    private val demux = MuxDemux().apply { onOutgoingFrame = {} }

    /** (operation, thread) for every blocking/network seam the session hit. */
    private val calls = ConcurrentLinkedQueue<Pair<String, Thread>>()

    @AfterTest
    fun tearDown() {
        silentMcpServer.close()
        fakeMain.shutdownNow()
    }

    @Test
    fun `no blocking or network work runs on the thread that calls handleStream`() = runBlocking {
        demux.handleFrame(MuxFrame.Open(STREAM_ID))
        val stream = demux.incomingStreams.first()
        val handler = SessionHandler(
            certProvider = RecordingCertProvider(),
            mcpSessionFactory = RecordingSessionFactory(silentMcpServer.localPort),
            idleTimeout = SHORT_IDLE,
            tlsAccept = { _, _ -> SilentTlsSession },
            selectorManager = RecordingSelectorManager(SelectorManager(Dispatchers.IO))
        )

        withContext(fakeMainDispatcher) {
            withTimeout(TIMEOUT_MS) { handler.handleStream(stream) }
        }

        val ops = calls.map { it.first }.toSet()
        // Positive control: a test that never reached the connect would pass
        // the thread check below vacuously.
        assertEquals(
            setOf(OP_CERT, OP_CREATE, OP_CONNECT, OP_STOP),
            ops,
            "every seam must have been exercised; saw $calls"
        )
        val onCaller = calls.filter { it.second === fakeMainThread }.map { it.first }
        assertTrue(
            onCaller.isEmpty(),
            "blocking/network work ran on the caller's (main) thread: $onCaller"
        )
    }

    private fun record(op: String) {
        calls.add(op to Thread.currentThread())
    }

    private inner class RecordingCertProvider : TlsCertProvider {
        override suspend fun serverSslContext(): SSLContext {
            // Production reads the cert store and the Android Keystore here.
            record(OP_CERT)
            return SSLContext.getDefault()
        }
    }

    private inner class RecordingSessionFactory(private val port: Int) : McpSessionFactory {
        override suspend fun create(): McpSessionHandle {
            record(OP_CREATE)
            return McpSessionHandle(
                port = port,
                internalToken = "test-token",
                stop = { record(OP_STOP) }
            )
        }
    }

    /** A real [SelectorManager] whose provider records the thread that opens sockets. */
    private inner class RecordingSelectorManager(private val delegate: SelectorManager) :
        SelectorManager by delegate {
        override val provider: SelectorProvider = RecordingProvider(delegate.provider)
    }

    private inner class RecordingProvider(private val real: SelectorProvider) :
        SelectorProvider() {
        override fun openSocketChannel(): SocketChannel {
            record(OP_CONNECT)
            return real.openSocketChannel()
        }

        override fun openDatagramChannel(): DatagramChannel = real.openDatagramChannel()

        override fun openDatagramChannel(family: ProtocolFamily): DatagramChannel =
            real.openDatagramChannel(family)

        override fun openPipe(): Pipe = real.openPipe()

        override fun openSelector(): AbstractSelector = real.openSelector()

        override fun openServerSocketChannel(): ServerSocketChannel = real.openServerSocketChannel()
    }

    /** A peer that never sends: the session ends on the idle bound. */
    private object SilentTlsSession : TlsAcceptor.TlsSession {
        override suspend fun read(buf: ByteArray, off: Int, len: Int): Int = awaitCancellation()

        override suspend fun write(buf: ByteArray, off: Int, len: Int) = Unit

        override suspend fun close() = Unit
    }

    private companion object {
        const val FAKE_MAIN = "fake-main"
        const val STREAM_ID = 1u
        const val TIMEOUT_MS = 10_000L
        val SHORT_IDLE = 300.milliseconds
        const val OP_CERT = "serverSslContext"
        const val OP_CREATE = "mcpSessionFactory.create"
        const val OP_CONNECT = "loopback connect"
        const val OP_STOP = "mcpHandle.stop"
    }
}
