package com.rousecontext.bridge

import com.rousecontext.tunnel.MuxStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngineResult
import javax.net.ssl.TrustManagerFactory
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * In-memory certificate store for testing. Generates a self-signed cert using keytool.
 * Implements [TlsCertProvider] for use with [SessionHandler].
 */
class TestCertHelper : TlsCertProvider {
    val certificate: X509Certificate
    val keyStore: KeyStore
    val sslContext: SSLContext
    val trustingSslContext: SSLContext

    init {
        val tempFile = File.createTempFile("test-keystore-", ".p12")
        tempFile.deleteOnExit()
        tempFile.delete()

        val process = ProcessBuilder(
            "keytool",
            "-genkeypair",
            "-alias", "test",
            "-keyalg", "RSA",
            "-keysize", "2048",
            "-sigalg", "SHA256withRSA",
            "-dname", "CN=test.rousecontext.com",
            "-validity", "365",
            "-storetype", "PKCS12",
            "-keystore", tempFile.absolutePath,
            "-storepass", PASS_STR,
            "-keypass", PASS_STR
        ).redirectErrorStream(true).start()

        val output = process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()
        require(exitCode == 0) { "keytool failed (exit $exitCode): $output" }

        keyStore = KeyStore.getInstance("PKCS12")
        tempFile.inputStream().use { keyStore.load(it, PASS) }
        tempFile.delete()

        certificate = keyStore.getCertificate("test") as X509Certificate

        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(keyStore, PASS)
        sslContext = SSLContext.getInstance("TLS")
        sslContext.init(kmf.keyManagers, null, null)

        val trustStore = KeyStore.getInstance(KeyStore.getDefaultType())
        trustStore.load(null, PASS)
        trustStore.setCertificateEntry("test", certificate)
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(trustStore)
        trustingSslContext = SSLContext.getInstance("TLS")
        trustingSslContext.init(null, tmf.trustManagers, null)
    }

    override suspend fun serverSslContext(): SSLContext = sslContext

    companion object {
        private const val PASS_STR = "test123"
        private val PASS = PASS_STR.toCharArray()
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
    override val incoming: Flow<ByteArray> get() = readChannel.receiveAsFlow()
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

/**
 * Creates a connected pair of ChannelMuxStreams simulating a mux pipe.
 */
internal fun createMuxPipe(streamId: UInt): Pair<ChannelMuxStream, ChannelMuxStream> {
    val serverToClient = Channel<ByteArray>(Channel.BUFFERED)
    val clientToServer = Channel<ByteArray>(Channel.BUFFERED)

    val serverStream = ChannelMuxStream(
        streamIdValue = streamId,
        readChannel = clientToServer,
        writeChannel = serverToClient
    )
    val clientStream = ChannelMuxStream(
        streamIdValue = streamId,
        readChannel = serverToClient,
        writeChannel = clientToServer
    )

    return serverStream to clientStream
}

/**
 * Performs TLS client-side handshake over a [ChannelMuxStream] and returns plaintext I/O streams.
 *
 * After the handshake, a dedicated pump thread drains the MuxStream's incoming
 * ciphertext into a [LinkedBlockingQueue] so that [TlsClientInputStream.read]
 * can block on the queue without using a nested `runBlocking` on the caller's
 * thread. A matching outbound pump drains writes from the output queue back to
 * the MuxStream. This avoids deadlocks when the test body (typically driving
 * `runBlocking`) invokes these blocking JDK streams while other coroutines are
 * pending on the same event loop. See issue #223.
 */
internal suspend fun tlsClientHandshake(
    certHelper: TestCertHelper,
    clientStream: ChannelMuxStream
): Pair<InputStream, OutputStream> {
    val sslEngine = certHelper.trustingSslContext.createSSLEngine(
        "test.rousecontext.com",
        443
    )
    sslEngine.useClientMode = true

    val netIn = performTlsClientHandshake(sslEngine, clientStream)
    val pumps = startTlsClientPumps(clientStream)
    return Pair(
        TlsClientInputStream(
            sslEngine,
            pumps.inboundQueue,
            netIn,
            pumps.closed,
            pumps.inboundThread,
            pumps.outboundThread
        ),
        TlsClientOutputStream(sslEngine, pumps.outboundQueue)
    )
}

/**
 * Drives the TLS client handshake synchronously against [clientStream] until
 * it is finished, returning the residual ciphertext read buffer so the caller
 * can continue decoding any already-received data.
 */
private suspend fun performTlsClientHandshake(
    sslEngine: javax.net.ssl.SSLEngine,
    clientStream: ChannelMuxStream
): ByteBuffer {
    val session = sslEngine.session
    var netIn = ByteBuffer.allocate(session.packetBufferSize)
    val netOut = ByteBuffer.allocate(session.packetBufferSize)
    val appIn = ByteBuffer.allocate(session.applicationBufferSize)
    val appOut = ByteBuffer.allocate(session.applicationBufferSize)

    sslEngine.beginHandshake()
    var hsStatus = sslEngine.handshakeStatus

    while (hsStatus != SSLEngineResult.HandshakeStatus.FINISHED &&
        hsStatus != SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING
    ) {
        when (hsStatus) {
            SSLEngineResult.HandshakeStatus.NEED_WRAP -> {
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
            SSLEngineResult.HandshakeStatus.NEED_UNWRAP -> {
                val tlsData = clientStream.read()
                netIn = ensureCapacity(netIn, tlsData.size)
                netIn.put(tlsData)
                netIn.flip()
                val result = sslEngine.unwrap(netIn, appIn)
                hsStatus = result.handshakeStatus
                netIn.compact()
            }
            SSLEngineResult.HandshakeStatus.NEED_TASK -> {
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
    return netIn
}

private class TlsClientPumps(
    val inboundQueue: LinkedBlockingQueue<ByteArray>,
    val outboundQueue: LinkedBlockingQueue<ByteArray>,
    val closed: AtomicBoolean,
    val inboundThread: Thread,
    val outboundThread: Thread
)

/**
 * Starts a pair of dedicated pump threads that bridge the [clientStream]
 * suspend API to blocking queues. The inbound pump drains ciphertext from the
 * MuxStream and enqueues it for [TlsClientInputStream.read]. The outbound pump
 * drains the queue that [TlsClientOutputStream.write] fills and sends to the
 * MuxStream. Each pump owns its own `runBlocking` on its own thread, so the
 * test's outer `runBlocking` event loop is never reentered.
 */
private fun startTlsClientPumps(clientStream: ChannelMuxStream): TlsClientPumps {
    val inbound = LinkedBlockingQueue<ByteArray>()
    val outbound = LinkedBlockingQueue<ByteArray>()
    val closed = AtomicBoolean(false)

    val inboundThread = Thread({
        try {
            while (!closed.get()) {
                val data = kotlinx.coroutines.runBlocking { clientStream.read() }
                inbound.put(data)
            }
        } catch (_: Exception) {
            // EOF or close -- let the reader see EOF via sentinel.
        } finally {
            inbound.offer(EMPTY_EOF)
        }
    }, "tls-client-inbound-pump").apply {
        isDaemon = true
        start()
    }

    val outboundThread = Thread({
        try {
            while (!closed.get()) {
                val data = outbound.poll(POLL_INTERVAL_MS, TimeUnit.MILLISECONDS) ?: continue
                if (data === EMPTY_EOF) break
                kotlinx.coroutines.runBlocking { clientStream.send(data) }
            }
        } catch (_: Exception) {
            // Stream closed -- stop.
        }
    }, "tls-client-outbound-pump").apply {
        isDaemon = true
        start()
    }

    return TlsClientPumps(inbound, outbound, closed, inboundThread, outboundThread)
}

private const val POLL_INTERVAL_MS: Long = 100

/**
 * Sentinel value meaning "end of stream". Reference-equality checked.
 */
internal val EMPTY_EOF = ByteArray(0)

/**
 * Per-test wall-clock timeout used by the JUnit `Timeout` rule on each bridge
 * test class. 30 seconds is generous for the slowest test (OAuth device flow
 * waits 5.5 s past the RFC 8628 poll interval) while still failing fast if a
 * coroutine deadlocks. See issue #223.
 */
internal const val TEST_TIMEOUT_SECONDS: Long = 30

private fun ensureCapacity(buffer: ByteBuffer, additionalBytes: Int): ByteBuffer {
    if (buffer.remaining() >= additionalBytes) return buffer
    val newBuffer = ByteBuffer.allocate(buffer.position() + additionalBytes)
    buffer.flip()
    newBuffer.put(buffer)
    return newBuffer
}

/**
 * TLS client-side InputStream for tests.
 *
 * Reads ciphertext from a [LinkedBlockingQueue] fed by a dedicated pump thread
 * rather than calling a nested `runBlocking` on the caller's thread. This keeps
 * the test's outer `runBlocking` event loop free of reentrant blocking
 * operations. See issue #223.
 */
internal class TlsClientInputStream(
    private val engine: javax.net.ssl.SSLEngine,
    private val inbound: LinkedBlockingQueue<ByteArray>,
    private var netIn: ByteBuffer,
    private val closedFlag: AtomicBoolean,
    private val inboundPump: Thread,
    private val outboundPump: Thread
) : InputStream() {
    private var appIn = ByteBuffer.allocate(engine.session.applicationBufferSize)
    init {
        appIn.flip()
    }

    override fun read(): Int {
        val buf = ByteArray(1)
        val n = read(buf, 0, 1)
        return if (n == -1) -1 else buf[0].toInt() and 0xFF
    }

    @Suppress("ReturnCount")
    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (appIn.hasRemaining()) {
            val toRead = minOf(len, appIn.remaining())
            appIn.get(b, off, toRead)
            return toRead
        }
        appIn.clear()
        while (true) {
            // Block the JDK-side reader thread on the queue. The inbound pump
            // thread performs the actual suspending read against the MuxStream.
            val tlsData = try {
                inbound.take()
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return -1
            }
            if (tlsData === EMPTY_EOF) return -1
            netIn = ensureCapacity(netIn, tlsData.size)
            netIn.put(tlsData)
            netIn.flip()

            val result = engine.unwrap(netIn, appIn)
            netIn.compact()

            when (result.status) {
                SSLEngineResult.Status.OK -> {
                    appIn.flip()
                    if (appIn.hasRemaining()) {
                        val toRead = minOf(len, appIn.remaining())
                        appIn.get(b, off, toRead)
                        return toRead
                    }
                    appIn.clear()
                }
                SSLEngineResult.Status.CLOSED -> return -1
                SSLEngineResult.Status.BUFFER_UNDERFLOW -> appIn.clear()
                SSLEngineResult.Status.BUFFER_OVERFLOW -> {
                    val newBuf = ByteBuffer.allocate(appIn.capacity() * 2)
                    appIn.flip()
                    newBuf.put(appIn)
                    appIn = newBuf
                }
                else -> return -1
            }
        }
    }

    override fun close() {
        closedFlag.set(true)
        inboundPump.interrupt()
        outboundPump.interrupt()
    }
}

/**
 * TLS client-side OutputStream for tests.
 *
 * Pushes ciphertext onto a [LinkedBlockingQueue] drained by a dedicated pump
 * thread. No nested `runBlocking` on the caller's thread. See issue #223.
 */
internal class TlsClientOutputStream(
    private val engine: javax.net.ssl.SSLEngine,
    private val outbound: LinkedBlockingQueue<ByteArray>
) : OutputStream() {
    private val netOut = ByteBuffer.allocate(engine.session.packetBufferSize)

    override fun write(b: Int) {
        write(byteArrayOf(b.toByte()), 0, 1)
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        val appOut = ByteBuffer.wrap(b, off, len)
        while (appOut.hasRemaining()) {
            netOut.clear()
            val result = engine.wrap(appOut, netOut)
            netOut.flip()
            if (netOut.hasRemaining()) {
                val data = ByteArray(netOut.remaining())
                netOut.get(data)
                outbound.put(data)
            }
            if (result.status != SSLEngineResult.Status.OK) {
                throw java.io.IOException("TLS wrap failed: ${result.status}")
            }
        }
    }
}
