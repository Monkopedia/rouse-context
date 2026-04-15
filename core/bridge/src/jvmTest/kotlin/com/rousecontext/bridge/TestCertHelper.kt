package com.rousecontext.bridge

import com.rousecontext.tunnel.MuxStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.KeyStore
import java.security.cert.X509Certificate
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

    return Pair(
        TlsClientInputStream(sslEngine, clientStream, netIn),
        TlsClientOutputStream(sslEngine, clientStream)
    )
}

private fun ensureCapacity(buffer: ByteBuffer, additionalBytes: Int): ByteBuffer {
    if (buffer.remaining() >= additionalBytes) return buffer
    val newBuffer = ByteBuffer.allocate(buffer.position() + additionalBytes)
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
    private var netIn: ByteBuffer
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

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (appIn.hasRemaining()) {
            val toRead = minOf(len, appIn.remaining())
            appIn.get(b, off, toRead)
            return toRead
        }
        appIn.clear()
        while (true) {
            val tlsData = try {
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
}

/**
 * TLS client-side OutputStream for tests.
 */
internal class TlsClientOutputStream(
    private val engine: javax.net.ssl.SSLEngine,
    private val stream: MuxStream
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
                kotlinx.coroutines.runBlocking { stream.send(data) }
            }
            if (result.status != SSLEngineResult.Status.OK) {
                throw java.io.IOException("TLS wrap failed: ${result.status}")
            }
        }
    }
}
