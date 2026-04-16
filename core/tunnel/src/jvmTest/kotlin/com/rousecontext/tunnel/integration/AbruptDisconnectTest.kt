package com.rousecontext.tunnel.integration

import com.rousecontext.tunnel.TunnelClientImpl
import com.rousecontext.tunnel.TunnelState
import java.io.File
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

/**
 * Integration test that verifies the tunnel client handles an abrupt
 * relay death (simulating NAT rebind / Wi-Fi handoff where the remote
 * end vanishes without a FIN or close frame).
 *
 * Unlike [HalfOpenDetectionTest] which uses a [TcpDropProxy] to silently
 * discard traffic while keeping the TCP socket alive, this test kills
 * the relay process with [Process.destroyForcibly]. The OS delivers an
 * immediate socket error (RST or EOF), so detection should be much
 * faster than keepalive-based detection.
 *
 * The test then restarts the relay on the same port and verifies the
 * client can reconnect by calling [TunnelClientImpl.connect] manually.
 */
@Tag("integration")
class AbruptDisconnectTest {

    companion object {
        private const val RELAY_HOSTNAME = "localhost"
        private const val DEVICE_SUBDOMAIN = "test-abrupt"

        /** Accelerated keepalive: 2s interval instead of 30s. */
        private const val KEEPALIVE_INTERVAL_MS = 2_000L

        /** Accelerated keepalive: 1s per-ping timeout instead of 10s. */
        private const val KEEPALIVE_TIMEOUT_MS = 1_000L

        /** Same miss count as production. */
        private const val KEEPALIVE_MAX_MISSES = 3

        /** Session registration delay — relay needs time after WS upgrade. */
        private const val SESSION_REGISTRATION_DELAY_MS = 500L
    }

    private lateinit var tempDir: File
    private lateinit var ca: TestCertificateAuthority
    private lateinit var relayManager: TestRelayManager
    private var relayPort: Int = 0

    private val caCert: X509Certificate get() = ca.caCert
    private val deviceKeyStore: KeyStore get() = ca.deviceKeyStore

    @BeforeEach
    fun setUp() {
        val relayBinary = findRelayBinary()
        assumeTrue(
            relayBinary.exists() && relayBinary.canExecute(),
            "Relay binary not found. Build with: cd relay && cargo build"
        )

        tempDir = File.createTempFile("e2e-abrupt-", "")
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

    /**
     * Establishes a tunnel, kills the relay process forcibly, then asserts
     * the tunnel reaches DISCONNECTED cleanly. Restarts the relay on the
     * same port and verifies a manual reconnect succeeds.
     */
    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    fun `abrupt relay death detected and manual reconnect succeeds`() = runBlocking {
        val tunnelScope = CoroutineScope(Dispatchers.IO)
        val tunnelClient = connectTunnelClient(tunnelScope)

        try {
            // Phase 1: verify CONNECTED
            assertEquals(
                TunnelState.CONNECTED,
                tunnelClient.state.value,
                "Tunnel should be CONNECTED before relay kill"
            )

            val killTimeMs = System.currentTimeMillis()

            // Phase 2: kill the relay forcibly (no graceful close frame)
            relayManager.process!!.destroyForcibly()
            relayManager.process!!.waitFor(5, TimeUnit.SECONDS)

            // Phase 3: wait for DISCONNECTED
            val disconnected = withTimeout(30_000) {
                tunnelClient.state.first { it == TunnelState.DISCONNECTED }
            }

            val detectionTimeMs = System.currentTimeMillis() - killTimeMs
            println(
                "Abrupt disconnect detection took ${detectionTimeMs}ms"
            )

            assertEquals(
                TunnelState.DISCONNECTED,
                disconnected,
                "Tunnel should be DISCONNECTED after relay kill"
            )

            // OS-level socket error should be near-instant (well under
            // keepalive budget), but we allow up to the full keepalive
            // window in case the WebSocket library buffers the error.
            val maxExpectedMs = KEEPALIVE_INTERVAL_MS * KEEPALIVE_MAX_MISSES +
                KEEPALIVE_TIMEOUT_MS * KEEPALIVE_MAX_MISSES
            assertTrue(
                detectionTimeMs < maxExpectedMs,
                "Detection took ${detectionTimeMs}ms, expected < ${maxExpectedMs}ms"
            )

            // Phase 4: restart the relay on the same port
            relayManager.stop() // clean up the dead process reference
            relayManager = TestRelayManager(tempDir, RELAY_HOSTNAME)
            relayManager.start(relayPort)

            // Small delay to let the new relay fully initialize
            delay(SESSION_REGISTRATION_DELAY_MS)

            // Phase 5: reconnect and verify CONNECTED
            val reconnectStartMs = System.currentTimeMillis()
            tunnelClient.connect("wss://$RELAY_HOSTNAME:$relayPort/ws")

            assertEquals(
                TunnelState.CONNECTED,
                tunnelClient.state.value,
                "Tunnel should be CONNECTED after reconnecting to new relay"
            )

            val reconnectTimeMs = System.currentTimeMillis() - reconnectStartMs
            println("Reconnect took ${reconnectTimeMs}ms")
        } finally {
            try {
                tunnelClient.disconnect()
            } catch (_: Exception) {
                // Already disconnected
            }
            tunnelScope.coroutineContext.cancelChildren()
        }
    }

    private suspend fun connectTunnelClient(scope: CoroutineScope): TunnelClientImpl {
        val sslContext = TestSslContexts.buildMtls(deviceKeyStore, caCert)
        val wsFactory = MtlsWebSocketFactory(sslContext)
        val client = TunnelClientImpl(
            scope = scope,
            webSocketFactory = wsFactory,
            keepaliveIntervalMillis = KEEPALIVE_INTERVAL_MS,
            keepaliveTimeoutMillis = KEEPALIVE_TIMEOUT_MS,
            keepaliveMaxMisses = KEEPALIVE_MAX_MISSES
        )
        client.connect("wss://$RELAY_HOSTNAME:$relayPort/ws")
        assertEquals(
            TunnelState.CONNECTED,
            client.state.value,
            "TunnelClient should be CONNECTED after connect()"
        )
        delay(SESSION_REGISTRATION_DELAY_MS)
        return client
    }
}
