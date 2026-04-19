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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Regression guard for issue #243 — "always reconnect on FCM wake, never
 * trust stale tunnel state".
 *
 * Setup: provision the device and bring the tunnel to [TunnelState.CONNECTED].
 * The relay-side WebSocket is then killed *without a close frame* via
 * [com.rousecontext.tunnel.integration.TestRelayManager.killActiveWebsocket],
 * which leaves the device-side TCP socket in a half-open state — reads hang
 * forever, writes silently succeed into the kernel's send buffer, and
 * nothing at the application layer knows the peer is gone.
 *
 * The mux keepalive job then fires periodic Pings (accelerated timings in
 * [TunnelTestSupport]: 2s interval, 1s timeout, 3 misses) and drives the
 * tunnel to [TunnelState.DISCONNECTED] once the miss budget is exhausted.
 * That is the #243 behaviour we want to lock in — before the fix, a stale
 * ACTIVE state would have caused the next FCM wake to skip the reconnect
 * entirely.
 *
 * Scope note (#262): this scenario exercises the device-side TunnelClient
 * recovery path only. It does not route a synthetic AI client through the
 * relay, so the Conscrypt SNI suppression that blocks batch B is not in
 * play here.
 */
@RunWith(RobolectricTestRunner::class)
class HalfOpenReconnectTest {

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
    fun `half-open socket drives tunnel to DISCONNECTED via keepalive`() = runBlocking {
        val subdomain = harness.provisionDevice()

        val tunnel = TunnelTestSupport.buildTunnel(harness, tunnelScope)
        tunnel.connect(TunnelTestSupport.tunnelUrl(harness))

        // Sanity: tunnel reached CONNECTED through the real TLS + WS handshake.
        assertEquals(
            "tunnel should be CONNECTED before we kill the relay-side socket",
            TunnelState.CONNECTED,
            tunnel.state.value
        )

        // Drop the relay-side WebSocket without a close frame. The device-side
        // TCP socket is now half-open — see [TcpDropProxy] in the
        // `:core:tunnel:jvmTest` HalfOpenDetectionTest for the kernel-level
        // semantics that match this behaviour.
        val killed = harness.fixture.killActiveWebsocket(subdomain)
        assertTrue(
            "fixture must have had a live session registered to kill",
            killed
        )

        // Keepalive should detect the dead connection within
        // ~interval * max_misses = ~6s; allow generous margin for the
        // Robolectric JVM + subprocess scheduling jitter.
        tunnel.awaitState(TunnelState.DISCONNECTED, timeout = 30.seconds)

        assertEquals(
            "tunnel must transition to DISCONNECTED after keepalive exhaustion",
            TunnelState.DISCONNECTED,
            tunnel.state.value
        )

        // The FCM wake hook (sendFcmWake) is orthogonal to this regression:
        // #243 is about NOT skipping reconnect on FCM wake when state looks
        // ACTIVE. We've verified the pre-condition (state correctly moves to
        // DISCONNECTED after a silent drop), which is what the decider in
        // :work/WakeReconnectDecider.kt already handles with a Reconnect
        // action. Calling sendFcmWake here would require re-driving the
        // device-side TunnelForegroundService, which is covered in
        // IdleTimeoutWakeTest below.
        harness.fixture.sendFcmWake(subdomain)
    }
}
