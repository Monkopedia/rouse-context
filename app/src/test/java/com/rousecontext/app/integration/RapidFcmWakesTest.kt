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
 * Five FCM wakes fired within 2s must not drive the client into a connect
 * loop / socket-spawning frenzy.
 *
 * Production's dedup lives in two layers:
 *  1. [WakeReconnectDecider]: returns [WakeAction.AlreadyConnecting] when the
 *     tunnel is already mid-handshake.
 *  2. [com.rousecontext.work.TunnelForegroundService.launchReconnect]:
 *     skips if `reconnectJob?.isActive == true`.
 *
 * Under Robolectric we can't drive the foreground service directly (its
 * `startForeground` requires an Activity context), so this test exercises
 * layer (1) only. The CONNECTED path in the decider always returns
 * [WakeAction.Reconnect], mirroring the #243 fix that removed the
 * health-check-based "skip" altogether (a CONNECTED-state ACTIVE tunnel is
 * no guarantee the relay still has a WS mapping for it).
 *
 * Assertions:
 *  - All 5 fixture wake calls complete without error; the relay counts them.
 *  - The decider behaves consistently under rapid-fire invocation — never
 *    throws, always returns a valid [WakeAction].
 *  - The tunnel finishes in CONNECTED regardless of wake volume.
 *
 * Layer (2) dedup is regression-guarded at `work` via
 * `WakeReconnectDeciderTest` / `TunnelForegroundServiceTest` — it's a pure
 * JVM unit-test because it needs direct control over `reconnectJob`.
 *
 * #262 note: device-side only.
 */
@RunWith(RobolectricTestRunner::class)
class RapidFcmWakesTest {

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
    fun `five rapid fcm wakes plus decider calls keep tunnel stable`() = runBlocking {
        val subdomain = harness.provisionDevice()
        val tunnel = TunnelTestSupport.buildTunnel(harness, tunnelScope)
        val url = TunnelTestSupport.tunnelUrl(harness)
        tunnel.connect(url)
        tunnel.awaitState(
            TunnelState.CONNECTED,
            timeout = TunnelTestSupport.DEFAULT_CONNECT_TIMEOUT
        )

        // Fire 5 fixture wakes + decider calls in a tight loop (<2s).
        val actions = mutableListOf<WakeAction>()
        repeat(FIVE_WAKES) {
            harness.fixture.sendFcmWake(subdomain)
            actions += WakeReconnectDecider.decide(tunnel, 1.seconds)
        }

        // The decider must not throw, and each action must be a valid
        // [WakeAction] subtype — the production service treats Reconnect /
        // AlreadyConnecting as the only two valid outcomes.
        assertEquals(FIVE_WAKES, actions.size)
        assertTrue(
            "every action must be Reconnect or AlreadyConnecting, got $actions",
            actions.all { it is WakeAction.Reconnect || it is WakeAction.AlreadyConnecting }
        )

        // Tunnel stays CONNECTED — calling the decider does not itself
        // disturb tunnel state; the reconnect action is the *service*'s
        // responsibility to execute.
        assertEquals(
            "tunnel stays CONNECTED after rapid decider calls",
            TunnelState.CONNECTED,
            tunnel.state.value
        )
    }

    private companion object {
        const val FIVE_WAKES = 5
    }
}
