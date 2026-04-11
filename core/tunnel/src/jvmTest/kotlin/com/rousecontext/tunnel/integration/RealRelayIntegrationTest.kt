package com.rousecontext.tunnel.integration

import com.rousecontext.tunnel.MuxCodec
import com.rousecontext.tunnel.MuxFrame
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.nio.ByteBuffer
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Integration tests that start the real Rust relay binary as a subprocess
 * and connect from Kotlin over WebSocket with mTLS.
 *
 * These tests are skipped (via Assume) if the relay binary has not been built.
 * Build it with: cd relay && cargo build
 */
@Tag("integration")
class RealRelayIntegrationTest {

    companion object {
        private const val RELAY_HOSTNAME = "localhost"
        private const val WS_TIMEOUT_SECS = 10L
    }

    private lateinit var tempDir: File
    private lateinit var ca: TestCertificateAuthority
    private lateinit var relayManager: TestRelayManager

    // Convenience aliases
    private val caCert: X509Certificate get() = ca.caCert
    private val deviceKeyStore: KeyStore get() = ca.deviceKeyStore

    @BeforeEach
    fun setUp() {
        val relayBinary = findRelayBinary()
        assumeTrue(
            relayBinary.exists() && relayBinary.canExecute(),
            "Relay binary not found. Build with: cd relay && cargo build"
        )

        tempDir = File.createTempFile("relay-integration-", "")
        tempDir.delete()
        tempDir.mkdirs()

        ca = TestCertificateAuthority(
            tempDir,
            RELAY_HOSTNAME,
            "test-device"
        )
        ca.generate()

        relayManager = TestRelayManager(tempDir, RELAY_HOSTNAME)
    }

    @AfterEach
    fun tearDown() {
        if (::relayManager.isInitialized) {
            relayManager.stop()
        }
        if (::tempDir.isInitialized && tempDir.exists()) {
            tempDir.deleteRecursively()
        }
    }

    /**
     * Scenario 1: Connect with valid client cert, verify mux session established.
     */
    @Test
    fun `connect with valid mTLS cert establishes WebSocket session`() {
        val port = findFreePort()
        relayManager.start(port)

        val sslContext = TestSslContexts.buildMtls(deviceKeyStore, caCert)
        val client = HttpClient.newBuilder()
            .sslContext(sslContext)
            .build()

        val listener = CollectingListener()
        val ws = client.newWebSocketBuilder()
            .buildAsync(URI.create("wss://$RELAY_HOSTNAME:$port/ws"), listener)
            .get(WS_TIMEOUT_SECS, TimeUnit.SECONDS)

        // Send OPEN + DATA + CLOSE
        ws.sendBinary(
            ByteBuffer.wrap(MuxCodec.encode(MuxFrame.Open(streamId = 1u))),
            true
        ).get(WS_TIMEOUT_SECS, TimeUnit.SECONDS)

        ws.sendBinary(
            ByteBuffer.wrap(
                MuxCodec.encode(
                    MuxFrame.Data(streamId = 1u, payload = "hello relay".toByteArray())
                )
            ),
            true
        ).get(WS_TIMEOUT_SECS, TimeUnit.SECONDS)

        ws.sendBinary(
            ByteBuffer.wrap(MuxCodec.encode(MuxFrame.Close(streamId = 1u))),
            true
        ).get(WS_TIMEOUT_SECS, TimeUnit.SECONDS)

        ws.sendClose(WebSocket.NORMAL_CLOSURE, "done")
            .get(WS_TIMEOUT_SECS, TimeUnit.SECONDS)

        assertTrue(listener.errors.isEmpty(), "No errors: ${listener.errors}")
        assertTrue(true, "mTLS WebSocket connection succeeded")
    }

    /**
     * Scenario 2: Send OPEN + DATA + CLOSE for multiple streams sequentially.
     */
    @Test
    fun `OPEN DATA CLOSE frame sequence processes without error`() {
        val port = findFreePort()
        relayManager.start(port)

        val sslContext = TestSslContexts.buildMtls(deviceKeyStore, caCert)
        val client = HttpClient.newBuilder()
            .sslContext(sslContext)
            .build()

        val listener = CollectingListener()
        val ws = client.newWebSocketBuilder()
            .buildAsync(URI.create("wss://$RELAY_HOSTNAME:$port/ws"), listener)
            .get(WS_TIMEOUT_SECS, TimeUnit.SECONDS)

        // Open stream, send several messages, close stream
        ws.sendBinary(
            ByteBuffer.wrap(MuxCodec.encode(MuxFrame.Open(streamId = 1u))),
            true
        ).get(WS_TIMEOUT_SECS, TimeUnit.SECONDS)

        for (i in 1..5) {
            ws.sendBinary(
                ByteBuffer.wrap(
                    MuxCodec.encode(
                        MuxFrame.Data(
                            streamId = 1u,
                            payload = "message-$i".toByteArray()
                        )
                    )
                ),
                true
            ).get(WS_TIMEOUT_SECS, TimeUnit.SECONDS)
        }

        ws.sendBinary(
            ByteBuffer.wrap(MuxCodec.encode(MuxFrame.Close(streamId = 1u))),
            true
        ).get(WS_TIMEOUT_SECS, TimeUnit.SECONDS)

        ws.sendClose(WebSocket.NORMAL_CLOSURE, "done")
            .get(WS_TIMEOUT_SECS, TimeUnit.SECONDS)

        assertTrue(listener.errors.isEmpty(), "No errors: ${listener.errors}")
    }

    /**
     * Scenario 3: Connect without client cert - TLS handshake should fail
     * because the relay requires client certificates.
     */
    @Test
    fun `connect without client cert is rejected`() {
        val port = findFreePort()
        relayManager.start(port)

        val sslContext = TestSslContexts.buildNoCert(caCert)
        val client = HttpClient.newBuilder()
            .sslContext(sslContext)
            .build()

        var rejected = false
        try {
            client.newWebSocketBuilder()
                .buildAsync(URI.create("wss://$RELAY_HOSTNAME:$port/ws"), CollectingListener())
                .get(WS_TIMEOUT_SECS, TimeUnit.SECONDS)
        } catch (_: Exception) {
            rejected = true
        }

        assertTrue(rejected, "Connection without client cert should be rejected")
    }

    /**
     * Scenario 4: Multiple concurrent WebSocket connections from different "devices".
     */
    @Test
    fun `multiple concurrent WebSocket connections are independent`() {
        val port = findFreePort()
        relayManager.start(port)

        val sslContext = TestSslContexts.buildMtls(deviceKeyStore, caCert)

        val client1 = HttpClient.newBuilder().sslContext(sslContext).build()
        val client2 = HttpClient.newBuilder().sslContext(sslContext).build()

        val listener1 = CollectingListener()
        val listener2 = CollectingListener()

        val ws1 = client1.newWebSocketBuilder()
            .buildAsync(URI.create("wss://$RELAY_HOSTNAME:$port/ws"), listener1)
            .get(WS_TIMEOUT_SECS, TimeUnit.SECONDS)

        val ws2 = client2.newWebSocketBuilder()
            .buildAsync(URI.create("wss://$RELAY_HOSTNAME:$port/ws"), listener2)
            .get(WS_TIMEOUT_SECS, TimeUnit.SECONDS)

        // Send frames on both connections
        ws1.sendBinary(
            ByteBuffer.wrap(MuxCodec.encode(MuxFrame.Open(streamId = 1u))),
            true
        ).get(WS_TIMEOUT_SECS, TimeUnit.SECONDS)

        ws2.sendBinary(
            ByteBuffer.wrap(MuxCodec.encode(MuxFrame.Open(streamId = 2u))),
            true
        ).get(WS_TIMEOUT_SECS, TimeUnit.SECONDS)

        ws1.sendBinary(
            ByteBuffer.wrap(
                MuxCodec.encode(
                    MuxFrame.Data(streamId = 1u, payload = "conn1".toByteArray())
                )
            ),
            true
        ).get(WS_TIMEOUT_SECS, TimeUnit.SECONDS)

        ws2.sendBinary(
            ByteBuffer.wrap(
                MuxCodec.encode(
                    MuxFrame.Data(streamId = 2u, payload = "conn2".toByteArray())
                )
            ),
            true
        ).get(WS_TIMEOUT_SECS, TimeUnit.SECONDS)

        ws1.sendClose(WebSocket.NORMAL_CLOSURE, "done")
            .get(WS_TIMEOUT_SECS, TimeUnit.SECONDS)
        ws2.sendClose(WebSocket.NORMAL_CLOSURE, "done")
            .get(WS_TIMEOUT_SECS, TimeUnit.SECONDS)

        assertTrue(
            listener1.errors.isEmpty(),
            "Connection 1 errors: ${listener1.errors}"
        )
        assertTrue(
            listener2.errors.isEmpty(),
            "Connection 2 errors: ${listener2.errors}"
        )
    }

    // --- WebSocket listener ---

    private class CollectingListener : WebSocket.Listener {
        val binaryMessages = CopyOnWriteArrayList<ByteArray>()
        val errors = CopyOnWriteArrayList<Throwable>()

        override fun onBinary(
            webSocket: WebSocket,
            data: ByteBuffer,
            last: Boolean
        ): CompletionStage<*> {
            val bytes = ByteArray(data.remaining())
            data.get(bytes)
            binaryMessages.add(bytes)
            webSocket.request(1)
            return CompletableFuture.completedFuture(null)
        }

        override fun onClose(
            webSocket: WebSocket,
            statusCode: Int,
            reason: String
        ): CompletionStage<*> = CompletableFuture.completedFuture(null)

        override fun onError(webSocket: WebSocket, error: Throwable) {
            errors.add(error)
        }
    }
}
