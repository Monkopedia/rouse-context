package com.rousecontext.tunnel

import java.security.KeyFactory
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLEngineResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Performs TLS server-side accept over a [MuxStream].
 *
 * The device acts as the TLS server (it holds the certificate for its subdomain).
 * The MCP client connecting through the relay is the TLS client.
 * After the handshake completes, plaintext bytes flow through the returned [TlsSession]
 * via suspend-native read/write calls -- no Java [java.io.InputStream]/[java.io.OutputStream]
 * is exposed, so no `runBlocking` bridge is required.
 */
class TlsAcceptor(private val sslContext: SSLContext) {
    /**
     * Result of a successful TLS accept: suspend-native plaintext I/O.
     *
     * Implementations are thread-safe for independent concurrent read and write,
     * but concurrent reads (or concurrent writes) are serialized internally.
     */
    interface TlsSession {
        /**
         * Reads plaintext bytes into [buf] starting at [off] for up to [len] bytes.
         *
         * @return number of bytes read, or -1 on EOF
         */
        suspend fun read(buf: ByteArray, off: Int = 0, len: Int = buf.size - off): Int

        /**
         * Writes [len] plaintext bytes from [buf] starting at [off], encrypting them
         * to the underlying mux stream.
         */
        suspend fun write(buf: ByteArray, off: Int = 0, len: Int = buf.size - off)

        /**
         * Closes the TLS session and the underlying mux stream.
         */
        suspend fun close()
    }

    /**
     * Perform TLS server-side handshake over the given [MuxStream].
     * Returns a suspend-native [TlsSession] on success.
     *
     * @throws TunnelError.TlsHandshakeFailed if handshake fails
     */
    suspend fun accept(stream: MuxStream): TlsSession = withContext(Dispatchers.IO) {
        try {
            val engine = createServerEngine()
            SuspendTlsSession(engine, stream, pumpHandshake(engine, stream))
        } catch (e: TunnelError) {
            throw e
        } catch (e: Exception) {
            throw TunnelError.TlsHandshakeFailed("TLS handshake failed", e)
        }
    }

    /**
     * Drive the server-side handshake to completion, returning the leftover
     * encrypted bytes (in "compact" mode) for the resulting session to consume.
     */
    private suspend fun pumpHandshake(engine: SSLEngine, stream: MuxStream): java.nio.ByteBuffer {
        val session = engine.session
        val appIn = java.nio.ByteBuffer.allocate(session.applicationBufferSize)
        val appOut = java.nio.ByteBuffer.allocate(0) // empty: no app data during handshake
        var netIn = java.nio.ByteBuffer.allocate(session.packetBufferSize)
        val netOut = java.nio.ByteBuffer.allocate(session.packetBufferSize)

        // Set when the previous unwrap reported BUFFER_UNDERFLOW: netIn holds a
        // PARTIAL TLS record, so the next iteration must pull another mux DATA
        // frame even though netIn is non-empty. See the NEED_UNWRAP note.
        var needMoreNetData = false

        engine.beginHandshake()
        var hsStatus = engine.handshakeStatus
        while (hsStatus != SSLEngineResult.HandshakeStatus.FINISHED &&
            hsStatus != SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING
        ) {
            when (hsStatus) {
                SSLEngineResult.HandshakeStatus.NEED_WRAP -> {
                    netOut.clear()
                    val result = engine.wrap(appOut, netOut)
                    hsStatus = result.handshakeStatus
                    netOut.flip()
                    if (netOut.hasRemaining()) {
                        stream.write(drain(netOut))
                    }
                }
                SSLEngineResult.HandshakeStatus.NEED_UNWRAP -> {
                    // Read from the stream when netIn has nothing left to unwrap,
                    // OR when the last unwrap underflowed. Multiple TLS records
                    // may arrive in ONE mux DATA frame (so we must not read while
                    // netIn still holds whole records), and ONE TLS record may be
                    // SPLIT across two frames (so we must read when netIn holds
                    // only a partial record -- otherwise unwrap underflows forever
                    // on the same bytes, spinning at 100% CPU). See #558.
                    if (netIn.position() == 0 || needMoreNetData) {
                        netIn = appendTo(netIn, stream.read())
                    }
                    netIn.flip()
                    val result = engine.unwrap(netIn, appIn)
                    netIn.compact()
                    hsStatus = result.handshakeStatus
                    needMoreNetData =
                        result.status == SSLEngineResult.Status.BUFFER_UNDERFLOW
                }
                SSLEngineResult.HandshakeStatus.NEED_TASK -> {
                    runDelegatedTasks(engine)
                    hsStatus = engine.handshakeStatus
                }
                else -> break
            }
        }
        return netIn
    }

    private fun runDelegatedTasks(engine: SSLEngine) {
        var task = engine.delegatedTask
        while (task != null) {
            task.run()
            task = engine.delegatedTask
        }
    }

    /**
     * Build the server-side [SSLEngine] with ALPN advertising HTTP/1.1.
     * Without ALPN, some HTTP clients complete TLS but never send HTTP
     * data because no application protocol was agreed.
     */
    private fun createServerEngine(): SSLEngine {
        val engine = sslContext.createSSLEngine()
        engine.useClientMode = false
        val sslParams = engine.sslParameters
        sslParams.applicationProtocols = arrayOf("http/1.1")
        engine.sslParameters = sslParams
        return engine
    }

    companion object {
        /**
         * Create a [TlsAcceptor] from a JVM [SSLContext].
         */
        fun create(sslContext: SSLContext): TlsAcceptor = TlsAcceptor(sslContext)

        /**
         * Create a [TlsAcceptor] from certificate and private key DER bytes.
         */
        fun fromCertAndKey(
            certDer: ByteArray,
            keyDer: ByteArray,
            keyAlgorithm: String = "RSA"
        ): TlsAcceptor {
            val certFactory = CertificateFactory.getInstance("X.509")
            val cert = certFactory.generateCertificate(certDer.inputStream()) as X509Certificate

            val keyFactory = KeyFactory.getInstance(keyAlgorithm)
            val privateKey = keyFactory.generatePrivate(PKCS8EncodedKeySpec(keyDer))

            val keyStore = KeyStore.getInstance(KeyStore.getDefaultType())
            keyStore.load(null, null)
            keyStore.setKeyEntry("device", privateKey, charArrayOf(), arrayOf(cert))

            val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            kmf.init(keyStore, charArrayOf())

            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(kmf.keyManagers, null, null)

            return TlsAcceptor(sslContext)
        }

        /**
         * Create a [TlsAcceptor] from PEM-encoded certificate chain and private key.
         *
         * @param certPem PEM-encoded certificate chain (may contain multiple certs)
         * @param keyPem PEM-encoded PKCS#8 private key
         */
        fun fromPem(certPem: String, keyPem: String): TlsAcceptor {
            val certFactory = CertificateFactory.getInstance("X.509")
            val certs = parsePemCertificates(certFactory, certPem)
            require(certs.isNotEmpty()) { "No certificates found in PEM" }

            val privateKey = parsePemPrivateKey(keyPem)

            val keyStore = KeyStore.getInstance(KeyStore.getDefaultType())
            keyStore.load(null, null)
            keyStore.setKeyEntry(
                "device",
                privateKey,
                charArrayOf(),
                certs.toTypedArray()
            )

            val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            kmf.init(keyStore, charArrayOf())

            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(kmf.keyManagers, null, null)

            return TlsAcceptor(sslContext)
        }

        private fun parsePemCertificates(
            factory: CertificateFactory,
            pem: String
        ): List<X509Certificate> {
            val regex = Regex(
                "-----BEGIN CERTIFICATE-----(.+?)-----END CERTIFICATE-----",
                RegexOption.DOT_MATCHES_ALL
            )
            return regex.findAll(pem).map { match ->
                val base64 = match.groupValues[1].replace("\\s".toRegex(), "")
                val der = java.util.Base64.getDecoder().decode(base64)
                factory.generateCertificate(der.inputStream()) as X509Certificate
            }.toList()
        }

        private fun parsePemPrivateKey(pem: String): java.security.PrivateKey {
            // Support PKCS#8 ("PRIVATE KEY") and EC/RSA-specific headers
            val pattern =
                "-----BEGIN (?:RSA |EC )?PRIVATE KEY-----" +
                    "(.+?)" +
                    "-----END (?:RSA |EC )?PRIVATE KEY-----"
            val regex = Regex(pattern, RegexOption.DOT_MATCHES_ALL)
            val match = regex.find(pem)
                ?: throw IllegalArgumentException("No private key found in PEM")
            val base64 = match.groupValues[1].replace("\\s".toRegex(), "")
            val keyBytes = java.util.Base64.getDecoder().decode(base64)

            // Try EC first (our ACME keys are EC P-256), then RSA
            return try {
                val keyFactory = KeyFactory.getInstance("EC")
                keyFactory.generatePrivate(PKCS8EncodedKeySpec(keyBytes))
            } catch (_: Exception) {
                val keyFactory = KeyFactory.getInstance("RSA")
                keyFactory.generatePrivate(PKCS8EncodedKeySpec(keyBytes))
            }
        }
    }
}

private fun ensureCapacity(buffer: java.nio.ByteBuffer, additionalBytes: Int): java.nio.ByteBuffer {
    if (buffer.remaining() >= additionalBytes) return buffer
    val newBuffer = java.nio.ByteBuffer.allocate(buffer.position() + additionalBytes)
    buffer.flip()
    newBuffer.put(buffer)
    return newBuffer
}

/** Append [data] to a "compact"-mode buffer, growing it if necessary. */
private fun appendTo(buffer: java.nio.ByteBuffer, data: ByteArray): java.nio.ByteBuffer =
    ensureCapacity(buffer, data.size).also { it.put(data) }

/** Copy out a flipped buffer's readable region. */
private fun drain(buffer: java.nio.ByteBuffer): ByteArray =
    ByteArray(buffer.remaining()).also { buffer.get(it) }

/**
 * A suspend-native [TlsAcceptor.TlsSession] that encrypts/decrypts plaintext against
 * an underlying [MuxStream]. Reads and writes are serialized with separate mutexes
 * so one direction may proceed while the other is suspended.
 */
@Suppress("TooManyFunctions")
private class SuspendTlsSession(
    private val engine: SSLEngine,
    private val stream: MuxStream,
    initialNetIn: java.nio.ByteBuffer
) : TlsAcceptor.TlsSession {

    private val readMutex = Mutex()
    private val writeMutex = Mutex()

    // Encrypted bytes received from the mux stream but not yet unwrapped.
    // Kept in "compact" mode (position = write pointer).
    private var netIn: java.nio.ByteBuffer = initialNetIn

    // Decrypted plaintext ready to hand back to the caller. Kept in "read" mode
    // (flipped -- position..limit is the readable region).
    private var appIn: java.nio.ByteBuffer =
        java.nio.ByteBuffer.allocate(engine.session.applicationBufferSize).also { it.flip() }

    private val netOut: java.nio.ByteBuffer =
        java.nio.ByteBuffer.allocate(engine.session.packetBufferSize)

    @Volatile
    private var eof: Boolean = false

    @Suppress("LoopWithTooManyJumpStatements")
    override suspend fun read(buf: ByteArray, off: Int, len: Int): Int = readMutex.withLock {
        if (eof) return@withLock -1
        if (appIn.hasRemaining()) {
            return@withLock takeFromAppIn(buf, off, len)
        }

        appIn.clear()
        // Set when the previous unwrap reported BUFFER_UNDERFLOW: netIn holds a
        // PARTIAL TLS record and the next iteration MUST pull another mux DATA
        // frame even though netIn is non-empty. Without this the loop unwraps the
        // same partial record forever at 100% CPU and the peer blocks until its
        // socket read timeout fires. See #558.
        var needMoreNetData = false
        while (true) {
            // Read from the stream when netIn has nothing left to unwrap, OR when
            // the last unwrap underflowed. Multiple TLS records may arrive in ONE
            // mux DATA frame, and ONE TLS record may be SPLIT across two frames.
            if ((netIn.position() == 0 || needMoreNetData) && !pullNetData()) {
                eof = true
                return@withLock -1
            }
            needMoreNetData = false
            netIn.flip()

            val result = engine.unwrap(netIn, appIn)
            netIn.compact()

            when (result.status) {
                SSLEngineResult.Status.OK -> {
                    appIn.flip()
                    if (appIn.hasRemaining()) {
                        return@withLock takeFromAppIn(buf, off, len)
                    }
                    appIn.clear()
                    // May need more data, loop
                }
                SSLEngineResult.Status.CLOSED -> {
                    eof = true
                    return@withLock -1
                }
                SSLEngineResult.Status.BUFFER_UNDERFLOW -> {
                    // netIn holds only a partial record: force a stream read on
                    // the next iteration instead of re-unwrapping the same bytes.
                    needMoreNetData = true
                    appIn.clear()
                }
                SSLEngineResult.Status.BUFFER_OVERFLOW -> {
                    // Grow the app buffer and retry unwrap immediately
                    // (netIn still has the data that caused overflow)
                    val newBuf = java.nio.ByteBuffer.allocate(appIn.capacity() * 2)
                    appIn.flip()
                    newBuf.put(appIn)
                    appIn = newBuf
                    continue
                }
                else -> {
                    eof = true
                    return@withLock -1
                }
            }
        }
        @Suppress("UNREACHABLE_CODE")
        -1
    }

    /** Hand back up to [len] plaintext bytes already decoded into [appIn]. */
    private fun takeFromAppIn(buf: ByteArray, off: Int, len: Int): Int {
        val toRead = minOf(len, appIn.remaining())
        appIn.get(buf, off, toRead)
        return toRead
    }

    /** Pull one more mux DATA frame into [netIn]. Returns false on EOF/error. */
    private suspend fun pullNetData(): Boolean {
        val tlsData = try {
            stream.read()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            return false
        }
        netIn = appendTo(netIn, tlsData)
        return true
    }

    override suspend fun write(buf: ByteArray, off: Int, len: Int) = writeMutex.withLock {
        val appOut = java.nio.ByteBuffer.wrap(buf, off, len)
        while (appOut.hasRemaining()) {
            netOut.clear()
            val result = engine.wrap(appOut, netOut)
            netOut.flip()
            if (netOut.hasRemaining()) {
                val data = ByteArray(netOut.remaining())
                netOut.get(data)
                try {
                    stream.write(data)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    throw java.io.IOException("TLS write failed: stream closed", e)
                }
            }
            if (result.status != SSLEngineResult.Status.OK) {
                throw java.io.IOException("TLS wrap failed: ${result.status}")
            }
        }
    }

    override suspend fun close() {
        eof = true
        try {
            stream.close()
        } catch (_: Exception) {
            // Best effort
        }
    }
}
