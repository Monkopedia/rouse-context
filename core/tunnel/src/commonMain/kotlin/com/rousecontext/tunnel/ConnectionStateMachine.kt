package com.rousecontext.tunnel

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Manages connection state transitions for the tunnel.
 *
 * Valid transitions:
 * - DISCONNECTED -> CONNECTING
 * - CONNECTING -> CONNECTED
 * - CONNECTING -> DISCONNECTED (on failure)
 * - CONNECTED -> DISCONNECTED (on close or error)
 */
class ConnectionStateMachine {
    private val _state = MutableStateFlow<TunnelState>(TunnelState.Disconnected)
    val state: StateFlow<TunnelState> = _state.asStateFlow()

    fun transition(newState: TunnelState) {
        val current = _state.value
        require(isValidTransition(current, newState)) {
            "Invalid transition: $current -> $newState"
        }
        _state.value = newState
    }

    private fun isValidTransition(
        from: TunnelState,
        to: TunnelState,
    ): Boolean =
        when (from) {
            is TunnelState.Disconnected -> to is TunnelState.Connecting
            is TunnelState.Connecting -> to is TunnelState.Connected || to is TunnelState.Disconnected
            is TunnelState.Connected -> to is TunnelState.Disconnected
        }
}
