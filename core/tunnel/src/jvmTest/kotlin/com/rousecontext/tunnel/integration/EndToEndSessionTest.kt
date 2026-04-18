package com.rousecontext.tunnel.integration

import com.rousecontext.tunnel.MuxCodec
import com.rousecontext.tunnel.MuxErrorCode
import com.rousecontext.tunnel.MuxFrame
import com.rousecontext.tunnel.MuxStream
import com.rousecontext.tunnel.TlsAcceptor
import com.rousecontext.tunnel.TunnelClientImpl
import com.rousecontext.tunnel.TunnelState
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
import javax.net.ssl.SSLSocket
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

/**
 * End-to-end session tests using the real Rust relay binary.
 *
 * Scenarios 5-7 exercise the production [TunnelClientImpl] code path:
 * the device connects via [MtlsWebSocketFactory], and incoming sessions
 * arrive through [TunnelClientImpl.incomingSessions].
 *
 * Later scenarios (8+) test relay-level behavior (disconnect propagation,
 * max streams, error frames) using raw WebSocket where needed to inject
 * specific mux frames.
 *
 * SNI format: the relay's SNI router (see `relay/src/sni.rs`) requires
 * hostnames of the form `{integration_secret}.{subdomain}.{base_domain}`.
 * Bare `{subdomain}.{base_domain}` SNI is rejected. We use [INTEGRATION_SECRET]
 * as the first label; because the relay auto-creates a Firestore record with
 * empty `valid_secrets` when the device WS upgrades, secret validation is
 * skipped and any non-empty secret prefix is accepted.
 *
 * These tests are skipped if the relay binary has not been built.
 * Build it with: cd relay && cargo build
 */
@Suppress("LargeClass")
@Tag("integration")
@Timeout(value = 180, unit = TimeUnit.SECONDS)
class EndToEndSessionTest {

    companion object {
        private const val RELAY_HOSTNAME = "localhost"
        private const val WS_TIMEOUT_SECS = 10L
        private const val DEVICE_SUBDOMAIN = "test-device"
        private const val INTEGRATION_SECRET = "ai"

        /** The SNI hostname AI clients connect with: `{secret}.{subdomain}.{hostname}`. */
        private const val CLIENT_SNI =
            "$INTEGRATION_SECRET.$DEVICE_SUBDOMAIN.$RELAY_HOSTNAME"

        /**
         * Give the relay a moment after the WS upgrade completes to finish
         * auto-creating the Firestore device record and inserting the mux
         * session into its registry. Without this, an AI client that races
         * in immediately after [connectTunnelClient] returns can hit
         * `DeviceNotFound` before the server-side `handle_mux_session`
         * body runs.
         */
        private const val SESSION_REGISTRATION_DELAY_MS = 500L
    }

    private lateinit var tempDir: File
    private lateinit var ca: TestCertificateAuthority
    private lateinit var relayManager: TestRelayManager
    private var relayPort: Int = 0

    // Convenience aliases from the CA
    private val caCert: X509Certificate get() = ca.caCert
    private val deviceKeyStore: KeyStore get() = ca.deviceKeyStore
    private val deviceCert: X509Certificate get() = ca.deviceCert

    @BeforeEach
    fun setUp() {
        val relayBinary = findRelayBinary()
        assumeTrue(
            relayBinary.exists() && relayBinary.canExecute(),
            "Relay binary not found. Build with: cd relay && cargo build"
        )

        tempDir = File.createTempFile("e2e-session-", "")
        tempDir.delete()
        tempDir.mkdirs()

        ca = TestCertificateAuthority(tempDir, RELAY_HOSTNAME, DEVICE_SUBDOMAIN)
        ca.generate()

        relayManager = TestRelayManager(tempDir, RELAY_HOSTNAME)
        relayPort = findFreePort()
        relayManager.start(relayPort)
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

    // =========================================================================
    // Scenario 5: Cold path -- full TLS tunnel via TunnelClientImpl
    // =========================================================================

    /**
     * Client connects via TLS with device subdomain SNI. The device uses
     * [TunnelClientImpl] to receive sessions from [incomingSessions], then
     * does TLS accept on the mux stream. Plaintext data flows bidirectionally.
     */
    @Test
    fun `scenario 5 - cold path full TLS tunnel`() = runBlocking {
        val tunnelClient = connectTunnelClient()

        try {
            // Collect the first incoming session
            val sessionReceived = CompletableDeferred<MuxStream>()
            val collectJob = launch {
                tunnelClient.incomingSessions.collect { stream ->
                    sessionReceived.complete(stream)
                }
            }

            // AI client connects with device subdomain SNI (starts TLS handshake)
            val clientSocket = CompletableDeferred<SSLSocket>()
            launch(Dispatchers.IO) {
                val socket = connectAiClient()
                clientSocket.complete(socket)
            }

            // Device receives the session via TunnelClientImpl
            val muxStream = withTimeout(10_000) { sessionReceived.await() }
            assertTrue(muxStream.id > 0u, "Should get a valid stream ID")

            // Device does TLS accept over the mux stream
            val deviceSslContext = TestSslContexts.buildDeviceServer(deviceKeyStore)
            val acceptor = TlsAcceptor.create(deviceSslContext)

            val tlsSession = CompletableDeferred<TlsAcceptor.TlsSession>()
            launch(Dispatchers.IO) {
                val session = acceptor.accept(muxStream)
                tlsSession.complete(session)
            }

            // Wait for both sides to complete handshake
            val aiSocket = withTimeout(10_000) { clientSocket.await() }
            val session = withTimeout(10_000) { tlsSession.await() }

            // Bidirectional plaintext data flow
            // Client -> Device
            aiSocket.outputStream.write("hello from AI client".toByteArray())
            aiSocket.outputStream.flush()

            val buf = ByteArray(4096)
            val n = session.read(buf, 0, buf.size)
            assertTrue(n > 0, "Should read plaintext from AI client")
            assertEquals("hello from AI client", String(buf, 0, n))

            // Device -> Client
            session.write("hello from device".toByteArray())

            val buf2 = ByteArray(4096)
            val n2 = aiSocket.inputStream.read(buf2, 0, buf2.size)
            assertTrue(n2 > 0, "Should read plaintext from device")
            assertEquals("hello from device", String(buf2, 0, n2))

            // Cleanup
            aiSocket.close()
            collectJob.cancel()
        } finally {
            tunnelClient.disconnect()
            coroutineContext.cancelChildren()
        }
    }

    // =========================================================================
    // Scenario 6: Warm path -- device already connected via TunnelClientImpl
    // =========================================================================

    /**
     * Device already connected via TunnelClientImpl. Client connects and gets
     * an instant OPEN with no delay.
     */
    @Test
    fun `scenario 6 - warm path instant OPEN`() = runBlocking {
        val tunnelClient = connectTunnelClient()

        try {
            val sessionReceived = CompletableDeferred<MuxStream>()
            val collectJob = launch {
                tunnelClient.incomingSessions.collect { stream ->
                    sessionReceived.complete(stream)
                }
            }

            // Device is already connected. Now connect AI client.
            val startTime = System.currentTimeMillis()

            // AI client connects via raw TCP with synthetic ClientHello
            val rawSocket = connectRawAiClient()

            val muxStream = withTimeout(10_000) { sessionReceived.await() }
            val elapsed = System.currentTimeMillis() - startTime

            assertTrue(muxStream.id > 0u, "Should get a valid stream ID")
            // Warm path should be sub-second (no FCM wakeup needed)
            assertTrue(
                elapsed < 3_000,
                "Session should arrive quickly (warm path), took ${elapsed}ms"
            )

            rawSocket.close()
            collectJob.cancel()
        } finally {
            tunnelClient.disconnect()
            coroutineContext.cancelChildren()
        }
    }

    // =========================================================================
    // Scenario 7: Concurrent clients via TunnelClientImpl
    // =========================================================================

    /**
     * Two AI clients connect to the same device subdomain. Device receives
     * two sessions via [TunnelClientImpl.incomingSessions] with different
     * stream IDs.
     */
    @Test
    fun `scenario 7 - concurrent clients get different stream IDs`() = runBlocking {
        val tunnelClient = connectTunnelClient()

        try {
            val sessions = CopyOnWriteArrayList<MuxStream>()
            val twoSessions = CompletableDeferred<Unit>()
            val collectJob = launch {
                tunnelClient.incomingSessions.collect { stream ->
                    sessions.add(stream)
                    if (sessions.size >= 2) {
                        twoSessions.complete(Unit)
                    }
                }
            }

            // Connect two AI clients via raw TCP
            val rawSocket1 = connectRawAiClient()
            val rawSocket2 = connectRawAiClient()

            withTimeout(10_000) { twoSessions.await() }

            assertEquals(2, sessions.size, "Should receive two sessions")
            val ids = sessions.map { it.id }.toSet()
            assertEquals(2, ids.size, "Stream IDs should be different: $ids")

            rawSocket1.close()
            rawSocket2.close()
            collectJob.cancel()
        } finally {
            tunnelClient.disconnect()
            coroutineContext.cancelChildren()
        }
    }

    // =========================================================================
    // Scenario 8: Client disconnects mid-session (via TunnelClientImpl)
    // =========================================================================

    /**
     * Client closes TCP during active session. Device should see the stream
     * close via the MuxStream lifecycle.
     */
    @Test
    fun `scenario 8 - client disconnect sends CLOSE to device`() = runBlocking {
        val tunnelClient = connectTunnelClient()

        try {
            val sessionReceived = CompletableDeferred<MuxStream>()
            val collectJob = launch {
                tunnelClient.incomingSessions.collect { stream ->
                    sessionReceived.complete(stream)
                }
            }

            // AI client connects via raw TCP
            val rawSocket = connectRawAiClient()

            val muxStream = withTimeout(10_000) { sessionReceived.await() }

            // Close the raw socket abruptly
            rawSocket.close()

            // The stream should close. It may first deliver DATA frames (the
            // ClientHello bytes), then eventually throw on read when the relay
            // sends CLOSE. Drain until we see the closure.
            val streamClosed = CompletableDeferred<Boolean>()
            launch(Dispatchers.IO) {
                try {
                    // Drain any pending DATA frames, then CLOSE causes exception
                    while (true) {
                        muxStream.read()
                    }
                } catch (_: Exception) {
                    streamClosed.complete(true)
                }
            }

            val closed = withTimeout(15_000) { streamClosed.await() }
            assertTrue(closed, "MuxStream should close after client disconnect")

            collectJob.cancel()
        } finally {
            tunnelClient.disconnect()
            coroutineContext.cancelChildren()
        }
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
        val tunnelClient = connectTunnelClient()

        val sessionReceived = CompletableDeferred<MuxStream>()
        val collectJob = launch {
            tunnelClient.incomingSessions.collect { stream ->
                sessionReceived.complete(stream)
            }
        }

        // AI client connects via raw TCP
        val rawSocket = connectRawAiClient()

        withTimeout(10_000) { sessionReceived.await() }

        // Device disconnects
        tunnelClient.disconnect()
        collectJob.cancel()

        // Client should detect the connection drop
        val disconnected = CompletableDeferred<Boolean>()
        launch(Dispatchers.IO) {
            try {
                val buf = ByteArray(1024)
                rawSocket.soTimeout = 10_000
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
    // Scenario 10: Max streams exceeded (via TunnelClientImpl)
    // =========================================================================

    /**
     * Open 8 streams (the configured max), try to open a 9th.
     * The relay should refuse or close it.
     */
    @Test
    fun `scenario 10 - max streams exceeded`() = runBlocking {
        val tunnelClient = connectTunnelClient()

        try {
            val sessions = CopyOnWriteArrayList<MuxStream>()
            val enoughSessions = CompletableDeferred<Int>()
            val collectJob = launch {
                tunnelClient.incomingSessions.collect { stream ->
                    sessions.add(stream)
                    if (sessions.size >= 8) {
                        // Give the 9th a moment to be processed
                        delay(2_000)
                        enoughSessions.complete(sessions.size)
                    }
                }
            }

            // Open 9 AI client connections via raw TCP
            val sockets = CopyOnWriteArrayList<java.net.Socket>()
            for (ignored in 1..9) {
                try {
                    sockets.add(connectRawAiClient())
                } catch (_: Exception) {
                    // Some connections may be refused
                }
            }

            val count = withTimeout(20_000) { enoughSessions.await() }
            // Device should receive at most 8 sessions (max_streams_per_device = 8)
            assertTrue(count <= 8, "Should receive at most 8 sessions, got $count")

            sockets.forEach { runCatching { it.close() } }
            collectJob.cancel()
        } finally {
            tunnelClient.disconnect()
            coroutineContext.cancelChildren()
        }
    }

    // =========================================================================
    // Scenario 14: FCM timeout (simulated) -- no device registered
    // =========================================================================

    /**
     * Client connects to a subdomain with no device registered.
     * The relay looks the device up in Firestore, finds nothing, and closes
     * the TCP connection. We use a secret-prefixed SNI so this exercises the
     * `DeviceNotFound` branch rather than the SNI-router's bare-subdomain
     * reject path (which scenario 14 is specifically meant to cover).
     */
    @Test
    fun `scenario 14 - no device registered causes client rejection`() = runBlocking {
        // Do NOT connect a device mux. Connect AI client to unknown subdomain.
        val rejected = CompletableDeferred<Boolean>()
        launch(Dispatchers.IO) {
            try {
                val factory = TestSslContexts.buildAiClientSocketFactory(caCert, deviceCert)
                val socket = factory.createSocket(
                    "127.0.0.1",
                    relayPort
                ) as SSLSocket
                socket.soTimeout = 15_000
                val params = socket.sslParameters
                params.serverNames = listOf(
                    javax.net.ssl.SNIHostName(
                        "$INTEGRATION_SECRET.unknown-device.$RELAY_HOSTNAME"
                    )
                )
                socket.sslParameters = params
                socket.startHandshake()
                // If we get here, try to read -- should fail
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
    // Scenario 15: Device sends ERROR(STREAM_REFUSED) -- raw WS needed
    // =========================================================================

    /**
     * Device receives OPEN, sends back ERROR(STREAM_REFUSED).
     * Relay should close the AI client's TCP connection.
     *
     * This test uses raw WebSocket because it needs to inject an ERROR frame
     * that TunnelClientImpl would not normally send.
     */
    @Suppress("LoopWithTooManyJumpStatements")
    @Test
    fun `scenario 15 - device STREAM_REFUSED closes client`() = runBlocking {
        val deviceWs = connectDeviceMuxRaw()

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
    // Scenario 16: Device sends DATA for unknown stream_id -- raw WS needed
    // =========================================================================

    /**
     * Device sends DATA with a stream_id the relay does not know about.
     * Relay should ignore or send ERROR back. At minimum, should not crash.
     *
     * This test uses raw WebSocket to inject an invalid frame.
     */
    @Suppress("LoopWithTooManyJumpStatements")
    @Test
    fun `scenario 16 - DATA for unknown stream_id does not crash relay`() = runBlocking {
        val deviceWs = connectDeviceMuxRaw()

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
    // Scenario 17: Mux WebSocket drops -- all client TCPs close
    // =========================================================================

    /**
     * Device's WebSocket drops while clients are connected.
     * All AI client TCP connections should close.
     */
    @Test
    fun `scenario 17 - mux drop closes all client connections`() = runBlocking {
        val tunnelClient = connectTunnelClient()

        val sessions = CopyOnWriteArrayList<MuxStream>()
        val twoSessions = CompletableDeferred<Unit>()
        val collectJob = launch {
            tunnelClient.incomingSessions.collect { stream ->
                sessions.add(stream)
                if (sessions.size >= 2) {
                    twoSessions.complete(Unit)
                }
            }
        }

        // Connect two AI clients via raw TCP
        val rawSocket1 = connectRawAiClient()
        val rawSocket2 = connectRawAiClient()
        val rawSockets = listOf(rawSocket1, rawSocket2)

        withTimeout(10_000) { twoSessions.await() }

        // Device disconnects abruptly
        tunnelClient.disconnect()
        collectJob.cancel()

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
    // Scenario 18: No mid-stream reconnect (via TunnelClientImpl)
    // =========================================================================

    /**
     * After TunnelClientImpl disconnects, device reconnects on a new
     * TunnelClientImpl. Old stream IDs are gone; clients must reconnect.
     */
    @Test
    fun `scenario 18 - reconnect gets new stream IDs`() = runBlocking {
        // First connection
        val tunnelClient1 = connectTunnelClient()

        val firstSession = CompletableDeferred<MuxStream>()
        val collectJob1 = launch {
            tunnelClient1.incomingSessions.collect { stream ->
                firstSession.complete(stream)
            }
        }

        val rawSocket1 = connectRawAiClient()

        withTimeout(10_000) { firstSession.await() }
        rawSocket1.close()

        // Disconnect first tunnel client
        tunnelClient1.disconnect()
        collectJob1.cancel()
        delay(1_000) // Let relay clean up

        // Reconnect with a new TunnelClientImpl
        val tunnelClient2 = connectTunnelClient()

        val secondSession = CompletableDeferred<MuxStream>()
        val collectJob2 = launch {
            tunnelClient2.incomingSessions.collect { stream ->
                secondSession.complete(stream)
            }
        }

        val rawSocket2 = connectRawAiClient()

        val session2 = withTimeout(10_000) { secondSession.await() }

        // New session means stream IDs start over
        // The important thing is both sessions work independently
        assertTrue(
            session2.id > 0u,
            "Second session should assign valid stream IDs"
        )

        rawSocket2.close()
        tunnelClient2.disconnect()
        collectJob2.cancel()
        coroutineContext.cancelChildren()
    }

    // =========================================================================
    // Scenario 19: TLS with split records
    // =========================================================================

    /**
     * Full TLS tunnel where data flows in multiple small writes. Verifies
     * that TLS records split across mux DATA frames are reassembled correctly.
     */
    @Test
    fun `scenario 19 - TLS tunnel handles multiple sequential messages`() = runBlocking {
        val tunnelClient = connectTunnelClient()

        try {
            val sessionReceived = CompletableDeferred<MuxStream>()
            val collectJob = launch {
                tunnelClient.incomingSessions.collect { stream ->
                    sessionReceived.complete(stream)
                }
            }

            // AI client connects with TLS
            val clientSocket = CompletableDeferred<SSLSocket>()
            launch(Dispatchers.IO) {
                val socket = connectAiClient()
                clientSocket.complete(socket)
            }

            val muxStream = withTimeout(10_000) { sessionReceived.await() }

            // TLS accept on device side
            val deviceSslContext = TestSslContexts.buildDeviceServer(deviceKeyStore)
            val acceptor = TlsAcceptor.create(deviceSslContext)

            val tlsSession = CompletableDeferred<TlsAcceptor.TlsSession>()
            launch(Dispatchers.IO) {
                val session = acceptor.accept(muxStream)
                tlsSession.complete(session)
            }

            val aiSocket = withTimeout(10_000) { clientSocket.await() }
            val session = withTimeout(10_000) { tlsSession.await() }

            // Send multiple messages in sequence
            val messages = listOf("first", "second", "third", "fourth", "fifth")
            for (msg in messages) {
                aiSocket.outputStream.write(msg.toByteArray())
                aiSocket.outputStream.flush()

                val buf = ByteArray(4096)
                val n = session.read(buf, 0, buf.size)
                assertTrue(n > 0, "Should read message: $msg")
                assertEquals(msg, String(buf, 0, n))
            }

            // And in the other direction
            for (msg in messages) {
                session.write(msg.toByteArray())

                val buf = ByteArray(4096)
                val n = aiSocket.inputStream.read(buf, 0, buf.size)
                assertTrue(n > 0, "Should read reply: $msg")
                assertEquals(msg, String(buf, 0, n))
            }

            aiSocket.close()
            collectJob.cancel()
        } finally {
            tunnelClient.disconnect()
            coroutineContext.cancelChildren()
        }
    }

    // =========================================================================
    // Scenario 20: Session lifecycle -- connect, stream, disconnect, reconnect
    // =========================================================================

    /**
     * Full lifecycle: connect TunnelClient, receive a session, exchange data,
     * disconnect, reconnect, receive another session, exchange data again.
     */
    @Suppress("LongMethod")
    @Test
    fun `scenario 20 - full session lifecycle with reconnect`() = runBlocking {
        // Phase 1: connect and exchange data
        val tunnelClient1 = connectTunnelClient()

        val session1Received = CompletableDeferred<MuxStream>()
        val collectJob1 = launch {
            tunnelClient1.incomingSessions.collect { stream ->
                session1Received.complete(stream)
            }
        }

        val clientSocket1 = CompletableDeferred<SSLSocket>()
        launch(Dispatchers.IO) {
            clientSocket1.complete(connectAiClient())
        }

        val muxStream1 = withTimeout(10_000) { session1Received.await() }
        val deviceSslContext = TestSslContexts.buildDeviceServer(deviceKeyStore)
        val acceptor1 = TlsAcceptor.create(deviceSslContext)

        val tlsSession1 = CompletableDeferred<TlsAcceptor.TlsSession>()
        launch(Dispatchers.IO) {
            tlsSession1.complete(acceptor1.accept(muxStream1))
        }

        val aiSocket1 = withTimeout(10_000) { clientSocket1.await() }
        val tls1 = withTimeout(10_000) { tlsSession1.await() }

        // Exchange data
        aiSocket1.outputStream.write("phase1-request".toByteArray())
        aiSocket1.outputStream.flush()
        val buf1 = ByteArray(4096)
        val n1 = tls1.read(buf1, 0, buf1.size)
        assertEquals("phase1-request", String(buf1, 0, n1))

        tls1.write("phase1-response".toByteArray())
        val buf1r = ByteArray(4096)
        val n1r = aiSocket1.inputStream.read(buf1r, 0, buf1r.size)
        assertEquals("phase1-response", String(buf1r, 0, n1r))

        aiSocket1.close()
        tunnelClient1.disconnect()
        collectJob1.cancel()

        assertEquals(TunnelState.DISCONNECTED, tunnelClient1.state.value)
        delay(1_000) // Let relay clean up

        // Phase 2: reconnect and exchange data again
        val tunnelClient2 = connectTunnelClient()
        assertEquals(TunnelState.CONNECTED, tunnelClient2.state.value)

        val session2Received = CompletableDeferred<MuxStream>()
        val collectJob2 = launch {
            tunnelClient2.incomingSessions.collect { stream ->
                session2Received.complete(stream)
            }
        }

        val clientSocket2 = CompletableDeferred<SSLSocket>()
        launch(Dispatchers.IO) {
            clientSocket2.complete(connectAiClient())
        }

        val muxStream2 = withTimeout(10_000) { session2Received.await() }
        val acceptor2 = TlsAcceptor.create(deviceSslContext)

        val tlsSession2 = CompletableDeferred<TlsAcceptor.TlsSession>()
        launch(Dispatchers.IO) {
            tlsSession2.complete(acceptor2.accept(muxStream2))
        }

        val aiSocket2 = withTimeout(10_000) { clientSocket2.await() }
        val tls2 = withTimeout(10_000) { tlsSession2.await() }

        aiSocket2.outputStream.write("phase2-request".toByteArray())
        aiSocket2.outputStream.flush()
        val buf2 = ByteArray(4096)
        val n2 = tls2.read(buf2, 0, buf2.size)
        assertEquals("phase2-request", String(buf2, 0, n2))

        tls2.write("phase2-response".toByteArray())
        val buf2r = ByteArray(4096)
        val n2r = aiSocket2.inputStream.read(buf2r, 0, buf2r.size)
        assertEquals("phase2-response", String(buf2r, 0, n2r))

        aiSocket2.close()
        tunnelClient2.disconnect()
        collectJob2.cancel()
        coroutineContext.cancelChildren()
    }

    // =========================================================================
    // Scenario: healthCheck against the real relay
    // =========================================================================

    /**
     * Real-relay integration of [TunnelClient.healthCheck]. The relay's
     * production `dispatch_incoming` implementation must answer a Ping with a
     * matching Pong without opening a stream. See issue #179.
     */
    @Test
    fun `healthCheck succeeds against live relay`() = runBlocking {
        val tunnelClient = connectTunnelClient()
        try {
            val live = tunnelClient.healthCheck(kotlin.time.Duration.parse("2s"))
            assertTrue(live, "Live relay must answer Ping with matching Pong")
        } finally {
            tunnelClient.disconnect()
            coroutineContext.cancelChildren()
        }
    }

    /**
     * When the relay process is killed mid-session, healthCheck should return
     * false within the timeout and the TunnelClient must transition out of
     * ACTIVE/CONNECTED. This is the load-bearing guarantee for
     * [TunnelForegroundService]'s wake-path decision: on a dead socket, the
     * service must fall through to reconnect instead of skipping.
     */
    @Test
    fun `healthCheck fails and state flips when relay is killed`() = runBlocking {
        val tunnelClient = connectTunnelClient()
        try {
            // Baseline: relay is up, healthCheck must succeed.
            assertTrue(
                tunnelClient.healthCheck(kotlin.time.Duration.parse("2s")),
                "Prereq: live relay should answer Ping"
            )

            // Kill the relay process. The TCP reset may take a moment to
            // propagate back; healthCheck with a bounded deadline must still
            // return false, and the state must eventually flip out of
            // CONNECTED/ACTIVE.
            relayManager.stop()

            val live = tunnelClient.healthCheck(kotlin.time.Duration.parse("2s"))
            assertTrue(!live, "healthCheck must return false after relay killed")

            withTimeout(30_000) {
                while (tunnelClient.state.value == TunnelState.CONNECTED ||
                    tunnelClient.state.value == TunnelState.ACTIVE
                ) {
                    delay(100)
                }
            }
            assertEquals(TunnelState.DISCONNECTED, tunnelClient.state.value)
        } finally {
            runCatching { tunnelClient.disconnect() }
            coroutineContext.cancelChildren()
        }
    }

    // Note: scenario 21 (multiple simultaneous TunnelClientImpl connections
    // with the same device cert) is intentionally omitted. The relay's behavior
    // when two connections present the same device cert is undefined -- it may
    // route to either or neither. This is a relay-level concern, not a
    // TunnelClientImpl concern.

    // =========================================================================
    // Helper: TunnelClientImpl connection
    // =========================================================================

    /**
     * Connect a [TunnelClientImpl] to the relay using [MtlsWebSocketFactory].
     * This exercises the production code path.
     */
    private suspend fun connectTunnelClient(): TunnelClientImpl {
        val sslContext = TestSslContexts.buildMtls(deviceKeyStore, caCert)
        val wsFactory = MtlsWebSocketFactory(sslContext)
        val client = TunnelClientImpl(
            scope = kotlinx.coroutines.CoroutineScope(Dispatchers.IO),
            webSocketFactory = wsFactory
        )
        client.connect("wss://$RELAY_HOSTNAME:$relayPort/ws")
        assertEquals(
            TunnelState.CONNECTED,
            client.state.value,
            "TunnelClient should be CONNECTED after connect()"
        )
        // The Kotlin side sees CONNECTED as soon as the WebSocket handshake
        // completes, but the relay's handle_mux_session body (which does
        // Firestore auto-create + session_registry.insert) runs in a separate
        // tokio task afterwards. Give it a moment so subsequent AI-client
        // connections find the session registered.
        delay(SESSION_REGISTRATION_DELAY_MS)
        return client
    }

    // =========================================================================
    // Helper: Raw WebSocket connection (for relay-specific tests)
    // =========================================================================

    private data class DeviceConnection(val ws: WebSocket, val listener: CollectingListener)

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
     * Connect a "device" via raw mTLS WebSocket (bypassing TunnelClientImpl).
     * Used only for scenarios that need to inject specific mux frames.
     */
    private fun connectDeviceMuxRaw(): DeviceConnection {
        val sslContext = TestSslContexts.buildMtls(deviceKeyStore, caCert)
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

    // =========================================================================
    // Helper: AI client connections
    // =========================================================================

    /**
     * Connect an "AI client" to the relay with SNI routing to the device.
     * Returns an SSLSocket whose TLS handshake completes through the relay.
     */
    private fun connectAiClient(): SSLSocket {
        val factory = TestSslContexts.buildAiClientSocketFactory(caCert, deviceCert)
        val socket = factory.createSocket(
            "127.0.0.1",
            relayPort
        ) as SSLSocket

        socket.soTimeout = 10_000
        val params = socket.sslParameters
        params.serverNames = listOf(javax.net.ssl.SNIHostName(CLIENT_SNI))
        socket.sslParameters = params
        socket.startHandshake()
        return socket
    }

    /**
     * Connect an "AI client" via raw TCP with a synthetic TLS ClientHello.
     * Use for tests that need relay routing but not full TLS handshake.
     */
    private fun connectRawAiClient(subdomain: String = DEVICE_SUBDOMAIN): java.net.Socket {
        val socket = java.net.Socket("127.0.0.1", relayPort)
        socket.soTimeout = 10_000

        val sniHost = "$INTEGRATION_SECRET.$subdomain.$RELAY_HOSTNAME"
        val clientHello = buildSyntheticClientHello(sniHost)
        socket.outputStream.write(clientHello)
        socket.outputStream.flush()
        return socket
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
        error("Timed out waiting for frame after ${timeoutMs}ms")
    }
}
