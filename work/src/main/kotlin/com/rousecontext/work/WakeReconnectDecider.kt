package com.rousecontext.work

import com.rousecontext.tunnel.TunnelClient
import com.rousecontext.tunnel.TunnelState
import kotlin.time.Duration

/**
 * Decides whether a wake-up (FCM, Start command, etc.) should trigger a new
 * connect or if one is already in progress.
 *
 * Split out from [TunnelForegroundService] for unit testing -- the service
 * itself is awkward to instantiate in a JVM test.
 *
 * History:
 * - #179: stopped trusting `state == ACTIVE` as "already connected, skip" and
 *   introduced a mux-level Ping probe before skipping.
 * - #243: removed the Skip path entirely. An FCM wake means the relay needs a
 *   fresh WebSocket — it would not have sent the push if it already had one.
 *   Even when the device's health check Ping/Pong succeeds (the TCP socket is
 *   alive), the relay's routing table no longer maps this device to that
 *   WebSocket. Skipping reconnect leaves the relay stuck waiting, and every
 *   inbound MCP call fails. The health check is still performed for
 *   diagnostics: [WakeAction.Reconnect.wasStale] == true means the tunnel was
 *   confirmed dead, false means it looked alive but the relay disagrees.
 */
sealed class WakeAction {

    /**
     * Tear down the current tunnel (if any) and establish a fresh one.
     * [wasStale] indicates the tunnel claimed ACTIVE but failed its probe —
     * useful for logging so the monotonic "tunnel stuck" bug is visible.
     */
    data class Reconnect(val wasStale: Boolean) : WakeAction()

    /** Already connecting; another coroutine will handle it. */
    object AlreadyConnecting : WakeAction()
}

object WakeReconnectDecider {

    /**
     * Inspect the tunnel and decide what to do.
     *
     * Always returns [WakeAction.Reconnect] unless a connect is already in
     * flight. When the tunnel is ACTIVE, a health check Ping is still sent so
     * [WakeAction.Reconnect.wasStale] can distinguish "confirmed dead" from
     * "looked alive but relay lost the connection" in logs.
     */
    suspend fun decide(tunnelClient: TunnelClient, healthCheckTimeout: Duration): WakeAction =
        when (tunnelClient.state.value) {
            TunnelState.CONNECTING -> WakeAction.AlreadyConnecting
            TunnelState.ACTIVE -> {
                val live = try {
                    tunnelClient.healthCheck(healthCheckTimeout)
                } catch (_: Exception) {
                    false
                }
                WakeAction.Reconnect(wasStale = !live)
            }
            TunnelState.CONNECTED,
            TunnelState.DISCONNECTED,
            TunnelState.DISCONNECTING -> WakeAction.Reconnect(wasStale = false)
        }
}
