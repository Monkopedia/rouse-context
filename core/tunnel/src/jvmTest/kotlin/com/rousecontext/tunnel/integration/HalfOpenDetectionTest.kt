package com.rousecontext.tunnel.integration

import com.rousecontext.tunnel.TunnelClientImpl
import com.rousecontext.tunnel.TunnelState
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
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
 * Integration test that verifies the tunnel keepalive detects a half-open TCP
 * socket. A [TcpDropProxy] sits between the tunnel client and the relay; when
 * [TcpDropProxy.dropTraffic] is flipped, reads continue consuming bytes but
 * writes to the other side are silently discarded. The kernel TCP stack stays
 * healthy — only application-layer Ping/Pong frames are lost.
 *
 * With accelerated keepalive timings (2s interval, 1s timeout, 3 misses), the
 * tunnel should detect the dead connection within ~9s instead of the
 * production ~120s.
 */
@Tag("integration")
@Timeout(value = 240, unit = TimeUnit.SECONDS)
class HalfOpenDetectionTest {

    companion object {
        private const val RELAY_HOSTNAME = "localhost"
        private const val DEVICE_SUBDOMAIN = "test-halfopen"

        /** Accelerated keepalive: 2s interval instead of 30s. */
        private const val KEEPALIVE_INTERVAL_MS = 2_000L

        /** Accelerated keepalive: 1s per-ping timeout instead of 10s. */
        private const val KEEPALIVE_TIMEOUT_MS = 1_000L

        /** Same miss count as production. */
        private const val KEEPALIVE_MAX_MISSES = 3

        /**
         * Upper bound for [TestRelayManager.waitForSessionRegistered]. The
         * relay-side `Notify` typically fires within a millisecond of the
         * mux WebSocket upgrade completing; 10s is generous for CI under
         * stress (#400).
         */
        private const val SESSION_REGISTRATION_TIMEOUT_MS = 10_000L
    }

    private lateinit var tempDir: File
    private lateinit var ca: TestCertificateAuthority
    private lateinit var relayManager: TestRelayManager
    private var relayPort: Int = 0
    private lateinit var proxy: TcpDropProxy

    private val caCert: X509Certificate get() = ca.caCert
    private val deviceKeyStore: KeyStore get() = ca.deviceKeyStore

    @BeforeEach
    fun setUp() {
        val relayBinary = findRelayBinary()
        assumeTrue(
            relayBinary.exists() && relayBinary.canExecute(),
            "Relay binary not found. Build with: cd relay && cargo build --features test-mode"
        )

        tempDir = File.createTempFile("e2e-halfopen-", "")
        tempDir.delete()
        tempDir.mkdirs()

        ca = TestCertificateAuthority(tempDir, RELAY_HOSTNAME, DEVICE_SUBDOMAIN)
        ca.generate()

        // test-mode enables the `/test/wait-session-registered` admin
        // endpoint used by `connectTunnelClient` to deterministically wait
        // for the relay-side `SessionRegistry.insert` instead of a blind
        // 500ms sleep (#400).
        relayManager = TestRelayManager(tempDir, RELAY_HOSTNAME, enableTestMode = true)
        relayPort = findFreePort()
        relayManager.start(relayPort)

        proxy = TcpDropProxy(targetHost = "127.0.0.1", targetPort = relayPort)
    }

    @AfterEach
    fun tearDown() {
        if (::proxy.isInitialized) {
            proxy.stop()
        }
        if (::relayManager.isInitialized) {
            relayManager.stop()
        }
        if (::tempDir.isInitialized && tempDir.exists()) {
            tempDir.deleteRecursively()
        }
    }

    /**
     * Establishes a tunnel through the proxy, verifies CONNECTED state,
     * then drops all traffic and asserts keepalive drives the state to
     * DISCONNECTED within the expected time window.
     */
    @Test
    @Timeout(value = 180, unit = TimeUnit.SECONDS)
    fun `half-open socket detected by keepalive within expected window`() = runBlocking {
        val proxyScope = CoroutineScope(Dispatchers.IO)
        proxy.start(proxyScope)

        val tunnelClient = connectTunnelClient()

        try {
            // Verify tunnel reached CONNECTED
            assertEquals(
                TunnelState.CONNECTED,
                tunnelClient.state.value,
                "Tunnel should be CONNECTED before dropping traffic"
            )

            val dropTimeMs = System.currentTimeMillis()

            // Drop all traffic — simulates NAT rebind / WiFi handoff
            proxy.dropTraffic.set(true)

            // Wait for the tunnel to detect the dead connection.
            // With 2s interval, 1s timeout, 3 misses: worst case ~9s.
            // Give generous margin: 30s.
            val disconnected = withTimeout(30_000) {
                tunnelClient.state.first { it == TunnelState.DISCONNECTED }
            }

            val detectionTimeMs = System.currentTimeMillis() - dropTimeMs
            println(
                "Half-open detection took ${detectionTimeMs}ms " +
                    "(expected range: ~${KEEPALIVE_INTERVAL_MS * KEEPALIVE_MAX_MISSES}ms)"
            )

            assertEquals(
                TunnelState.DISCONNECTED,
                disconnected,
                "Tunnel should be DISCONNECTED after keepalive exhaustion"
            )

            // Detection should take at least (interval * misses) minus some
            // jitter, but not wildly longer.
            val minExpectedMs = KEEPALIVE_INTERVAL_MS * KEEPALIVE_MAX_MISSES
            assertTrue(
                detectionTimeMs >= minExpectedMs - KEEPALIVE_INTERVAL_MS,
                "Detection was suspiciously fast: ${detectionTimeMs}ms " +
                    "(expected >= ~${minExpectedMs - KEEPALIVE_INTERVAL_MS}ms)"
            )
        } finally {
            try {
                tunnelClient.disconnect()
            } catch (_: Exception) {
                // Already disconnected
            }
            proxyScope.coroutineContext.cancelChildren()
        }
    }

    private suspend fun connectTunnelClient(): TunnelClientImpl {
        val sslContext = TestSslContexts.buildMtls(deviceKeyStore, caCert)
        val wsFactory = MtlsWebSocketFactory(sslContext)
        val client = TunnelClientImpl(
            scope = CoroutineScope(Dispatchers.IO),
            webSocketFactory = wsFactory,
            keepaliveIntervalMillis = KEEPALIVE_INTERVAL_MS,
            keepaliveTimeoutMillis = KEEPALIVE_TIMEOUT_MS,
            keepaliveMaxMisses = KEEPALIVE_MAX_MISSES
        )
        // Connect through proxy, not directly to relay
        client.connect("wss://$RELAY_HOSTNAME:${proxy.port}/ws")
        assertEquals(
            TunnelState.CONNECTED,
            client.state.value,
            "TunnelClient should be CONNECTED after connect()"
        )
        // Deterministic wait for the relay's `SessionRegistry.insert` after
        // the WS upgrade completes. Replaces the former 500ms blind sleep
        // (#400). Backed by per-subdomain `Notify` on the relay, exposed via
        // the test-mode admin endpoint.
        val registered = relayManager.waitForSessionRegistered(
            DEVICE_SUBDOMAIN,
            SESSION_REGISTRATION_TIMEOUT_MS
        )
        assertTrue(
            registered,
            "Relay did not register mux session for $DEVICE_SUBDOMAIN within " +
                "${SESSION_REGISTRATION_TIMEOUT_MS}ms"
        )
        return client
    }
}

/**
 * A simple JVM TCP proxy that forwards bytes between two sockets.
 *
 * When [dropTraffic] is `true`, reads still consume bytes from both sides
 * (keeping the TCP connection alive at the kernel level), but the bytes are
 * silently discarded instead of being written to the other side. This
 * simulates a half-open socket: the kernel TCP stack is fine, but nothing
 * reaches the remote peer.
 */
class TcpDropProxy(private val targetHost: String, private val targetPort: Int) {
    /** The port the proxy is listening on. */
    val port: Int

    /** When true, bytes are consumed but not forwarded. */
    val dropTraffic = AtomicBoolean(false)

    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null

    init {
        val ss = ServerSocket(0)
        port = ss.localPort
        serverSocket = ss
    }

    /**
     * Start accepting connections. Each accepted client socket gets a pair
     * of forwarding coroutines (client->target and target->client).
     */
    fun start(scope: CoroutineScope) {
        val ss = serverSocket ?: return
        acceptJob = scope.launch {
            while (isActive) {
                val clientSocket = try {
                    ss.accept()
                } catch (_: Exception) {
                    break
                }

                val targetSocket = try {
                    Socket(targetHost, targetPort)
                } catch (e: Exception) {
                    clientSocket.close()
                    continue
                }

                // client -> target
                launch(Dispatchers.IO) {
                    forwardBytes(clientSocket, targetSocket)
                }
                // target -> client
                launch(Dispatchers.IO) {
                    forwardBytes(targetSocket, clientSocket)
                }
            }
        }
    }

    fun stop() {
        acceptJob?.cancel()
        acceptJob = null
        try {
            serverSocket?.close()
        } catch (_: Exception) {
            // Ignore
        }
        serverSocket = null
    }

    @Suppress("TooGenericExceptionCaught", "LoopWithTooManyJumpStatements")
    private fun forwardBytes(from: Socket, to: Socket) {
        val buf = ByteArray(8192)
        try {
            val input = from.getInputStream()
            val output = to.getOutputStream()
            while (!from.isClosed && !to.isClosed) {
                val n = input.read(buf)
                if (n < 0) break
                if (!dropTraffic.get()) {
                    output.write(buf, 0, n)
                    output.flush()
                }
                // When dropTraffic is true: read consumed the bytes (keeping
                // the TCP window open), but we don't write them. The remote
                // peer's application-layer Ping never arrives.
            }
        } catch (_: Exception) {
            // Connection broken — expected during teardown
        } finally {
            try {
                from.close()
            } catch (_: Exception) {
                // ignore
            }
            try {
                to.close()
            } catch (_: Exception) {
                // ignore
            }
        }
    }
}
