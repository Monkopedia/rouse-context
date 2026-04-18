package com.rousecontext.tunnel.integration

import com.rousecontext.tunnel.TunnelClientImpl
import com.rousecontext.tunnel.TunnelState
import java.io.File
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

/**
 * Smoke tests for the test-mode admin surface exposed by [TestRelayAdmin]
 * (see issue #249).
 *
 * Coverage:
 *  - Relay starts cleanly with `--test-mode <port>`.
 *  - `/test/stats` is reachable and reports zero counters initially.
 *  - `/test/fcm-wake` captures synthetic wake events.
 *  - `/test/kill-ws` aborts the active mux WebSocket; the device-side
 *    `TunnelClient.healthCheck()` detects the drop and returns `false`.
 *
 * This test is lightweight by design — deeper coverage of the scenarios
 * enabled by the admin surface lives in #251 / #252 / #253.
 */
@Tag("integration")
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class TestRelayAdminSmokeTest {

    companion object {
        private const val RELAY_HOSTNAME = "localhost"
        private const val DEVICE_SUBDOMAIN = "smoke-admin"

        /** Accelerated keepalive so we detect the kill within the test window. */
        private const val KEEPALIVE_INTERVAL_MS = 2_000L
        private const val KEEPALIVE_TIMEOUT_MS = 1_000L
        private const val KEEPALIVE_MAX_MISSES = 3

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
            "Relay binary not found. Build with: cd relay && cargo build --features test-mode"
        )

        tempDir = File.createTempFile("admin-smoke-", "")
        tempDir.delete()
        tempDir.mkdirs()

        ca = TestCertificateAuthority(tempDir, RELAY_HOSTNAME, DEVICE_SUBDOMAIN)
        ca.generate()

        relayManager = TestRelayManager(
            tempDir = tempDir,
            relayHostname = RELAY_HOSTNAME,
            enableTestMode = true
        )
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
     * The admin server answers, initial counters are zero, and the smoke-test
     * fake-wake hook records entries as expected.
     *
     * If the binary was built without `--features test-mode`, the relay
     * prints a warning and ignores `--test-mode`, the admin readiness poll
     * fails, and `start()` fails — skip this test in that case.
     */
    @Test
    fun `admin server serves stats and captures synthetic wakes`() {
        val admin = assertNotNull(
            relayManager.admin,
            "test-mode admin should be live when enableTestMode = true"
        )

        val initial = admin.stats()
        assertEquals(0, initial.registerCalls)
        assertEquals(0, initial.renewCalls)
        assertEquals(0, initial.rotateSecretCalls)

        relayManager.sendFcmWake("alpha")
        relayManager.sendFcmWake("beta")

        val captured = admin.capturedWakes()
        assertTrue(
            captured.containsAll(listOf("alpha", "beta")),
            "captured wakes should include both subdomains, got=$captured"
        )
    }

    /**
     * After a tunnel is established and `killActiveWebsocket()` is invoked,
     * `TunnelClient.healthCheck()` must return `false` — the transport is dead
     * and the next ping exchange will time out. This is the #243 regression
     * guard that scenario tests (#253) will build on.
     */
    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    fun `killActiveWebsocket drives healthCheck false within 5s`() = runBlocking {
        val tunnelClient = connectTunnelClient()

        try {
            assertEquals(
                TunnelState.CONNECTED,
                tunnelClient.state.value,
                "Tunnel should be CONNECTED before kill"
            )

            val killed = relayManager.killActiveWebsocket(DEVICE_SUBDOMAIN)
            assertTrue(killed, "relay should have had a live session to kill")

            // Within ~5s the keepalive should drive healthCheck to false.
            // We poll so transient retries don't flake the assertion.
            val healthy = withTimeoutOrNull(8.seconds) {
                while (tunnelClient.healthCheck(5.seconds)) {
                    delay(200)
                }
                false
            }
            assertFalse(
                healthy ?: true,
                "healthCheck should return false after relay-side kill"
            )

            // Final state should reflect disconnect (or actively disconnecting).
            val state = withTimeoutOrNull(10_000) {
                tunnelClient.state.first { it == TunnelState.DISCONNECTED }
            }
            assertEquals(TunnelState.DISCONNECTED, state)
        } finally {
            runCatching { tunnelClient.disconnect() }
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
        client.connect("wss://$RELAY_HOSTNAME:$relayPort/ws")
        delay(SESSION_REGISTRATION_DELAY_MS)
        return client
    }
}
