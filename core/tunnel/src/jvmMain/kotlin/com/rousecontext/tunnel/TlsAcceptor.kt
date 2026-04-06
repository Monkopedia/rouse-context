package com.rousecontext.tunnel

import java.io.InputStream
import java.io.OutputStream
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
import kotlinx.coroutines.withContext

/**
 * Performs TLS server-side accept over a [MuxStream].
 *
 * The device acts as the TLS server (it holds the certificate for its subdomain).
 * The MCP client connecting through the relay is the TLS client.
 * After the handshake completes, plaintext bytes flow through the returned streams.
 */
class TlsAcceptor(
    private val sslContext: SSLContext
) {
    /**
     * Result of a successful TLS accept: plaintext I/O streams.
     */
    data class TlsSession(
        val input: InputStream,
        val output: OutputStream
    )

    /**
     * Perform TLS server-side handshake over the given [MuxStream].
     * Returns plaintext I/O streams on success.
     *
     * @throws TunnelError.TlsHandshakeFailed if handshake fails
     */
    suspend fun accept(stream: MuxStream): TlsSession = withContext(Dispatchers.IO) {
        try {
            val engine = sslContext.createSSLEngine()
            engine.useClientMode = false

            val session = engine.session
            val appBufferSize = session.applicationBufferSize
            val netBufferSize = session.packetBufferSize

            var appIn = java.nio.ByteBuffer.allocate(appBufferSize)
            val appOut = java.nio.ByteBuffer.allocate(0) // empty: no app data during handshake
            var netIn = java.nio.ByteBuffer.allocate(netBufferSize)
            var netOut = java.nio.ByteBuffer.allocate(netBufferSize)

            engine.beginHandshake()

            // Pump handshake to completion
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
                            val data = ByteArray(netOut.remaining())
                            netOut.get(data)
                            stream.write(data)
                        }
                    }
                    SSLEngineResult.HandshakeStatus.NEED_UNWRAP -> {
                        // Only read from the stream if netIn is empty.
                        // Multiple TLS records may arrive in a single mux DATA frame,
                        // so netIn may still have data from a previous read.
                        if (netIn.position() == 0) {
                            val tlsData = stream.read()
                            netIn = ensureCapacity(netIn, tlsData.size)
                            netIn.put(tlsData)
                        }
                        netIn.flip()
                        val result = engine.unwrap(netIn, appIn)
                        hsStatus = result.handshakeStatus
                        netIn.compact()
                    }
                    SSLEngineResult.HandshakeStatus.NEED_TASK -> {
                        var task = engine.delegatedTask
                        while (task != null) {
                            task.run()
                            task = engine.delegatedTask
                        }
                        hsStatus = engine.handshakeStatus
                    }
                    else -> break
                }
            }

            TlsSession(
                input = TlsInputStream(engine, stream, netIn),
                output = TlsOutputStream(engine, stream)
            )
        } catch (e: TunnelError) {
            throw e
        } catch (e: Exception) {
            throw TunnelError.TlsHandshakeFailed("TLS handshake failed", e)
        }
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

/**
 * An [InputStream] that decrypts TLS data read from a [MuxStream].
 */
internal class TlsInputStream(
    private val engine: SSLEngine,
    private val stream: MuxStream,
    private var netIn: java.nio.ByteBuffer
) : InputStream() {
    private var appIn = java.nio.ByteBuffer.allocate(engine.session.applicationBufferSize)
    init {
        appIn.flip() // start empty
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

        // Need to decrypt more data
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
                SSLEngineResult.Status.OK -> {
                    appIn.flip()
                    if (appIn.hasRemaining()) {
                        val toRead = minOf(len, appIn.remaining())
                        appIn.get(b, off, toRead)
                        return toRead
                    }
                    appIn.clear()
                    // May need more data, loop
                }
                SSLEngineResult.Status.CLOSED -> return -1
                SSLEngineResult.Status.BUFFER_UNDERFLOW -> {
                    // Need more network data, loop
                    appIn.clear()
                }
                SSLEngineResult.Status.BUFFER_OVERFLOW -> {
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
 * An [OutputStream] that encrypts plaintext and writes TLS data to a [MuxStream].
 */
internal class TlsOutputStream(
    private val engine: SSLEngine,
    private val stream: MuxStream
) : OutputStream() {
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
                kotlinx.coroutines.runBlocking { stream.write(data) }
            }
            if (result.status != SSLEngineResult.Status.OK) {
                throw java.io.IOException("TLS wrap failed: ${result.status}")
            }
        }
    }
}
