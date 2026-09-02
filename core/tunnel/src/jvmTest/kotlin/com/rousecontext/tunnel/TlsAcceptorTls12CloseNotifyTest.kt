package com.rousecontext.tunnel

import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLEngineResult
import javax.net.ssl.SSLEngineResult.HandshakeStatus
import javax.net.ssl.SSLEngineResult.Status
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Timeout

/**
 * The peer's `close_notify` asks a TLS 1.2 server for a record, and this pins
 * both halves of that (#668).
 *
 * ## Why this file exists at all
 *
 * `classifyEngineRequest`'s KDoc used to say, unqualified, that a server
 * `unwrap` of the peer's `close_notify` "does not reproduce" as `NEED_WRAP` --
 * that it always reports `CLOSED` with `NOT_HANDSHAKING`, so row 3 of the
 * `CLOSED` table owns the event outright. That was measured, and it was true of
 * the connection it was measured on, which was TLS 1.3. **The protocol was
 * never varied.** On TLS 1.2 the same `unwrap` reports `CLOSED` with
 * `handshakeStatus = NEED_WRAP` and the engine holds the responding
 * `close_notify`, exactly as #617 described.
 *
 * The claim was prose, and prose does not fail when it stops being true. The
 * measurement is executable here instead, on both protocols, so the axis that
 * was never varied is now the thing under test.
 *
 * ## The two tests
 *
 * [a server unwrap of close_notify asks for a record on TLS 1_2 but not on TLS 1_3]
 * is the JDK premise. It drives two raw SunJSSE engines and varies **only**
 * `setEnabledProtocols`. If a future JDK changes either row, this goes red and
 * the KDoc on `classifyEngineRequest` needs rewriting -- that failure means
 * "the documented measurement is stale", not "the acceptor is broken".
 *
 * [the acceptor answers a TLS 1_2 close_notify before read reports end of stream]
 * is the behaviour that premise implies, over the shipped acceptor. It leaves
 * `TlsAcceptor` completely untouched -- the acceptor calls
 * `SSLContext.getInstance("TLS")` and never `setEnabledProtocols`, so its
 * enabled set is the platform default and includes TLSv1.2 -- and pins only the
 * *client* to TLS 1.2. That is a real configuration the device can be handed,
 * not a contrivance.
 *
 * It asserts the responding record actually reaches the mux stream, not merely
 * that `read` returned -1. The -1 is produced either way: `classifyEmitStatus`
 * and `classifyDataUnwrapStatus` are consulted in that order, so dropping the
 * `NEED_WRAP` branch still yields a clean EOF with the response stranded inside
 * the engine. That silence is precisely how #617 survived, and asserting the
 * `-1` alone would reproduce it.
 *
 * Both engine drivers here are local rather than shared with [TlsAcceptTest]:
 * that file's client reads a mux frame on every `NEED_UNWRAP`, which is fine
 * when a flight arrives one record per frame but deadlocks if a TLS 1.2 server
 * packs `ChangeCipherSpec` and `Finished` into one. The driver below unwraps
 * what is already buffered first and pulls a frame only when that makes no
 * progress.
 *
 * Bounded on a separate thread for the reason set out on
 * [TlsAcceptorSplitRecordTest]: a wedge in the TLS pump makes `runBlocking`
 * park forever, which HANGS the run and writes no JUnit XML at all. Only a
 * `SEPARATE_THREAD` ceiling turns that into a reported failure.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class TlsAcceptorTls12CloseNotifyTest {

    /**
     * The premise, measured rather than asserted from the issue text.
     *
     * Two SunJSSE engines, full handshake, one application record each way,
     * then `closeOutbound()` on the client and a single server `unwrap` of the
     * resulting record. The only variable between the two halves is
     * `setEnabledProtocols`.
     *
     * The `session.protocol` assertions are the control. `setEnabledProtocols`
     * taking effect is exactly the thing that must not be assumed here: a run
     * where it silently did nothing would negotiate TLS 1.3 twice and report
     * two green TLS-1.3 rows, one of them mislabelled.
     */
    @Test
    fun `a server unwrap of close_notify asks for a record on TLS 1_2 but not on TLS 1_3`() {
        val certStore = TestCertificateStore()

        val twelve = EnginePair(certStore, TLS_1_2).apply { establish() }
        assertEquals(
            TLS_1_2,
            twelve.protocolInForce,
            "control: setEnabledProtocols did not take, so this row is not a TLS 1.2 row"
        )
        val twelveClose = twelve.serverUnwrapOfCloseNotify()
        assertEquals(Status.CLOSED, twelveClose.status, "TLS 1.2 close_notify status")
        assertEquals(
            HandshakeStatus.NEED_WRAP,
            twelveClose.handshakeStatus,
            "on TLS 1.2 the peer's close_notify leaves the engine holding the responding " +
                "close_notify and asking the application to wrap it out"
        )
        val twelveResponse = twelve.serverWrap()
        assertEquals(Status.CLOSED, twelveResponse.status, "TLS 1.2 responding wrap status")
        assertTrue(
            twelveResponse.bytesProduced() > 0,
            "the TLS 1.2 NEED_WRAP is live: a wrap after it produces the responding record"
        )

        val thirteen = EnginePair(certStore, TLS_1_3).apply { establish() }
        assertEquals(
            TLS_1_3,
            thirteen.protocolInForce,
            "control: setEnabledProtocols did not take, so this row is not a TLS 1.3 row"
        )
        val thirteenClose = thirteen.serverUnwrapOfCloseNotify()
        assertEquals(Status.CLOSED, thirteenClose.status, "TLS 1.3 close_notify status")
        assertEquals(
            HandshakeStatus.NOT_HANDSHAKING,
            thirteenClose.handshakeStatus,
            "on TLS 1.3 the same event asks for nothing -- this is the row the KDoc " +
                "was originally measured on, and it is only this row"
        )
        assertEquals(
            0,
            thirteen.serverWrap().bytesProduced(),
            "on TLS 1.3 there is no responding record to emit"
        )
    }

    /**
     * The behaviour, over the production `read` loop with no seam.
     *
     * `TlsAcceptor` is constructed exactly as it ships. Only the client is
     * pinned, so this is the shipped acceptor meeting a TLS-1.2-only peer.
     */
    @Test
    fun `the acceptor answers a TLS 1_2 close_notify before read reports end of stream`() =
        runBlocking {
            val certStore = TestCertificateStore()
            // Untouched: no setEnabledProtocols, as TlsAcceptor leaves it.
            val acceptor = TlsAcceptor.create(certStore.sslContext)

            val serverToClient = Channel<ByteArray>(Channel.UNLIMITED)
            val clientToServer = Channel<ByteArray>(Channel.UNLIMITED)
            val serverStream = ChannelMuxStream(1u, clientToServer, serverToClient)
            val clientStream = ChannelMuxStream(1u, serverToClient, clientToServer)

            val accepted = CompletableDeferred<TlsAcceptor.TlsSession>()
            launch(Dispatchers.IO) {
                runCatching { acceptor.accept(serverStream) }
                    .onSuccess { accepted.complete(it) }
                    .onFailure { accepted.completeExceptionally(it) }
            }

            val client = MuxClientEngine(certStore, clientStream, TLS_1_2)
            withTimeout(TIMEOUT_MS) { client.handshake() }

            assertEquals(
                TLS_1_2,
                client.protocolInForce,
                "control: the acceptor did not negotiate TLS 1.2, so this test proves nothing " +
                    "about TLS 1.2 -- note the server was never pinned, only the client"
            )

            val session = withTimeout(TIMEOUT_MS) { accepted.await() }

            // Reach the data phase for real: the NEED_WRAP under test is a
            // post-handshake event, so a session that never carried data would
            // not exercise it.
            withTimeout(TIMEOUT_MS) { client.sendApplicationData(PAYLOAD) }
            val buf = ByteArray(READ_BUFFER)
            val n = withTimeout(TIMEOUT_MS) { session.read(buf, 0, buf.size) }
            assertContentEquals(PAYLOAD, buf.copyOf(n), "the session never reached the data phase")

            assertNull(
                serverToClient.tryReceive().getOrNull(),
                "the acceptor had already written something before the close, so the record " +
                    "asserted below would not identify the response"
            )

            withTimeout(TIMEOUT_MS) { client.sendCloseNotify() }

            assertEquals(
                -1,
                withTimeout(TIMEOUT_MS) { session.read(buf, 0, buf.size) },
                "a data-phase close_notify is the ordinary end of a working session"
            )

            // The assertion this file exists for. On TLS 1.2 the unwrap above
            // reported NEED_WRAP, `serviceEngineRequest` routed that to
            // `pumpEngineOutput`, and the responding close_notify went out
            // BEFORE read() returned -1. Drop the NEED_WRAP branch and the -1
            // above still passes while this fails.
            val response = assertNotNull(
                serverToClient.tryReceive().getOrNull(),
                "the acceptor never answered the peer's close_notify. On TLS 1.2 the server " +
                    "unwrap reports CLOSED with handshakeStatus = NEED_WRAP and the engine is " +
                    "holding the responding record; nothing else will ever emit it"
            )
            assertEquals(
                Status.CLOSED,
                client.unwrapStatus(response),
                "the acceptor emitted a record, but it was not the responding close_notify"
            )

            coroutineContext.cancelChildren()
        }

    // ------------------------------------------------------------- raw engines

    /**
     * Two engines over an in-memory transport. One shared pair of buffers for
     * handshake, drain and data, because skipping a record (a TLS 1.3
     * `NewSessionTicket`, say) desynchronises sequence numbers and the next
     * unwrap dies with a tag mismatch rather than with anything informative.
     */
    private class EnginePair(certStore: TestCertificateStore, private val protocol: String) {
        private val client: SSLEngine =
            certStore.trustingSslContext.createSSLEngine(TEST_HOST, TEST_PORT).apply {
                useClientMode = true
                enabledProtocols = arrayOf(protocol)
            }
        private val server: SSLEngine = certStore.sslContext.createSSLEngine().apply {
            useClientMode = false
            enabledProtocols = arrayOf(protocol)
        }

        private val clientToServer = ByteBuffer.allocate(TRANSPORT_BUFFER)
        private val serverToClient = ByteBuffer.allocate(TRANSPORT_BUFFER)
        private val scratch = ByteBuffer.allocate(TRANSPORT_BUFFER)

        val protocolInForce: String get() = server.session.protocol

        fun establish() {
            handshake()
            applicationRecord(client, server, clientToServer, "client to server")
            applicationRecord(server, client, serverToClient, "server to client")
        }

        fun serverUnwrapOfCloseNotify(): SSLEngineResult {
            client.closeOutbound()
            clientToServer.clear()
            client.wrap(EMPTY, clientToServer)
            clientToServer.flip()
            scratch.clear()
            val result = server.unwrap(clientToServer, scratch)
            clientToServer.compact()
            return result
        }

        fun serverWrap(): SSLEngineResult {
            serverToClient.clear()
            return server.wrap(EMPTY, serverToClient)
        }

        private fun handshake() {
            client.beginHandshake()
            server.beginHandshake()
            repeat(MAX_ROUNDS) {
                val clientMoved = step(client, clientToServer, serverToClient)
                val serverMoved = step(server, serverToClient, clientToServer)
                if (settled(client) && settled(server)) {
                    drainPostHandshakeRecords()
                    return
                }
                check(clientMoved || serverMoved) {
                    "handshake stalled on $protocol: client=${client.handshakeStatus} " +
                        "server=${server.handshakeStatus}"
                }
            }
            error("handshake did not converge on $protocol")
        }

        /** [outbound] is [engine]'s write side, [inbound] its read side. */
        private fun step(engine: SSLEngine, outbound: ByteBuffer, inbound: ByteBuffer): Boolean {
            var moved = false
            repeat(MAX_ROUNDS) {
                when (engine.handshakeStatus) {
                    HandshakeStatus.NEED_TASK -> {
                        runTasks(engine)
                        moved = true
                    }
                    HandshakeStatus.NEED_WRAP -> {
                        val result = engine.wrap(EMPTY, outbound)
                        if (result.bytesProduced() > 0) moved = true
                        if (result.status != Status.OK) return moved
                    }
                    HandshakeStatus.NEED_UNWRAP, HandshakeStatus.NEED_UNWRAP_AGAIN -> {
                        if (!unwrapOne(engine, inbound)) return moved
                        moved = true
                    }
                    else -> return moved
                }
            }
            return moved
        }

        /** One unwrap from [inbound]; false when it cannot make progress. */
        private fun unwrapOne(engine: SSLEngine, inbound: ByteBuffer): Boolean {
            inbound.flip()
            if (!inbound.hasRemaining()) {
                inbound.compact()
                return false
            }
            scratch.clear()
            val result = engine.unwrap(inbound, scratch)
            inbound.compact()
            return result.bytesConsumed() > 0
        }

        /** TLS 1.3 posts `NewSessionTicket`s after `FINISHED`; none may be skipped. */
        private fun drainPostHandshakeRecords() {
            repeat(MAX_ROUNDS) {
                val clientMoved = consumeBuffered(client, serverToClient)
                val serverMoved = consumeBuffered(server, clientToServer)
                if (!clientMoved && !serverMoved) return
            }
        }

        private fun consumeBuffered(engine: SSLEngine, inbound: ByteBuffer): Boolean {
            inbound.flip()
            var moved = false
            while (inbound.hasRemaining()) {
                scratch.clear()
                val result = engine.unwrap(inbound, scratch)
                if (result.status == Status.BUFFER_UNDERFLOW || result.bytesConsumed() == 0) break
                moved = true
                runTasks(engine)
            }
            inbound.compact()
            return moved
        }

        private fun applicationRecord(
            from: SSLEngine,
            to: SSLEngine,
            transport: ByteBuffer,
            label: String
        ) {
            transport.clear()
            from.wrap(ByteBuffer.wrap(label.toByteArray()), transport)
            transport.flip()
            scratch.clear()
            to.unwrap(transport, scratch)
            transport.compact()
            scratch.flip()
            val received = ByteArray(scratch.remaining()).also { scratch.get(it) }
            assertEquals(label, String(received), "application record lost on $protocol")
        }

        private fun settled(engine: SSLEngine): Boolean =
            engine.handshakeStatus == HandshakeStatus.NOT_HANDSHAKING ||
                engine.handshakeStatus == HandshakeStatus.FINISHED
    }

    // --------------------------------------------------- client over a MuxStream

    /** A TLS client speaking to the acceptor across a [MuxStream]. */
    private class MuxClientEngine(
        certStore: TestCertificateStore,
        private val stream: MuxStream,
        protocol: String
    ) {
        private val engine: SSLEngine =
            certStore.trustingSslContext.createSSLEngine(TEST_HOST, TEST_PORT).apply {
                useClientMode = true
                enabledProtocols = arrayOf(protocol)
            }

        private var netIn = ByteBuffer.allocate(engine.session.packetBufferSize)
        private val netOut = ByteBuffer.allocate(engine.session.packetBufferSize)
        private val appIn = ByteBuffer.allocate(engine.session.applicationBufferSize)

        val protocolInForce: String get() = engine.session.protocol

        suspend fun handshake() {
            engine.beginHandshake()
            repeat(MAX_ROUNDS) {
                when (engine.handshakeStatus) {
                    HandshakeStatus.NEED_TASK -> runTasks(engine)
                    HandshakeStatus.NEED_WRAP -> emit()
                    HandshakeStatus.NEED_UNWRAP, HandshakeStatus.NEED_UNWRAP_AGAIN -> receive()
                    else -> return
                }
            }
            error("client handshake did not converge")
        }

        suspend fun sendApplicationData(payload: ByteArray) {
            netOut.clear()
            engine.wrap(ByteBuffer.wrap(payload), netOut)
            flush()
        }

        suspend fun sendCloseNotify() {
            engine.closeOutbound()
            netOut.clear()
            engine.wrap(EMPTY, netOut)
            flush()
        }

        fun unwrapStatus(record: ByteArray): Status {
            val source = ByteBuffer.wrap(record)
            appIn.clear()
            return engine.unwrap(source, appIn).status
        }

        private suspend fun emit() {
            netOut.clear()
            engine.wrap(EMPTY, netOut)
            flush()
        }

        private suspend fun flush() {
            netOut.flip()
            if (!netOut.hasRemaining()) return
            val record = ByteArray(netOut.remaining()).also { netOut.get(it) }
            stream.send(record)
        }

        /**
         * Make progress from what is already buffered; pull a mux frame only
         * when that yields nothing. Reading a frame per `NEED_UNWRAP` instead
         * would deadlock the moment a TLS 1.2 server packs `ChangeCipherSpec`
         * and `Finished` into one frame.
         */
        private suspend fun receive() {
            netIn.flip()
            val consumed = if (netIn.hasRemaining()) {
                appIn.clear()
                engine.unwrap(netIn, appIn).bytesConsumed() > 0
            } else {
                false
            }
            netIn.compact()
            if (consumed) return
            val frame = stream.read()
            netIn = ensureRoom(netIn, frame.size)
            netIn.put(frame)
        }

        private fun ensureRoom(buffer: ByteBuffer, needed: Int): ByteBuffer {
            if (buffer.remaining() >= needed) return buffer
            val grown = ByteBuffer.allocate(buffer.position() + needed)
            buffer.flip()
            grown.put(buffer)
            return grown
        }
    }

    private companion object {
        const val TLS_1_2 = "TLSv1.2"
        const val TLS_1_3 = "TLSv1.3"
        const val TEST_HOST = "test.rousecontext.com"
        const val TEST_PORT = 443
        const val TRANSPORT_BUFFER = 64 * 1024
        const val READ_BUFFER = 1024
        const val MAX_ROUNDS = 200
        const val TIMEOUT_MS = 10_000L
        val PAYLOAD = "plaintext before the close".toByteArray()
        val EMPTY: ByteBuffer = ByteBuffer.allocate(0)

        fun runTasks(engine: SSLEngine) {
            var task = engine.delegatedTask
            while (task != null) {
                task.run()
                task = engine.delegatedTask
            }
        }
    }
}
