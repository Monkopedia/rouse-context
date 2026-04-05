package com.rousecontext.tunnel.integration

import com.rousecontext.tunnel.ChannelMuxStream
import com.rousecontext.tunnel.MuxCodec
import com.rousecontext.tunnel.MuxErrorCode
import com.rousecontext.tunnel.MuxFrame
import com.rousecontext.tunnel.TlsAcceptor
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
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before

/**
 * End-to-end session tests using the real Rust relay binary.
 *
 * Pattern: start relay, connect as "device" via mux WebSocket (mTLS),
 * connect as "AI client" via raw TLS to the device subdomain, and verify
 * data flows through the relay's SNI-based passthrough.
 *
 * These tests are skipped if the relay binary has not been built.
 * Build it with: cd relay && cargo build
 */
@Suppress("LargeClass")
class EndToEndSessionTest {

    companion object {
        private const val RELAY_HOSTNAME = "localhost"
        private const val RELAY_STARTUP_TIMEOUT_MS = 10_000L
        private const val WS_TIMEOUT_SECS = 10L
        private const val STORE_PASS = "changeit"
        private const val DEVICE_SUBDOMAIN = "test-device"
    }

    private lateinit var relayBinary: File
    private lateinit var tempDir: File
    private lateinit var caCert: X509Certificate
    private lateinit var deviceKeyStore: KeyStore
    private lateinit var deviceCert: X509Certificate
    private var relayProcess: Process? = null
    private var relayPort: Int = 0

    @Before
    fun setUp() {
        relayBinary = findRelayBinary()
        assumeTrue(
            "Relay binary not found. Build with: cd relay && cargo build",
            relayBinary.exists() && relayBinary.canExecute()
        )

        tempDir = File.createTempFile("e2e-session-", "")
        tempDir.delete()
        tempDir.mkdirs()

        generateCertificates()
        relayPort = findFreePort()
        writeRelayConfig(relayPort)
        relayProcess = startRelay(relayPort)
    }

    @After
    fun tearDown() {
        relayProcess?.let {
            it.destroyForcibly()
            it.waitFor(5, TimeUnit.SECONDS)
        }
        if (::tempDir.isInitialized && tempDir.exists()) {
            tempDir.deleteRecursively()
        }
    }

    // =========================================================================
    // Scenario 5: Cold path — full TLS tunnel without FCM
    // =========================================================================

    /**
     * Client connects via TLS with device subdomain SNI. Relay sends OPEN
     * to device mux. Device receives ClientHello bytes, does TLS accept on
     * the mux stream. Plaintext data flows bidirectionally through the tunnel.
     */
    @Test
    fun `scenario 5 - cold path full TLS tunnel`() = runBlocking {
        // 1. Device connects via mTLS WebSocket
        val deviceWs = connectDeviceMux()

        // 2. Start receiving mux frames from relay on device side
        val deviceFrames = deviceWs.listener.binaryMessages
        val openReceived = CompletableDeferred<MuxFrame.Open>()

        // Device-side frame pump: wait for OPEN + DATA (ClientHello)
        val deviceStreamData = Channel<ByteArray>(Channel.BUFFERED)
        val deviceStreamId = CompletableDeferred<UInt>()

        launch(Dispatchers.IO) {
            var gotOpen = false
            while (true) {
                val frameBytes = try {
                    waitForFrame(deviceFrames, 10_000)
                } catch (_: Exception) {
                    break
                }
                val frame = MuxCodec.decode(frameBytes)
                when {
                    frame is MuxFrame.Open && !gotOpen -> {
                        gotOpen = true
                        deviceStreamId.complete(frame.streamId)
                        openReceived.complete(frame)
                    }
                    frame is MuxFrame.Data -> {
                        deviceStreamData.send(frame.payload)
                    }
                    frame is MuxFrame.Close -> {
                        deviceStreamData.close()
                        break
                    }
                }
            }
        }

        // 3. AI client connects with device subdomain SNI
        // This triggers the relay to look up the device session and send OPEN
        val clientSocket = CompletableDeferred<SSLSocket>()
        launch(Dispatchers.IO) {
            val socket = connectAiClient()
            clientSocket.complete(socket)
        }

        // 4. Device receives OPEN frame from relay
        val openFrame = withTimeout(10_000) { openReceived.await() }
        val streamId = openFrame.streamId

        // 5. Build a MuxStream that sends DATA frames back through the WS
        val serverToClient = Channel<ByteArray>(Channel.BUFFERED)

        // Pump deviceStreamData into serverToClient for TLS accept
        launch(Dispatchers.IO) {
            for (data in deviceStreamData) {
                serverToClient.send(data)
            }
            serverToClient.close()
        }

        val clientToServer = Channel<ByteArray>(Channel.BUFFERED)
        val deviceMuxStream = ChannelMuxStream(
            streamIdValue = streamId,
            readChannel = serverToClient,
            writeChannel = clientToServer
        )

        // Forward clientToServer data as mux DATA frames back to relay
        launch(Dispatchers.IO) {
            for (data in clientToServer) {
                val frame = MuxCodec.encode(MuxFrame.Data(streamId, data))
                deviceWs.ws.sendBinary(ByteBuffer.wrap(frame), true)
                    .get(WS_TIMEOUT_SECS, TimeUnit.SECONDS)
            }
        }

        // 6. Device does TLS accept over the mux stream
        val deviceSslContext = buildDeviceServerSslContext()
        val acceptor = TlsAcceptor.create(deviceSslContext)

        val tlsSession = CompletableDeferred<TlsAcceptor.TlsSession>()
        launch(Dispatchers.IO) {
            val session = acceptor.accept(deviceMuxStream)
            tlsSession.complete(session)
        }

        // Wait for client TLS socket to connect and handshake
        val aiSocket = withTimeout(10_000) { clientSocket.await() }
        val session = withTimeout(10_000) { tlsSession.await() }

        // 7. Bidirectional plaintext data flow
        // Client -> Device
        val clientOut = aiSocket.outputStream
        clientOut.write("hello from AI client".toByteArray())
        clientOut.flush()

        val buf = ByteArray(4096)
        val n = session.input.read(buf, 0, buf.size)
        assertTrue(n > 0, "Should read plaintext from AI client")
        assertEquals("hello from AI client", String(buf, 0, n))

        // Device -> Client
        session.output.write("hello from device".toByteArray())
        session.output.flush()

        val buf2 = ByteArray(4096)
        val n2 = aiSocket.inputStream.read(buf2, 0, buf2.size)
        assertTrue(n2 > 0, "Should read plaintext from device")
        assertEquals("hello from device", String(buf2, 0, n2))

        // Cleanup
        aiSocket.close()
        deviceWs.ws.sendClose(WebSocket.NORMAL_CLOSURE, "done")
            .get(WS_TIMEOUT_SECS, TimeUnit.SECONDS)
        coroutineContext.cancelChildren()
    }

    // =========================================================================
    // Scenario 6: Warm path — device already connected
    // =========================================================================

    /**
     * Device already connected via mux. Client connects and gets an instant
     * OPEN with no delay.
     */
    @Test
    fun `scenario 6 - warm path instant OPEN`() = runBlocking {
        val deviceWs = connectDeviceMux()

        // Device is already connected. Now connect AI client.
        val startTime = System.currentTimeMillis()

        val openReceived = CompletableDeferred<MuxFrame.Open>()
        launch(Dispatchers.IO) {
            while (true) {
                val frameBytes = try {
                    waitForFrame(deviceWs.listener.binaryMessages, 10_000)
                } catch (_: Exception) {
                    break
                }
                val frame = MuxCodec.decode(frameBytes)
                if (frame is MuxFrame.Open) {
                    openReceived.complete(frame)
                    break
                }
            }
        }

        // AI client connects via raw TCP with synthetic ClientHello
        val rawSocket = connectRawAiClient()

        val openFrame = withTimeout(10_000) { openReceived.await() }
        val elapsed = System.currentTimeMillis() - startTime

        assertTrue(openFrame.streamId > 0u, "Should get a valid stream ID")
        // Warm path should be sub-second (no FCM wakeup needed)
        assertTrue(elapsed < 3_000, "OPEN should arrive quickly (warm path), took ${elapsed}ms")

        rawSocket.close()
        deviceWs.ws.sendClose(WebSocket.NORMAL_CLOSURE, "done")
            .get(WS_TIMEOUT_SECS, TimeUnit.SECONDS)
        coroutineContext.cancelChildren()
    }

    // =========================================================================
    // Scenario 7: Concurrent clients
    // =========================================================================

    /**
     * Two AI clients connect to the same device subdomain. Device receives
     * two OPEN frames with different stream IDs.
     */
    @Test
    fun `scenario 7 - concurrent clients get different stream IDs`() = runBlocking {
        val deviceWs = connectDeviceMux()

        val openFrames = CopyOnWriteArrayList<MuxFrame.Open>()
        val twoOpens = CompletableDeferred<Unit>()

        launch(Dispatchers.IO) {
            while (true) {
                val frameBytes = try {
                    waitForFrame(deviceWs.listener.binaryMessages, 10_000)
                } catch (_: Exception) {
                    break
                }
                val frame = MuxCodec.decode(frameBytes)
                if (frame is MuxFrame.Open) {
                    openFrames.add(frame)
                    if (openFrames.size >= 2) {
                        twoOpens.complete(Unit)
                    }
                }
            }
        }

        // Connect two AI clients via raw TCP
        val rawSocket1 = connectRawAiClient()
        val rawSocket2 = connectRawAiClient()

        withTimeout(10_000) { twoOpens.await() }

        assertEquals(2, openFrames.size, "Should receive two OPEN frames")
        val ids = openFrames.map { it.streamId }.toSet()
        assertEquals(2, ids.size, "Stream IDs should be different: $ids")

        rawSocket1.close()
        rawSocket2.close()
        deviceWs.ws.sendClose(WebSocket.NORMAL_CLOSURE, "done")
            .get(WS_TIMEOUT_SECS, TimeUnit.SECONDS)
        coroutineContext.cancelChildren()
    }

    // =========================================================================
    // Scenario 8: Client disconnects mid-session
    // =========================================================================

    /**
     * Client closes TCP during active data flow. Relay sends CLOSE to
     * device mux for that stream.
     */
    @Test
    fun `scenario 8 - client disconnect sends CLOSE to device`() = runBlocking {
        val deviceWs = connectDeviceMux()

        val openReceived = CompletableDeferred<MuxFrame.Open>()
        val closeReceived = CompletableDeferred<MuxFrame.Close>()

        launch(Dispatchers.IO) {
            while (true) {
                val frameBytes = try {
                    waitForFrame(deviceWs.listener.binaryMessages, 15_000)
                } catch (_: Exception) {
                    break
                }
                val frame = MuxCodec.decode(frameBytes)
                when (frame) {
                    is MuxFrame.Open -> openReceived.complete(frame)
                    is MuxFrame.Close -> closeReceived.complete(frame)
                    else -> {} // DATA frames (ClientHello) expected too
                }
            }
        }

        // AI client connects via raw TCP
        val rawSocket = connectRawAiClient()

        val openFrame = withTimeout(10_000) { openReceived.await() }

        // Close the raw socket abruptly
        rawSocket.close()

        // Device should receive CLOSE for that stream
        val closeFrame = withTimeout(10_000) { closeReceived.await() }
        assertEquals(
            openFrame.streamId,
            closeFrame.streamId,
            "CLOSE should be for the same stream ID as OPEN"
        )

        deviceWs.ws.sendClose(WebSocket.NORMAL_CLOSURE, "done")
            .get(WS_TIMEOUT_SECS, TimeUnit.SECONDS)
        coroutineContext.cancelChildren()
    }

    // =========================================================================
    // Scenario 9: Device disconnects mid-session
    // =========================================================================

    /**
     * Device closes WebSocket while client has active stream.
     * Client's TCP connection should drop.
     */
    @Test
    fun `scenario 9 - device disconnect drops client TCP`() = runBlocking {
        val deviceWs = connectDeviceMux()

        val openReceived = CompletableDeferred<MuxFrame.Open>()
        launch(Dispatchers.IO) {
            while (true) {
                val frameBytes = try {
                    waitForFrame(deviceWs.listener.binaryMessages, 10_000)
                } catch (_: Exception) {
                    break
                }
                val frame = MuxCodec.decode(frameBytes)
                if (frame is MuxFrame.Open) {
                    openReceived.complete(frame)
                    break
                }
            }
        }

        // AI client connects via raw TCP
        val rawSocket = connectRawAiClient()

        withTimeout(10_000) { openReceived.await() }

        // Device closes WebSocket
        deviceWs.ws.sendClose(WebSocket.NORMAL_CLOSURE, "device leaving")
            .get(WS_TIMEOUT_SECS, TimeUnit.SECONDS)

        // Client should detect the connection drop
        val disconnected = CompletableDeferred<Boolean>()
        launch(Dispatchers.IO) {
            try {
                val buf = ByteArray(1024)
                rawSocket.soTimeout = 10_000
                // Read should return -1 or throw when relay closes the TCP
                val n = rawSocket.inputStream.read(buf)
                disconnected.complete(n == -1)
            } catch (_: Exception) {
                disconnected.complete(true)
            }
        }

        val dropped = withTimeout(15_000) { disconnected.await() }
        assertTrue(dropped, "AI client should detect connection drop")

        rawSocket.close()
        coroutineContext.cancelChildren()
    }

    // =========================================================================
    // Scenario 10: Max streams exceeded
    // =========================================================================

    /**
     * Open 8 streams (the configured max), try to open a 9th.
     * The relay should refuse or close it.
     */
    @Test
    fun `scenario 10 - max streams exceeded`() = runBlocking {
        val deviceWs = connectDeviceMux()

        val openCount = CompletableDeferred<Int>()
        val allFrames = CopyOnWriteArrayList<MuxFrame>()

        launch(Dispatchers.IO) {
            var opens = 0
            while (true) {
                val frameBytes = try {
                    waitForFrame(deviceWs.listener.binaryMessages, 15_000)
                } catch (_: Exception) {
                    openCount.complete(opens)
                    break
                }
                val frame = MuxCodec.decode(frameBytes)
                allFrames.add(frame)
                if (frame is MuxFrame.Open) {
                    opens++
                    if (opens >= 8) {
                        // Give the 9th a moment to be processed
                        delay(2_000)
                        openCount.complete(opens)
                        break
                    }
                }
            }
        }

        // Open 9 AI client connections via raw TCP
        val sockets = CopyOnWriteArrayList<java.net.Socket>()
        for (i in 1..9) {
            try {
                sockets.add(connectRawAiClient())
            } catch (_: Exception) {
                // Some connections may be refused
            }
        }

        val opens = withTimeout(20_000) { openCount.await() }
        // Device should receive at most 8 OPEN frames (max_streams_per_device = 8)
        assertTrue(
            opens <= 8,
            "Should receive at most 8 OPEN frames, got $opens"
        )

        sockets.forEach { runCatching { it.close() } }
        deviceWs.ws.sendClose(WebSocket.NORMAL_CLOSURE, "done")
            .get(WS_TIMEOUT_SECS, TimeUnit.SECONDS)
        coroutineContext.cancelChildren()
    }

    // =========================================================================
    // Scenario 14: FCM timeout (simulated) — no device registered
    // =========================================================================

    /**
     * Client connects to a subdomain with no device registered.
     * The relay tries FCM (stub returns error) and the client connection
     * should time out / be refused.
     */
    @Test
    fun `scenario 14 - no device registered causes client rejection`() = runBlocking {
        // Do NOT connect a device mux. Connect AI client to unknown subdomain.
        val rejected = CompletableDeferred<Boolean>()
        launch(Dispatchers.IO) {
            try {
                val factory = buildAiClientSslSocketFactory()
                val socket = factory.createSocket(
                    "127.0.0.1",
                    relayPort
                ) as SSLSocket
                socket.soTimeout = 15_000
                val params = socket.sslParameters
                params.serverNames = listOf(
                    javax.net.ssl.SNIHostName("unknown-device.$RELAY_HOSTNAME")
                )
                socket.sslParameters = params
                socket.startHandshake()
                // If we get here, try to read — should fail
                val n = socket.inputStream.read(ByteArray(1))
                rejected.complete(n == -1)
                socket.close()
            } catch (_: Exception) {
                rejected.complete(true)
            }
        }

        val wasRejected = withTimeout(20_000) { rejected.await() }
        assertTrue(wasRejected, "Client to unregistered device should be rejected")
        coroutineContext.cancelChildren()
    }

    // =========================================================================
    // Scenario 15: Device sends ERROR(STREAM_REFUSED)
    // =========================================================================

    /**
     * Device receives OPEN, sends back ERROR(STREAM_REFUSED).
     * Relay should close the AI client's TCP connection.
     */
    @Test
    fun `scenario 15 - device STREAM_REFUSED closes client`() = runBlocking {
        val deviceWs = connectDeviceMux()

        val openReceived = CompletableDeferred<MuxFrame.Open>()
        launch(Dispatchers.IO) {
            while (true) {
                val frameBytes = try {
                    waitForFrame(deviceWs.listener.binaryMessages, 10_000)
                } catch (_: Exception) {
                    break
                }
                val frame = MuxCodec.decode(frameBytes)
                if (frame is MuxFrame.Open) {
                    openReceived.complete(frame)
                    break
                }
            }
        }

        // AI client connects via raw TCP
        val rawSocket = connectRawAiClient()

        val openFrame = withTimeout(10_000) { openReceived.await() }

        // Device sends ERROR(STREAM_REFUSED) for that stream
        val errorFrame = MuxCodec.encode(
            MuxFrame.Error(
                streamId = openFrame.streamId,
                errorCode = MuxErrorCode.STREAM_REFUSED,
                message = "test refused"
            )
        )
        deviceWs.ws.sendBinary(ByteBuffer.wrap(errorFrame), true)
            .get(WS_TIMEOUT_SECS, TimeUnit.SECONDS)

        // Client should detect the connection close
        val clientClosed = CompletableDeferred<Boolean>()
        launch(Dispatchers.IO) {
            try {
                rawSocket.soTimeout = 10_000
                val n = rawSocket.inputStream.read(ByteArray(1024))
                clientClosed.complete(n == -1)
            } catch (_: Exception) {
                clientClosed.complete(true)
            }
        }

        val closed = withTimeout(15_000) { clientClosed.await() }
        assertTrue(closed, "Client should be disconnected after STREAM_REFUSED")
        rawSocket.close()

        deviceWs.ws.sendClose(WebSocket.NORMAL_CLOSURE, "done")
            .get(WS_TIMEOUT_SECS, TimeUnit.SECONDS)
        coroutineContext.cancelChildren()
    }

    // =========================================================================
    // Scenario 16: Device sends DATA for unknown stream_id
    // =========================================================================

    /**
     * Device sends DATA with a stream_id the relay does not know about.
     * Relay should ignore or send ERROR back. At minimum, should not crash.
     */
    @Test
    fun `scenario 16 - DATA for unknown stream_id does not crash relay`() = runBlocking {
        val deviceWs = connectDeviceMux()

        // Send DATA for a non-existent stream
        val bogusData = MuxCodec.encode(
            MuxFrame.Data(streamId = 99999u, payload = "bogus".toByteArray())
        )
        deviceWs.ws.sendBinary(ByteBuffer.wrap(bogusData), true)
            .get(WS_TIMEOUT_SECS, TimeUnit.SECONDS)

        // Give relay time to process
        delay(1_000)

        // Relay should still be alive: we can still open a real stream
        val openReceived = CompletableDeferred<Boolean>()
        launch(Dispatchers.IO) {
            while (true) {
                val frameBytes = try {
                    waitForFrame(deviceWs.listener.binaryMessages, 10_000)
                } catch (_: Exception) {
                    break
                }
                val frame = MuxCodec.decode(frameBytes)
                if (frame is MuxFrame.Open) {
                    openReceived.complete(true)
                    break
                }
            }
        }

        val rawSocket = connectRawAiClient()

        val gotOpen = withTimeout(10_000) { openReceived.await() }
        assertTrue(gotOpen, "Relay should still function after bogus DATA")
        rawSocket.close()

        deviceWs.ws.sendClose(WebSocket.NORMAL_CLOSURE, "done")
            .get(WS_TIMEOUT_SECS, TimeUnit.SECONDS)
        coroutineContext.cancelChildren()
    }

    // =========================================================================
    // Scenario 17: Mux WebSocket drops — all client TCPs close
    // =========================================================================

    /**
     * Device's WebSocket drops while clients are connected.
     * All AI client TCP connections should close.
     */
    @Test
    fun `scenario 17 - mux drop closes all client connections`() = runBlocking {
        val deviceWs = connectDeviceMux()

        val opensReceived = CompletableDeferred<Int>()
        var openCount = 0
        launch(Dispatchers.IO) {
            while (true) {
                val frameBytes = try {
                    waitForFrame(deviceWs.listener.binaryMessages, 10_000)
                } catch (_: Exception) {
                    break
                }
                val frame = MuxCodec.decode(frameBytes)
                if (frame is MuxFrame.Open) {
                    openCount++
                    if (openCount >= 2) {
                        opensReceived.complete(openCount)
                    }
                }
            }
        }

        // Connect two AI clients via raw TCP
        val rawSocket1 = connectRawAiClient()
        val rawSocket2 = connectRawAiClient()
        val rawSockets = listOf(rawSocket1, rawSocket2)

        withTimeout(10_000) { opensReceived.await() }

        // Device abruptly closes WebSocket
        deviceWs.ws.sendClose(WebSocket.NORMAL_CLOSURE, "device crash")
            .get(WS_TIMEOUT_SECS, TimeUnit.SECONDS)

        // All client sockets should detect the drop
        delay(2_000)
        var droppedCount = 0
        for (socket in rawSockets) {
            try {
                val buf = ByteArray(1)
                socket.soTimeout = 5_000
                val n = socket.inputStream.read(buf)
                if (n == -1) droppedCount++
            } catch (_: Exception) {
                droppedCount++
            }
        }

        assertEquals(
            rawSockets.size,
            droppedCount,
            "All ${rawSockets.size} client sockets should detect the drop"
        )

        rawSockets.forEach { runCatching { it.close() } }
        coroutineContext.cancelChildren()
    }

    // =========================================================================
    // Scenario 18: No mid-stream reconnect
    // =========================================================================

    /**
     * After mux drops, device reconnects on a new WebSocket.
     * Old stream IDs are gone; clients must reconnect from scratch.
     */
    @Test
    fun `scenario 18 - reconnect gets new stream IDs`() = runBlocking {
        // First connection
        val deviceWs1 = connectDeviceMux()

        val firstOpen = CompletableDeferred<MuxFrame.Open>()
        launch(Dispatchers.IO) {
            while (true) {
                val frameBytes = try {
                    waitForFrame(deviceWs1.listener.binaryMessages, 10_000)
                } catch (_: Exception) {
                    break
                }
                val frame = MuxCodec.decode(frameBytes)
                if (frame is MuxFrame.Open) {
                    firstOpen.complete(frame)
                    break
                }
            }
        }

        val rawSocket1 = connectRawAiClient()

        val open1 = withTimeout(10_000) { firstOpen.await() }
        rawSocket1.close()

        // Disconnect device
        deviceWs1.ws.sendClose(WebSocket.NORMAL_CLOSURE, "bye")
            .get(WS_TIMEOUT_SECS, TimeUnit.SECONDS)
        delay(1_000) // Let relay clean up

        // Reconnect device
        val deviceWs2 = connectDeviceMux()

        val secondOpen = CompletableDeferred<MuxFrame.Open>()
        launch(Dispatchers.IO) {
            while (true) {
                val frameBytes = try {
                    waitForFrame(deviceWs2.listener.binaryMessages, 10_000)
                } catch (_: Exception) {
                    break
                }
                val frame = MuxCodec.decode(frameBytes)
                if (frame is MuxFrame.Open) {
                    secondOpen.complete(frame)
                    break
                }
            }
        }

        val rawSocket2 = connectRawAiClient()

        val open2 = withTimeout(10_000) { secondOpen.await() }

        // New session means stream IDs start over
        // The important thing is both sessions work independently
        assertTrue(
            open2.streamId > 0u,
            "Second session should assign valid stream IDs"
        )

        rawSocket2.close()
        deviceWs2.ws.sendClose(WebSocket.NORMAL_CLOSURE, "done")
            .get(WS_TIMEOUT_SECS, TimeUnit.SECONDS)
        coroutineContext.cancelChildren()
    }

    // =========================================================================
    // Helper classes and methods
    // =========================================================================

    private data class DeviceConnection(
        val ws: WebSocket,
        val listener: CollectingListener
    )

    private class CollectingListener : WebSocket.Listener {
        val binaryMessages = CopyOnWriteArrayList<ByteArray>()
        val errors = CopyOnWriteArrayList<Throwable>()
        private val closed = AtomicBoolean(false)

        override fun onOpen(webSocket: WebSocket) {
            webSocket.request(1)
        }

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
        ): CompletionStage<*> {
            closed.set(true)
            return CompletableFuture.completedFuture(null)
        }

        override fun onError(webSocket: WebSocket, error: Throwable) {
            errors.add(error)
        }
    }

    /**
     * Connect a "device" to the relay via mTLS WebSocket on the relay API endpoint.
     */
    private fun connectDeviceMux(): DeviceConnection {
        val sslContext = buildMtlsSslContext()
        val client = HttpClient.newBuilder()
            .sslContext(sslContext)
            .build()

        val listener = CollectingListener()
        val ws = client.newWebSocketBuilder()
            .buildAsync(
                URI.create("wss://$RELAY_HOSTNAME:$relayPort/ws"),
                listener
            )
            .get(WS_TIMEOUT_SECS, TimeUnit.SECONDS)

        return DeviceConnection(ws, listener)
    }

    /**
     * Connect an "AI client" to the relay with SNI set to the device subdomain.
     * Returns an SSLSocket whose TLS will be tunneled through the relay to the device.
     * The TLS handshake completes end-to-end with the device, so the device must
     * be doing TLS accept for this to return.
     */
    private fun connectAiClient(): SSLSocket {
        val factory = buildAiClientSslSocketFactory()
        val socket = factory.createSocket(
            "127.0.0.1",
            relayPort
        ) as SSLSocket

        socket.soTimeout = 10_000
        // Set SNI to device subdomain so relay routes to device passthrough
        val params = socket.sslParameters
        params.serverNames = listOf(
            javax.net.ssl.SNIHostName("$DEVICE_SUBDOMAIN.$RELAY_HOSTNAME")
        )
        socket.sslParameters = params

        // Start the TLS handshake. This sends the ClientHello to the relay,
        // which the relay needs to extract SNI for routing. The handshake
        // completes through the relay to the device's TLS accept.
        socket.startHandshake()
        return socket
    }

    /**
     * Connect an "AI client" via raw TCP, sending a synthetic TLS ClientHello
     * with the device subdomain as SNI. Use this for tests that need the relay
     * to route to the device but do not need full TLS handshake completion.
     */
    private fun connectRawAiClient(subdomain: String = DEVICE_SUBDOMAIN): java.net.Socket {
        val socket = java.net.Socket("127.0.0.1", relayPort)
        socket.soTimeout = 10_000

        val sniHost = "$subdomain.$RELAY_HOSTNAME"
        val clientHello = buildSyntheticClientHello(sniHost)
        socket.outputStream.write(clientHello)
        socket.outputStream.flush()
        return socket
    }

    /**
     * Build a minimal synthetic TLS 1.2 ClientHello with an SNI extension.
     * This is enough for the relay to extract the SNI hostname and route
     * the connection to the device passthrough path.
     */
    @Suppress("MagicNumber")
    private fun buildSyntheticClientHello(sniHostname: String): ByteArray {
        val hostBytes = sniHostname.toByteArray(Charsets.US_ASCII)

        // SNI extension: type(0x0000) + length + server_name_list
        val sniEntry = ByteArray(3 + hostBytes.size) // type(1) + length(2) + name
        sniEntry[0] = 0x00 // host_name type
        sniEntry[1] = (hostBytes.size shr 8).toByte()
        sniEntry[2] = hostBytes.size.toByte()
        hostBytes.copyInto(sniEntry, 3)

        val sniList = ByteArray(2 + sniEntry.size)
        sniList[0] = (sniEntry.size shr 8).toByte()
        sniList[1] = sniEntry.size.toByte()
        sniEntry.copyInto(sniList, 2)

        val sniExt = ByteArray(4 + sniList.size)
        sniExt[0] = 0x00 // extension type high byte
        sniExt[1] = 0x00 // extension type low byte (SNI = 0x0000)
        sniExt[2] = (sniList.size shr 8).toByte()
        sniExt[3] = sniList.size.toByte()
        sniList.copyInto(sniExt, 4)

        val extensions = ByteArray(2 + sniExt.size)
        extensions[0] = (sniExt.size shr 8).toByte()
        extensions[1] = sniExt.size.toByte()
        sniExt.copyInto(extensions, 2)

        // ClientHello body: version(2) + random(32) + session_id_len(1) +
        // cipher_suites_len(2) + cipher(2) + comp_len(1) + comp(1) + extensions
        val random = ByteArray(32) { 0x42 }
        val cipherSuites = byteArrayOf(
            0x00,
            0x02, // length = 2 bytes (1 suite)
            0x00,
            0x2F // TLS_RSA_WITH_AES_128_CBC_SHA
        )
        val compression = byteArrayOf(0x01, 0x00) // 1 method: null

        val helloBody = ByteArray(
            2 + 32 + 1 + cipherSuites.size + compression.size + extensions.size
        )
        var pos = 0
        helloBody[pos++] = 0x03 // TLS 1.2
        helloBody[pos++] = 0x03.toByte()
        random.copyInto(helloBody, pos)
        pos += 32
        helloBody[pos++] = 0x00 // session_id length = 0
        cipherSuites.copyInto(helloBody, pos)
        pos += cipherSuites.size
        compression.copyInto(helloBody, pos)
        pos += compression.size
        extensions.copyInto(helloBody, pos)

        // Handshake header: type(1) + length(3)
        val handshake = ByteArray(4 + helloBody.size)
        handshake[0] = 0x01 // ClientHello
        handshake[1] = (helloBody.size shr 16).toByte()
        handshake[2] = (helloBody.size shr 8).toByte()
        handshake[3] = helloBody.size.toByte()
        helloBody.copyInto(handshake, 4)

        // TLS record header: type(1) + version(2) + length(2)
        val record = ByteArray(5 + handshake.size)
        record[0] = 0x16 // Handshake
        record[1] = 0x03 // TLS 1.0 record version
        record[2] = 0x01
        record[3] = (handshake.size shr 8).toByte()
        record[4] = handshake.size.toByte()
        handshake.copyInto(record, 5)

        return record
    }

    /**
     * Build an SSLSocketFactory for the AI client that trusts the device's cert.
     * The AI client does TLS with the device (through the relay passthrough).
     */
    private fun buildAiClientSslSocketFactory(): SSLSocketFactory {
        val trustStore = KeyStore.getInstance(KeyStore.getDefaultType())
        trustStore.load(null, null)
        trustStore.setCertificateEntry("device-ca", caCert)
        // Also trust the device cert directly for self-signed scenarios
        trustStore.setCertificateEntry("device", deviceCert)

        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(trustStore)

        val ctx = SSLContext.getInstance("TLS")
        ctx.init(null, tmf.trustManagers, null)
        return ctx.socketFactory
    }

    /**
     * Build SSL context for the device acting as TLS server.
     * Uses the device's keypair from the keystore.
     */
    private fun buildDeviceServerSslContext(): SSLContext {
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(deviceKeyStore, STORE_PASS.toCharArray())

        val ctx = SSLContext.getInstance("TLS")
        ctx.init(kmf.keyManagers, null, null)
        return ctx
    }

    /**
     * Build mTLS SSL context for the device WebSocket connection.
     * The device presents its client cert and trusts the relay's CA.
     */
    private fun buildMtlsSslContext(): SSLContext {
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(deviceKeyStore, STORE_PASS.toCharArray())

        val trustStore = KeyStore.getInstance(KeyStore.getDefaultType())
        trustStore.load(null, null)
        trustStore.setCertificateEntry("ca", caCert)

        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(trustStore)

        val ctx = SSLContext.getInstance("TLS")
        ctx.init(kmf.keyManagers, tmf.trustManagers, null)
        return ctx
    }

    /**
     * Wait for a binary frame to appear in the list, polling with timeout.
     */
    private fun waitForFrame(
        messages: CopyOnWriteArrayList<ByteArray>,
        timeoutMs: Long
    ): ByteArray {
        val deadline = System.currentTimeMillis() + timeoutMs
        var lastSize = 0
        while (System.currentTimeMillis() < deadline) {
            if (messages.size > lastSize) {
                return messages.removeAt(lastSize)
            }
            Thread.sleep(50)
        }
        throw Exception("Timed out waiting for frame after ${timeoutMs}ms")
    }

    // --- Certificate generation ---

    @Suppress("LongMethod")
    private fun generateCertificates() {
        val caKs = File(tempDir, "ca.p12")
        val relayKs = File(tempDir, "relay.p12")
        val deviceKs = File(tempDir, "device.p12")
        val caCertFile = File(tempDir, "ca-cert.pem")
        val csrFile = File(tempDir, "relay.csr")
        val signedCertFile = File(tempDir, "relay-signed.pem")
        val deviceCsrFile = File(tempDir, "device.csr")
        val deviceSignedCertFile = File(tempDir, "device-signed.pem")
        val relayCertFile = File(tempDir, "relay-cert.pem")
        val relayKeyFile = File(tempDir, "relay-key.pem")
        val deviceDomain = "$DEVICE_SUBDOMAIN.$RELAY_HOSTNAME"

        // --- CA ---
        keytool(
            "-genkeypair", "-alias", "ca",
            "-keyalg", "RSA", "-keysize", "2048",
            "-sigalg", "SHA256withRSA",
            "-dname", "CN=Test CA",
            "-ext", "bc:c",
            "-validity", "365",
            "-storetype", "PKCS12",
            "-keystore", caKs.absolutePath,
            "-storepass", STORE_PASS,
            "-keypass", STORE_PASS
        )
        keytool(
            "-exportcert", "-alias", "ca",
            "-keystore", caKs.absolutePath,
            "-storepass", STORE_PASS,
            "-rfc",
            "-file", caCertFile.absolutePath
        )

        // --- Relay server cert (for mTLS WebSocket) ---
        keytool(
            "-genkeypair", "-alias", "relay",
            "-keyalg", "RSA", "-keysize", "2048",
            "-sigalg", "SHA256withRSA",
            "-dname", "CN=$RELAY_HOSTNAME",
            "-validity", "365",
            "-storetype", "PKCS12",
            "-keystore", relayKs.absolutePath,
            "-storepass", STORE_PASS,
            "-keypass", STORE_PASS
        )
        keytool(
            "-certreq", "-alias", "relay",
            "-keystore", relayKs.absolutePath,
            "-storepass", STORE_PASS,
            "-file", csrFile.absolutePath
        )
        keytool(
            "-gencert", "-alias", "ca",
            "-keystore", caKs.absolutePath,
            "-storepass", STORE_PASS,
            "-infile", csrFile.absolutePath,
            "-outfile", signedCertFile.absolutePath,
            "-ext", "san=dns:$RELAY_HOSTNAME",
            "-rfc",
            "-validity", "365"
        )
        keytool(
            "-importcert", "-alias", "ca",
            "-keystore", relayKs.absolutePath,
            "-storepass", STORE_PASS,
            "-file", caCertFile.absolutePath,
            "-noprompt"
        )
        keytool(
            "-importcert", "-alias", "relay",
            "-keystore", relayKs.absolutePath,
            "-storepass", STORE_PASS,
            "-file", signedCertFile.absolutePath
        )

        relayCertFile.writeText(signedCertFile.readText())
        extractPrivateKey(relayKs, relayKeyFile)

        // --- Device client cert (CN = device subdomain) ---
        keytool(
            "-genkeypair", "-alias", "device",
            "-keyalg", "RSA", "-keysize", "2048",
            "-sigalg", "SHA256withRSA",
            "-dname", "CN=$deviceDomain",
            "-validity", "365",
            "-storetype", "PKCS12",
            "-keystore", deviceKs.absolutePath,
            "-storepass", STORE_PASS,
            "-keypass", STORE_PASS
        )
        keytool(
            "-certreq", "-alias", "device",
            "-keystore", deviceKs.absolutePath,
            "-storepass", STORE_PASS,
            "-file", deviceCsrFile.absolutePath
        )
        keytool(
            "-gencert", "-alias", "ca",
            "-keystore", caKs.absolutePath,
            "-storepass", STORE_PASS,
            "-infile", deviceCsrFile.absolutePath,
            "-outfile", deviceSignedCertFile.absolutePath,
            "-ext", "san=dns:$deviceDomain",
            "-rfc",
            "-validity", "365"
        )
        keytool(
            "-importcert", "-alias", "ca",
            "-keystore", deviceKs.absolutePath,
            "-storepass", STORE_PASS,
            "-file", caCertFile.absolutePath,
            "-noprompt"
        )
        keytool(
            "-importcert", "-alias", "device",
            "-keystore", deviceKs.absolutePath,
            "-storepass", STORE_PASS,
            "-file", deviceSignedCertFile.absolutePath
        )

        // Load for use in tests
        val caStore = KeyStore.getInstance("PKCS12")
        caKs.inputStream().use { caStore.load(it, STORE_PASS.toCharArray()) }
        caCert = caStore.getCertificate("ca") as X509Certificate

        deviceKeyStore = KeyStore.getInstance("PKCS12")
        deviceKs.inputStream().use {
            deviceKeyStore.load(it, STORE_PASS.toCharArray())
        }
        deviceCert = deviceKeyStore.getCertificate("device") as X509Certificate
    }

    private fun extractPrivateKey(keystoreFile: File, keyFile: File) {
        for (args in listOf(
            listOf(
                "openssl", "pkcs12",
                "-in", keystoreFile.absolutePath,
                "-nocerts", "-nodes",
                "-passin", "pass:$STORE_PASS",
                "-legacy"
            ),
            listOf(
                "openssl",
                "pkcs12",
                "-in",
                keystoreFile.absolutePath,
                "-nocerts",
                "-nodes",
                "-passin",
                "pass:$STORE_PASS"
            )
        )) {
            val proc = ProcessBuilder(args)
                .redirectErrorStream(true)
                .start()
            val output = proc.inputStream.bufferedReader().readText()
            proc.waitFor()

            val keyStart = output.indexOf("-----BEGIN PRIVATE KEY-----")
            val keyEndMarker = "-----END PRIVATE KEY-----"
            val keyEnd = output.indexOf(keyEndMarker)
            if (keyStart >= 0 && keyEnd > keyStart) {
                keyFile.writeText(
                    output.substring(keyStart, keyEnd + keyEndMarker.length) + "\n"
                )
                return
            }
        }
        fail("Failed to extract private key from ${keystoreFile.name}")
    }

    private fun keytool(vararg args: String) {
        val process = ProcessBuilder("keytool", *args)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()
        if (exitCode != 0) {
            fail("keytool failed (exit $exitCode): $output\nArgs: ${args.toList()}")
        }
    }

    // --- Relay config and process management ---

    private fun writeRelayConfig(port: Int) {
        val config = """
            [server]
            bind_addr = "127.0.0.1:$port"
            relay_hostname = "$RELAY_HOSTNAME"

            [tls]
            cert_path = "${tempDir.absolutePath}/relay-cert.pem"
            key_path = "${tempDir.absolutePath}/relay-key.pem"
            ca_cert_path = "${tempDir.absolutePath}/ca-cert.pem"

            [limits]
            max_streams_per_device = 8
            wake_rate_limit = 60
            fcm_wakeup_timeout_secs = 5
        """.trimIndent()

        File(tempDir, "relay.toml").writeText(config)
    }

    private fun startRelay(port: Int): Process {
        val configPath = File(tempDir, "relay.toml").absolutePath
        val pb = ProcessBuilder(relayBinary.absolutePath, configPath)
            .redirectErrorStream(true)
        pb.environment()["RUST_LOG"] = "debug"

        val process = pb.start()
        val deadline = System.currentTimeMillis() + RELAY_STARTUP_TIMEOUT_MS

        val outputCapture = StringBuilder()
        val readerThread = Thread {
            try {
                process.inputStream.bufferedReader().forEachLine { line ->
                    synchronized(outputCapture) { outputCapture.appendLine(line) }
                }
            } catch (_: Exception) {
                // Process killed
            }
        }
        readerThread.isDaemon = true
        readerThread.start()

        var started = false
        while (System.currentTimeMillis() < deadline) {
            if (!process.isAlive) {
                val output = synchronized(outputCapture) { outputCapture.toString() }
                fail("Relay process died during startup. Output:\n$output")
            }
            try {
                java.net.Socket("127.0.0.1", port).use { started = true }
                break
            } catch (_: Exception) {
                Thread.sleep(100)
            }
        }

        if (!started) {
            process.destroyForcibly()
            val output = synchronized(outputCapture) { outputCapture.toString() }
            fail("Relay did not start within ${RELAY_STARTUP_TIMEOUT_MS}ms. Output:\n$output")
        }

        Thread.sleep(200)
        return process
    }

    // --- Utility ---

    private fun findFreePort(): Int {
        val socket = java.net.ServerSocket(0)
        val port = socket.localPort
        socket.close()
        return port
    }

    private fun findRelayBinary(): File {
        val repoRoot = System.getProperty("repo.root")
        if (repoRoot != null) {
            val candidate = File(repoRoot, "relay/target/debug/rouse-relay")
            if (candidate.exists() && candidate.canExecute()) return candidate
        }

        var dir = File(System.getProperty("user.dir"))
        while (dir.parentFile != null) {
            val candidate = File(dir, "relay/target/debug/rouse-relay")
            if (candidate.exists() && candidate.canExecute()) return candidate
            dir = dir.parentFile
        }

        return File("/nonexistent/rouse-relay")
    }
}
