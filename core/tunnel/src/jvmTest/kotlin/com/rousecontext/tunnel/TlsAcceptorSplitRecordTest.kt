package com.rousecontext.tunnel

import java.util.concurrent.TimeUnit
import kotlin.test.Test
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
 * Regression tests for the inverse of [TlsAcceptorTest]'s multi-record case.
 *
 * `TlsAcceptor` used to pull another mux DATA frame only when
 * `netIn.position() == 0`. That guard exists so several TLS records coalesced
 * into ONE frame are all consumed before reading again (`TlsAcceptorTest`). But
 * ONE TLS record SPLIT across TWO frames leaves a partial record in `netIn`, so
 * `position() != 0`, so the old code never read the remainder: `unwrap` returned
 * `BUFFER_UNDERFLOW` consuming zero bytes, `compact()` restored the same bytes,
 * and the loop ran forever at 100% CPU with no suspension point (so it was not
 * cancellable either). The peer saw a socket that never spoke again and failed
 * on its own read timeout.
 *
 * The relay splices client bytes with a 16 KiB read buffer
 * (`relay/src/passthrough.rs`) while a TLS record can be ~16.4 KiB on the wire,
 * so any client burst over 16 KiB splits a record deterministically. See #558.
 *
 * ## Why the class carries a `SEPARATE_THREAD` timeout (#566)
 *
 * The defect these tests guard is an UNCANCELLABLE spin, so a regression does
 * not report red -- it hangs. The `withTimeout` bounds below cannot prevent
 * that: they bound the `await`, but the spinning `accept` coroutine is a child
 * of this `runBlocking`, and a child that never reaches a suspension point
 * ignores the cancellation `runBlocking` sends it. `runBlocking` then parks
 * forever waiting for it, so a regression burns the whole job budget and writes
 * no JUnit XML at all (measured on #563: killed at 300 s, 0 tests executed) --
 * which the executed-count check reads as "the build never ran".
 *
 * Only a thread-level ceiling can turn that into a reported failure, and
 * `SEPARATE_THREAD` is the one that does not depend on the wedged thread
 * cooperating: it abandons the run and fails the test. (This is the same
 * reasoning, and the same mechanism, as `MultiClientConcurrencyTest`'s
 * method-level ceiling -- see #504.) The Gradle output-store hazard that
 * `IntegrationHttpSupport` warns about for `SEPARATE_THREAD` does not apply
 * here: `TlsAcceptor` logs nothing, so an abandoned coroutine emits no output
 * to race the per-test store.
 *
 * 60 s is a ceiling, not a deadline. The class runs in ~0.7 s inside the warm
 * suite and ~1.5 s cold, and the longest path that is merely SLOW rather than
 * wedged is bounded by the in-test `withTimeout`s (45 s worst case in the
 * second test), which fail with a more specific message and are still
 * cancellable. 60 s sits above that and ~40x above the slowest observed
 * runtime, so a loaded machine reports red for the right reason or not at all.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class TlsAcceptorSplitRecordTest {

    @Test
    fun `handshake completes when a TLS record is split across two DATA frames`() = runBlocking {
        val certStore = TestCertificateStore()
        val acceptor = TlsAcceptor.create(certStore.sslContext)

        val clientToServerRaw = Channel<ByteArray>(Channel.UNLIMITED)
        val serverToClient = Channel<ByteArray>(Channel.BUFFERED)
        val serverReads = Channel<ByteArray>(Channel.BUFFERED)

        val clientStream = ChannelMuxStream(
            streamIdValue = 1u,
            readChannel = serverToClient,
            writeChannel = clientToServerRaw
        )
        val serverStream = SplittingServerStream(1u, serverReads, serverToClient)

        // Pump: split EVERY client->server delivery in half, as a relay whose
        // read() returned a partial TLS record would.
        val pumpJob = launch {
            while (true) {
                val chunk = clientToServerRaw.receiveCatching().getOrNull() ?: break
                if (chunk.size < 2) {
                    serverReads.send(chunk)
                } else {
                    val mid = chunk.size / 2
                    serverReads.send(chunk.copyOfRange(0, mid))
                    serverReads.send(chunk.copyOfRange(mid, chunk.size))
                }
            }
        }

        val tlsSessionDeferred = CompletableDeferred<TlsAcceptor.TlsSession>()
        launch(Dispatchers.IO) {
            try {
                tlsSessionDeferred.complete(acceptor.accept(serverStream))
            } catch (e: Exception) {
                tlsSessionDeferred.completeExceptionally(e)
            }
        }

        val clientDone = CompletableDeferred<Unit>()
        launch(Dispatchers.IO) {
            try {
                clientHandshakeOnly(clientStream, certStore.trustingSslContext)
                clientDone.complete(Unit)
            } catch (e: Exception) {
                clientDone.completeExceptionally(e)
            }
        }

        val ok = runCatching {
            withTimeout(15_000) { tlsSessionDeferred.await() }
        }
        pumpJob.cancel()
        coroutineContext.cancelChildren()
        assertTrue(
            ok.isSuccess,
            "TLS accept did not complete with split records: ${ok.exceptionOrNull()}"
        )
    }

    @Test
    fun `session read completes when an application record is split across frames`() = runBlocking {
        val certStore = TestCertificateStore()
        val acceptor = TlsAcceptor.create(certStore.sslContext)

        val clientToServerRaw = Channel<ByteArray>(Channel.UNLIMITED)
        val serverToClient = Channel<ByteArray>(Channel.BUFFERED)
        val serverReads = Channel<ByteArray>(Channel.BUFFERED)

        val clientStream = ChannelMuxStream(
            streamIdValue = 1u,
            readChannel = serverToClient,
            writeChannel = clientToServerRaw
        )
        val serverStream = SplittingServerStream(1u, serverReads, serverToClient)

        val pumpJob = launch {
            while (true) {
                val chunk = clientToServerRaw.receiveCatching().getOrNull() ?: break
                if (chunk.size < 2) {
                    serverReads.send(chunk)
                } else {
                    val mid = chunk.size / 2
                    serverReads.send(chunk.copyOfRange(0, mid))
                    serverReads.send(chunk.copyOfRange(mid, chunk.size))
                }
            }
        }

        val tlsSessionDeferred = CompletableDeferred<TlsAcceptor.TlsSession>()
        launch(Dispatchers.IO) {
            try {
                tlsSessionDeferred.complete(acceptor.accept(serverStream))
            } catch (e: Exception) {
                tlsSessionDeferred.completeExceptionally(e)
            }
        }

        val engineDeferred = CompletableDeferred<javax.net.ssl.SSLEngine>()
        launch(Dispatchers.IO) {
            try {
                engineDeferred.complete(
                    clientHandshakeOnly(clientStream, certStore.trustingSslContext)
                )
            } catch (e: Exception) {
                engineDeferred.completeExceptionally(e)
            }
        }

        val serverSession = withTimeout(15_000) { tlsSessionDeferred.await() }
        val engine = withTimeout(15_000) { engineDeferred.await() }

        // Client sends application data; the pump splits its record in half.
        val payload = "hello across a split record".toByteArray()
        val appOut = java.nio.ByteBuffer.wrap(payload)
        val netOut = java.nio.ByteBuffer.allocate(engine.session.packetBufferSize)
        engine.wrap(appOut, netOut)
        netOut.flip()
        val record = ByteArray(netOut.remaining())
        netOut.get(record)
        clientStream.send(record)

        val buf = ByteArray(1024)
        val n = withTimeout(15_000) { serverSession.read(buf, 0, buf.size) }
        assertTrue(n > 0, "expected plaintext from a split application record")
        kotlin.test.assertEquals(String(payload), String(buf, 0, n))

        pumpJob.cancel()
        coroutineContext.cancelChildren()
    }

    private class SplittingServerStream(
        private val streamIdValue: UInt,
        private val serverReads: Channel<ByteArray>,
        private val serverWrites: Channel<ByteArray>
    ) : MuxStream {
        override val id: UInt get() = streamIdValue
        override val incoming: kotlinx.coroutines.flow.Flow<ByteArray>
            get() = kotlinx.coroutines.flow.flow { }
        override var isClosed: Boolean = false
            private set

        override suspend fun read(): ByteArray = serverReads.receive()
        override suspend fun send(data: ByteArray) = serverWrites.send(data)
        override suspend fun close() {
            isClosed = true
            serverWrites.close()
        }
    }

    /**
     * Drive the client half of the handshake. The server->client direction is not
     * split by the pump, so one record arrives per read and the simple
     * always-read form is sufficient here.
     */
    private fun clientHandshakeOnly(
        clientStream: ChannelMuxStream,
        trustingSslContext: javax.net.ssl.SSLContext
    ): javax.net.ssl.SSLEngine {
        val engine = trustingSslContext.createSSLEngine("test.rousecontext.com", 443)
        engine.useClientMode = true
        val session = engine.session
        var netIn = java.nio.ByteBuffer.allocate(session.packetBufferSize)
        val netOut = java.nio.ByteBuffer.allocate(session.packetBufferSize)
        val appIn = java.nio.ByteBuffer.allocate(session.applicationBufferSize)
        val appOut = java.nio.ByteBuffer.allocate(session.applicationBufferSize)

        engine.beginHandshake()
        var hsStatus = engine.handshakeStatus
        while (hsStatus != javax.net.ssl.SSLEngineResult.HandshakeStatus.FINISHED &&
            hsStatus != javax.net.ssl.SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING
        ) {
            hsStatus = when (hsStatus) {
                javax.net.ssl.SSLEngineResult.HandshakeStatus.NEED_WRAP ->
                    clientWrap(engine, appOut, netOut, clientStream)
                javax.net.ssl.SSLEngineResult.HandshakeStatus.NEED_UNWRAP -> {
                    val tlsData = runBlocking { clientStream.read() }
                    if (netIn.remaining() < tlsData.size) {
                        val grown = java.nio.ByteBuffer.allocate(netIn.position() + tlsData.size)
                        netIn.flip()
                        grown.put(netIn)
                        netIn = grown
                    }
                    netIn.put(tlsData)
                    netIn.flip()
                    val result = engine.unwrap(netIn, appIn)
                    netIn.compact()
                    result.handshakeStatus
                }
                javax.net.ssl.SSLEngineResult.HandshakeStatus.NEED_TASK -> {
                    var task = engine.delegatedTask
                    while (task != null) {
                        task.run()
                        task = engine.delegatedTask
                    }
                    engine.handshakeStatus
                }
                else -> break
            }
        }
        return engine
    }

    private fun clientWrap(
        engine: javax.net.ssl.SSLEngine,
        appOut: java.nio.ByteBuffer,
        netOut: java.nio.ByteBuffer,
        clientStream: ChannelMuxStream
    ): javax.net.ssl.SSLEngineResult.HandshakeStatus {
        netOut.clear()
        val result = engine.wrap(appOut, netOut)
        netOut.flip()
        if (netOut.hasRemaining()) {
            val data = ByteArray(netOut.remaining())
            netOut.get(data)
            runBlocking { clientStream.send(data) }
        }
        return result.handshakeStatus
    }
}
