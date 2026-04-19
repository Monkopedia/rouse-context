package com.rousecontext.app.integration

import com.rousecontext.app.integration.TunnelTestSupport.awaitState
import com.rousecontext.app.integration.harness.AppIntegrationTestHarness
import com.rousecontext.tunnel.TunnelState
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Verifies the client recovers after the relay process is killed and
 * restarted. The device-side keepalive should detect the dead connection via
 * ping timeout and flip the tunnel to [TunnelState.DISCONNECTED]; once the
 * relay is back up and an FCM wake arrives, the device reconnects cleanly.
 *
 * Procedure:
 *  1. Provision + bring tunnel up.
 *  2. `relayManager.stop()` — terminate the relay subprocess without warning.
 *  3. Assert tunnel reaches DISCONNECTED via mux keepalive.
 *  4. `relayManager.start(port)` — restart the relay on the same port so the
 *     device's cached `wss://relay.test.local:<port>/ws` still works.
 *  5. Simulate FCM wake and call connect; assert CONNECTED.
 *
 * #262 note: client-side only; no AI-client SNI handling.
 */
@RunWith(RobolectricTestRunner::class)
class RelayRestartTest {

    private val harness = AppIntegrationTestHarness()
    private lateinit var tunnelScope: CoroutineScope

    @Before
    fun setUp() {
        harness.start()
        tunnelScope = TunnelTestSupport.newTunnelScope()
    }

    @After
    fun tearDown() {
        tunnelScope.cancel()
        harness.stop()
    }

    @Test
    fun `client reconnects after relay subprocess restart`() = runBlocking {
        val subdomain = harness.provisionDevice()

        val tunnel = TunnelTestSupport.buildTunnel(harness, tunnelScope)
        val url = TunnelTestSupport.tunnelUrl(harness)
        tunnel.connect(url)
        tunnel.awaitState(
            TunnelState.CONNECTED,
            timeout = TunnelTestSupport.DEFAULT_CONNECT_TIMEOUT
        )

        // --- Kill the relay subprocess. ---
        // The device-side WebSocket is now pointed at a dead TCP peer. The
        // kernel will RST on the next write, and keepalive Pings will fail
        // fast — so we expect DISCONNECTED well within the 30s bound.
        harness.fixture.stop()

        tunnel.awaitState(TunnelState.DISCONNECTED, timeout = 30.seconds)

        // --- Restart the relay on the same port. ---
        // The fixture's cert files / base config remain on disk from the
        // original [start], so this is effectively a process recycle.
        harness.fixture.start(harness.relayPort)

        // --- FCM wake + reconnect. ---
        harness.fixture.sendFcmWake(subdomain)
        tunnel.connect(url)
        tunnel.awaitState(
            TunnelState.CONNECTED,
            timeout = TunnelTestSupport.DEFAULT_CONNECT_TIMEOUT
        )

        assertEquals(
            "tunnel must be CONNECTED again after relay restart + wake",
            TunnelState.CONNECTED,
            tunnel.state.value
        )
    }
}
