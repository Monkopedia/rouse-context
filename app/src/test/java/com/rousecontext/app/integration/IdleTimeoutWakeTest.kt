package com.rousecontext.app.integration

import com.rousecontext.app.integration.TunnelTestSupport.awaitState
import com.rousecontext.app.integration.harness.AppIntegrationTestHarness
import com.rousecontext.tunnel.TunnelState
import com.rousecontext.work.WakeAction
import com.rousecontext.work.WakeReconnectDecider
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Verifies the "idle timeout + wake" recovery: after a relay-side idle
 * disconnect, a fresh FCM wake must drive the tunnel back to [TunnelState.CONNECTED].
 *
 * We don't have a clean way to advance the relay's 5-minute idle wall clock
 * from the test process, so [com.rousecontext.tunnel.integration.TestRelayManager.killActiveWebsocket]
 * is used as the closest proxy — it produces the same device-side observation
 * an idle-timeout close would (the relay drops the socket without warning).
 * The important assertion is that the *decider* (#243) picks a
 * [WakeAction.Reconnect] and a subsequent `connect` brings the tunnel back up.
 *
 * #262 note: this scenario is entirely device-side, no synthetic AI client
 * is routed through the relay's SNI passthrough.
 */
@RunWith(RobolectricTestRunner::class)
class IdleTimeoutWakeTest {

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
    fun `fcm wake after idle-equivalent drop reconnects the tunnel`() = runBlocking {
        val subdomain = harness.provisionDevice()

        val tunnel = TunnelTestSupport.buildTunnel(harness, tunnelScope)
        val url = TunnelTestSupport.tunnelUrl(harness)
        tunnel.connect(url)
        tunnel.awaitState(
            TunnelState.CONNECTED,
            timeout = TunnelTestSupport.DEFAULT_CONNECT_TIMEOUT
        )

        // --- Simulate relay-side idle close. ---
        // The production relay would have closed the WebSocket after ~5 min
        // idle; here we drop it synchronously so the test finishes in seconds.
        assertTrue(harness.fixture.killActiveWebsocket(subdomain))

        // Mux keepalive drives the local state machine to DISCONNECTED.
        tunnel.awaitState(TunnelState.DISCONNECTED, timeout = 30.seconds)

        // Relay delivers an FCM wake to the device: bump the fixture-side
        // counter and drive the decider, mimicking what FcmReceiver would
        // do inside TunnelForegroundService.
        harness.fixture.sendFcmWake(subdomain)
        val action = WakeReconnectDecider.decide(tunnel, healthCheckTimeout = 2.seconds)
        assertTrue(
            "decider must pick Reconnect on a dead tunnel, got $action",
            action is WakeAction.Reconnect
        )

        // Service would call connect on the Reconnect action — do so directly.
        tunnel.connect(url)
        tunnel.awaitState(
            TunnelState.CONNECTED,
            timeout = TunnelTestSupport.DEFAULT_CONNECT_TIMEOUT
        )

        assertEquals(
            "tunnel must be CONNECTED again after the FCM wake + reconnect cycle",
            TunnelState.CONNECTED,
            tunnel.state.value
        )
    }
}
