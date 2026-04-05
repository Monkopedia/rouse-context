package com.rousecontext.tunnel

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
<<<<<<< HEAD
 * State machine governing tunnel connection lifecycle.
=======
 * Manages connection state transitions for the tunnel.
>>>>>>> feat/tunnel-websocket-tls
 *
 * Valid transitions:
 * - DISCONNECTED -> CONNECTING
 * - CONNECTING -> CONNECTED
<<<<<<< HEAD
 * - CONNECTING -> DISCONNECTED (connection failed)
 * - CONNECTED -> ACTIVE (first stream opened)
 * - CONNECTED -> DISCONNECTING
 * - ACTIVE -> DISCONNECTING
 * - ACTIVE -> CONNECTED (last stream closed)
 * - DISCONNECTING -> DISCONNECTED
 */
class ConnectionStateMachine {

    private val _state = MutableStateFlow(TunnelState.DISCONNECTED)

    /**
     * Current state as a [StateFlow].
     */
    val state: StateFlow<TunnelState>
        get() = _state.asStateFlow()

    /**
     * Attempt a state transition.
     *
     * @return true if the transition was valid and applied
     * @throws IllegalStateException if the transition is invalid
     */
    fun transition(to: TunnelState): Boolean {
        val from = _state.value
        if (!isValidTransition(from, to)) {
            throw IllegalStateException("Invalid transition from $from to $to")
        }
        _state.value = to
        return true
    }

    private fun isValidTransition(from: TunnelState, to: TunnelState): Boolean =
        when (from) {
            TunnelState.DISCONNECTED -> to == TunnelState.CONNECTING
            TunnelState.CONNECTING -> to == TunnelState.CONNECTED || to == TunnelState.DISCONNECTED
            TunnelState.CONNECTED ->
                to == TunnelState.ACTIVE || to == TunnelState.DISCONNECTING

            TunnelState.ACTIVE ->
                to == TunnelState.DISCONNECTING || to == TunnelState.CONNECTED

            TunnelState.DISCONNECTING -> to == TunnelState.DISCONNECTED
=======
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
>>>>>>> feat/tunnel-websocket-tls
        }
}
