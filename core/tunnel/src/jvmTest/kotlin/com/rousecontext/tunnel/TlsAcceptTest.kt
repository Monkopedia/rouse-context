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
 * Tests that verify TLS server-side accept over a MuxStream.
 */
class TlsAcceptTest {
    @Test
    fun `TLS handshake completes and plaintext flows through`() = runBlocking {
        val certStore = TestCertificateStore()
        val acceptor = TlsAcceptor.create(certStore.sslContext)

        // Create a pair of connected MuxStreams (simulated pipe)
        val serverToClient = Channel<ByteArray>(Channel.BUFFERED)
        val clientToServer = Channel<ByteArray>(Channel.BUFFERED)

        val serverStream =
            ChannelMuxStream(
                streamIdValue = 1u,
                readChannel = clientToServer,
                writeChannel = serverToClient
            )
        val clientStream =
            ChannelMuxStream(
                streamIdValue = 1u,
                readChannel = serverToClient,
                writeChannel = clientToServer
            )

        val tlsSessionDeferred = CompletableDeferred<TlsAcceptor.TlsSession>()

        // Server side: TLS accept
        launch(Dispatchers.IO) {
            val tlsSession = acceptor.accept(serverStream)
            tlsSessionDeferred.complete(tlsSession)
        }

        // Client side: TLS handshake as client
        val clientTlsResult = CompletableDeferred<Pair<InputStream, OutputStream>>()
        launch(Dispatchers.IO) {
            val sslEngine = certStore.trustingSslContext.createSSLEngine(
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
                            clientStream.send(data)
                        }
                    }
                    javax.net.ssl.SSLEngineResult.HandshakeStatus.NEED_UNWRAP -> {
                        val tlsData = clientStream.read()
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

            // Handshake complete - send plaintext
            clientTlsResult.complete(
                Pair(
                    TlsClientInputStream(sslEngine, clientStream, netIn),
                    TlsClientOutputStream(sslEngine, clientStream)
                )
            )
        }

        // Wait for both sides to complete handshake
        val serverSession =
            withTimeout(10000) {
                tlsSessionDeferred.await()
            }
        val (clientIn, clientOut) =
            withTimeout(10000) {
                clientTlsResult.await()
            }

        // Client writes plaintext, server reads it
        clientOut.write("hello from client".toByteArray())
        clientOut.flush()

        val buf = ByteArray(1024)
        val n = serverSession.input.read(buf, 0, buf.size)
        assertTrue(n > 0)
        assertEquals("hello from client", String(buf, 0, n))

        // Server writes plaintext, client reads it
        serverSession.output.write("hello from server".toByteArray())
        serverSession.output.flush()

        val buf2 = ByteArray(1024)
        val n2 = clientIn.read(buf2, 0, buf2.size)
        assertTrue(n2 > 0)
        assertEquals("hello from server", String(buf2, 0, n2))

        coroutineContext.cancelChildren()
    }

    @Test
    fun `invalid TLS from client is handled gracefully`() = runBlocking {
        val certStore = TestCertificateStore()
        val acceptor = TlsAcceptor.create(certStore.sslContext)

        val serverToClient = Channel<ByteArray>(Channel.BUFFERED)
        val clientToServer = Channel<ByteArray>(Channel.BUFFERED)

        val serverStream =
            ChannelMuxStream(
                streamIdValue = 1u,
                readChannel = clientToServer,
                writeChannel = serverToClient
            )

        // Send garbage data instead of a proper TLS ClientHello
        clientToServer.send("this is not TLS data at all".toByteArray())
        clientToServer.close()

        val result =
            runCatching {
                withTimeout(5000) {
                    acceptor.accept(serverStream)
                }
            }

        assertTrue(result.isFailure)
        val error = result.exceptionOrNull()
        assertTrue(
            error is TunnelError.TlsHandshakeFailed,
            "Expected TlsHandshakeFailed but got ${error?.javaClass?.name}: ${error?.message}"
        )

        coroutineContext.cancelChildren()
    }
}

/**
 * A MuxStream backed by channels for testing. Simulates a bidirectional pipe.
 */
internal class ChannelMuxStream(
    private val streamIdValue: UInt,
    private val readChannel: Channel<ByteArray>,
    private val writeChannel: Channel<ByteArray>
) : MuxStream {
    override val id: UInt get() = streamIdValue

    private val _incoming = readChannel
    override val incoming: Flow<ByteArray> get() = _incoming.receiveAsFlow()

    override var isClosed: Boolean = false
        private set

    override suspend fun read(): ByteArray = readChannel.receive()

    override suspend fun send(data: ByteArray) {
        writeChannel.send(data)
    }

    override suspend fun close() {
        isClosed = true
        writeChannel.close()
    }
}

private fun ensureCapacity(buffer: java.nio.ByteBuffer, additionalBytes: Int): java.nio.ByteBuffer {
    if (buffer.remaining() >= additionalBytes) return buffer
    val newBuffer = java.nio.ByteBuffer.allocate(buffer.position() + additionalBytes)
    buffer.flip()
    newBuffer.put(buffer)
    return newBuffer
}

/**
 * TLS client-side InputStream for tests.
 */
internal class TlsClientInputStream(
    private val engine: javax.net.ssl.SSLEngine,
    private val stream: MuxStream,
    private var netIn: java.nio.ByteBuffer
) : java.io.InputStream() {
    private var appIn = java.nio.ByteBuffer.allocate(engine.session.applicationBufferSize)

    init {
        appIn.flip()
    }

    override fun read(): Int {
        val buf = ByteArray(1)
        val n = read(buf, 0, 1)
        return if (n == -1) -1 else buf[0].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (appIn.hasRemaining()) {
            val toRead = minOf(len, appIn.remaining())
            appIn.get(b, off, toRead)
            return toRead
        }

        appIn.clear()
        while (true) {
            val tlsData =
                try {
                    kotlinx.coroutines.runBlocking { stream.read() }
                } catch (_: Exception) {
                    return -1
                }
            netIn = ensureCapacity(netIn, tlsData.size)
            netIn.put(tlsData)
            netIn.flip()

            val result = engine.unwrap(netIn, appIn)
            netIn.compact()

            when (result.status) {
                javax.net.ssl.SSLEngineResult.Status.OK -> {
                    appIn.flip()
                    if (appIn.hasRemaining()) {
                        val toRead = minOf(len, appIn.remaining())
                        appIn.get(b, off, toRead)
                        return toRead
                    }
                    appIn.clear()
                }
                javax.net.ssl.SSLEngineResult.Status.CLOSED -> return -1
                javax.net.ssl.SSLEngineResult.Status.BUFFER_UNDERFLOW -> appIn.clear()
                javax.net.ssl.SSLEngineResult.Status.BUFFER_OVERFLOW -> {
                    val newBuf = java.nio.ByteBuffer.allocate(appIn.capacity() * 2)
                    appIn.flip()
                    newBuf.put(appIn)
                    appIn = newBuf
                }
                else -> return -1
            }
        }
    }
}

/**
 * TLS client-side OutputStream for tests.
 */
internal class TlsClientOutputStream(
    private val engine: javax.net.ssl.SSLEngine,
    private val stream: MuxStream
) : java.io.OutputStream() {
    private val netOut = java.nio.ByteBuffer.allocate(engine.session.packetBufferSize)

    override fun write(b: Int) {
        write(byteArrayOf(b.toByte()), 0, 1)
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        val appOut = java.nio.ByteBuffer.wrap(b, off, len)
        while (appOut.hasRemaining()) {
            netOut.clear()
            val result = engine.wrap(appOut, netOut)
            netOut.flip()
            if (netOut.hasRemaining()) {
                val data = ByteArray(netOut.remaining())
                netOut.get(data)
                kotlinx.coroutines.runBlocking { stream.send(data) }
            }
            if (result.status != javax.net.ssl.SSLEngineResult.Status.OK) {
                throw java.io.IOException("TLS wrap failed: ${result.status}")
            }
        }
    }
}
