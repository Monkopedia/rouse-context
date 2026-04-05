package com.rousecontext.tunnel

/**
 * Represents the current state of the tunnel connection.
 */
sealed interface TunnelState {
    data object Disconnected : TunnelState
    data object Connecting : TunnelState
    data object Connected : TunnelState
}
