package com.rousecontext.tunnel

/**
 * Observable state of the tunnel connection lifecycle.
 */
sealed interface TunnelState {
    data object Idle : TunnelState
    data object Connecting : TunnelState
    data object MuxConnected : TunnelState
    data object MuxDisconnected : TunnelState
    data class StreamOpened(val streamCount: Int) : TunnelState
    data class StreamClosed(val streamCount: Int) : TunnelState
}
