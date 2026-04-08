package com.rousecontext.tunnel

import java.io.InputStream
import java.io.OutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Tests for TlsAcceptor focusing on multi-record TLS frames.
 *
 * The bug: when the relay delivers multiple TLS records in a single mux
 * DATA frame (e.g. because they were buffered while the device was asleep),
 * the old code would re-read from the stream even though netIn already
 * contained unconsumed data, causing the handshake to hang.
 *
 * The fix checks `if (netIn.position() == 0)` before calling stream.read(),
 * so leftover data from a previous multi-record frame is consumed first.
 */
class TlsAcceptorTest {

    /**
     * A MuxStream that sits between client and server, concatenating
     * consecutive client-to-server writes into single deliveries.
     *
     * The server reads from [serverReads]. When the client writes,
     * data goes into [clientWrites]. A background pump collects
     * data and, when the server is ready, delivers it -- potentially
     * combining multiple client writes that arrived while the server
     * was busy processing.
     */
    private class ConcatenatingServerStream(
        private val streamIdValue: UInt,
        private val serverReads: Channel<ByteArray>,
        private val serverWrites: Channel<ByteArray>
    ) : MuxStream {
        override val id: UInt get() = streamIdValue
        override val incoming: Flow<ByteArray> get() = serverReads.receiveAsFlow()
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
     * Perform a TLS client-side handshake against a MuxStream.
     * Returns the plaintext I/O streams on success.
     */
    private fun doClientHandshake(
        clientStream: ChannelMuxStream,
        trustingSslContext: javax.net.ssl.SSLContext
    ): Pair<InputStream, OutputStream> {
        val sslEngine = trustingSslContext.createSSLEngine(
            "test.rousecontext.com",
            443
        )
        sslEngine.useClientMode = true

        val session = sslEngine.session
        var netIn = java.nio.ByteBuffer.allocate(session.packetBufferSize)
        var netOut = java.nio.ByteBuffer.allocate(session.packetBufferSize)
        val appIn = java.nio.ByteBuffer.allocate(session.applicationBufferSize)
        val appOut = java.nio.ByteBuffer.allocate(session.applicationBufferSize)

        sslEngine.beginHandshake()
        var hsStatus = sslEngine.handshakeStatus

        while (hsStatus != javax.net.ssl.SSLEngineResult.HandshakeStatus.FINISHED &&
            hsStatus != javax.net.ssl.SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING
        ) {
            when (hsStatus) {
                javax.net.ssl.SSLEngineResult.HandshakeStatus.NEED_WRAP -> {
                    netOut.clear()
                    val result = sslEngine.wrap(appOut, netOut)
                    hsStatus = result.handshakeStatus
                    netOut.flip()
                    if (netOut.hasRemaining()) {
                        val data = ByteArray(netOut.remaining())
                        netOut.get(data)
                        kotlinx.coroutines.runBlocking { clientStream.send(data) }
                    }
                }
                javax.net.ssl.SSLEngineResult.HandshakeStatus.NEED_UNWRAP -> {
                    val tlsData = kotlinx.coroutines.runBlocking { clientStream.read() }
                    netIn = ensureCapacity(netIn, tlsData.size)
                    netIn.put(tlsData)
                    netIn.flip()
                    val result = sslEngine.unwrap(netIn, appIn)
                    hsStatus = result.handshakeStatus
                    netIn.compact()
                }
                javax.net.ssl.SSLEngineResult.HandshakeStatus.NEED_TASK -> {
                    var task = sslEngine.delegatedTask
                    while (task != null) {
                        task.run()
                        task = sslEngine.delegatedTask
                    }
                    hsStatus = sslEngine.handshakeStatus
                }
                else -> break
            }
        }

        return Pair(
            TlsClientInputStream(sslEngine, clientStream, netIn),
            TlsClientOutputStream(sslEngine, clientStream)
        )
    }

    @Test
    fun `TLS handshake completes when multiple TLS records arrive in single DATA frame`() =
        runBlocking {
            val certStore = TestCertificateStore()
            val acceptor = TlsAcceptor.create(certStore.sslContext)

            // Client <-> server channels
            val clientToServerRaw = Channel<ByteArray>(Channel.UNLIMITED)
            val serverToClient = Channel<ByteArray>(Channel.BUFFERED)

            // The server reads from this channel -- we will concatenate
            // multiple client writes before delivering here
            val serverReads = Channel<ByteArray>(Channel.BUFFERED)

            val clientStream = ChannelMuxStream(
                streamIdValue = 1u,
                readChannel = serverToClient,
                writeChannel = clientToServerRaw
            )

            val serverStream = ConcatenatingServerStream(
                streamIdValue = 1u,
                serverReads = serverReads,
                serverWrites = serverToClient
            )

            // Pump that concatenates consecutive client writes before
            // delivering to the server, simulating relay buffering behavior
            val pumpJob = launch {
                var first = true
                while (true) {
                    // Wait for at least one write
                    val chunk = clientToServerRaw.receiveCatching()
                        .getOrNull() ?: break
                    if (first) {
                        // Deliver first ClientHello normally
                        serverReads.send(chunk)
                        first = false
                    } else {
                        // For subsequent writes, try to grab any additional
                        // data that is already queued and concatenate
                        val combined = mutableListOf(chunk)
                        while (true) {
                            val extra = clientToServerRaw.tryReceive()
                                .getOrNull() ?: break
                            combined.add(extra)
                        }
                        if (combined.size > 1) {
                            val total = combined.sumOf { it.size }
                            val merged = ByteArray(total)
                            var offset = 0
                            for (part in combined) {
                                part.copyInto(merged, offset)
                                offset += part.size
                            }
                            serverReads.send(merged)
                        } else {
                            serverReads.send(chunk)
                        }
                    }
                }
            }

            val tlsSessionDeferred = CompletableDeferred<TlsAcceptor.TlsSession>()

            // Server side: TLS accept
            launch(Dispatchers.IO) {
                try {
                    val tlsSession = acceptor.accept(serverStream)
                    tlsSessionDeferred.complete(tlsSession)
                } catch (e: Exception) {
                    tlsSessionDeferred.completeExceptionally(e)
                }
            }

            // Client side: TLS handshake
            val clientTlsResult = CompletableDeferred<Pair<InputStream, OutputStream>>()
            launch(Dispatchers.IO) {
                try {
                    val result = doClientHandshake(clientStream, certStore.trustingSslContext)
                    clientTlsResult.complete(result)
                } catch (e: Exception) {
                    clientTlsResult.completeExceptionally(e)
                }
            }

            val serverSession = withTimeout(10_000) {
                tlsSessionDeferred.await()
            }
            val (clientIn, clientOut) = withTimeout(10_000) {
                clientTlsResult.await()
            }

            // Verify bidirectional data flow works
            clientOut.write("hello from batched client".toByteArray())
            clientOut.flush()

            val buf = ByteArray(1024)
            val n = serverSession.input.read(buf, 0, buf.size)
            assertTrue(n > 0, "Expected to read data from server input")
            assertEquals("hello from batched client", String(buf, 0, n))

            serverSession.output.write("hello from server".toByteArray())
            serverSession.output.flush()

            val buf2 = ByteArray(1024)
            val n2 = clientIn.read(buf2, 0, buf2.size)
            assertTrue(n2 > 0, "Expected to read data from client input")
            assertEquals("hello from server", String(buf2, 0, n2))

            pumpJob.cancel()
            coroutineContext.cancelChildren()
        }

    @Test
    fun `TLS handshake succeeds with standard one-record-per-frame delivery`() = runBlocking {
        // Baseline test: standard delivery without batching, to confirm
        // the test infrastructure works and to serve as a control
        val certStore = TestCertificateStore()
        val acceptor = TlsAcceptor.create(certStore.sslContext)

        val serverToClient = Channel<ByteArray>(Channel.BUFFERED)
        val clientToServer = Channel<ByteArray>(Channel.BUFFERED)

        val clientStream = ChannelMuxStream(
            streamIdValue = 1u,
            readChannel = serverToClient,
            writeChannel = clientToServer
        )
        val serverStream = ChannelMuxStream(
            streamIdValue = 1u,
            readChannel = clientToServer,
            writeChannel = serverToClient
        )

        val tlsSessionDeferred = CompletableDeferred<TlsAcceptor.TlsSession>()

        launch(Dispatchers.IO) {
            try {
                val tlsSession = acceptor.accept(serverStream)
                tlsSessionDeferred.complete(tlsSession)
            } catch (e: Exception) {
                tlsSessionDeferred.completeExceptionally(e)
            }
        }

        val clientTlsResult = CompletableDeferred<Pair<InputStream, OutputStream>>()
        launch(Dispatchers.IO) {
            try {
                val result = doClientHandshake(clientStream, certStore.trustingSslContext)
                clientTlsResult.complete(result)
            } catch (e: Exception) {
                clientTlsResult.completeExceptionally(e)
            }
        }

        val serverSession = withTimeout(10_000) {
            tlsSessionDeferred.await()
        }
        val (clientIn, clientOut) = withTimeout(10_000) {
            clientTlsResult.await()
        }

        clientOut.write("control test".toByteArray())
        clientOut.flush()

        val buf = ByteArray(1024)
        val n = serverSession.input.read(buf, 0, buf.size)
        assertTrue(n > 0)
        assertEquals("control test", String(buf, 0, n))

        coroutineContext.cancelChildren()
    }
}

private fun ensureCapacity(buffer: java.nio.ByteBuffer, additionalBytes: Int): java.nio.ByteBuffer {
    if (buffer.remaining() >= additionalBytes) return buffer
    val newBuffer = java.nio.ByteBuffer.allocate(buffer.position() + additionalBytes)
    buffer.flip()
    newBuffer.put(buffer)
    return newBuffer
}
