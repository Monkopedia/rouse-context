package com.rousecontext.work

import com.rousecontext.tunnel.TunnelClient
import com.rousecontext.tunnel.TunnelState
import kotlin.time.Duration

/**
 * Decides whether a wake-up (FCM, Start command, etc.) should trigger a new
 * connect, skip, or force a reconnect because the tunnel is silently dead.
 *
 * Split out from [TunnelForegroundService] for unit testing -- the service
 * itself is awkward to instantiate in a JVM test.
 *
 * See issue #179: the previous implementation trusted
 * `TunnelClient.state == ACTIVE` as "already connected, skip". Under a
 * half-open socket that flag stays ACTIVE forever and every FCM wake is
 * dropped. We now actively probe with a bounded-deadline Ping before
 * trusting the state.
 */
sealed class WakeAction {
    /** Tunnel is healthy; do not reconnect. */
    object Skip : WakeAction()

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
     * Inspect the tunnel and decide what to do. On ACTIVE, actively probes
     * via [TunnelClient.healthCheck] with the given timeout before trusting
     * the state flag.
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
                if (live) WakeAction.Skip else WakeAction.Reconnect(wasStale = true)
            }
            TunnelState.CONNECTED,
            TunnelState.DISCONNECTED,
            TunnelState.DISCONNECTING -> WakeAction.Reconnect(wasStale = false)
        }
}
