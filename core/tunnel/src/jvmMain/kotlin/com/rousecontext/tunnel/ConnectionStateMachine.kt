package com.rousecontext.tunnel

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * State machine governing tunnel connection lifecycle.
 *
 * Valid transitions:
 * - DISCONNECTED -> CONNECTING
 * - CONNECTING -> CONNECTED
 * - CONNECTING -> DISCONNECTED (connection failed)
 * - CONNECTED -> ACTIVE (first stream opened)
 * - CONNECTED -> DISCONNECTING
 * - ACTIVE -> DISCONNECTING
 * - ACTIVE -> CONNECTED (last stream closed)
 * - DISCONNECTING -> DISCONNECTED
 */
class ConnectionStateMachine(private val log: (LogLevel, String) -> Unit = { _, _ -> }) {

    private val lock = Any()
    private val _state = MutableStateFlow(TunnelState.DISCONNECTED)

    /**
     * Current state as a [StateFlow].
     */
    val state: StateFlow<TunnelState>
        get() = _state.asStateFlow()

    /**
     * Attempt a state transition. Internally synchronized so the
     * read-check-write sequence is atomic with respect to other concurrent
     * `transition()` callers; see #269.
     *
     * @return true if the transition was valid and applied, false if invalid (logged as warning)
     */
    fun transition(to: TunnelState): Boolean = synchronized(lock) {
        val from = _state.value
        if (!isValidTransition(from, to)) {
            log(
                LogLevel.WARN,
                "ConnectionStateMachine: ignoring invalid transition from $from to $to"
            )
            return@synchronized false
        }
        _state.value = to
        true
    }

    private fun isValidTransition(from: TunnelState, to: TunnelState): Boolean = when (from) {
        TunnelState.DISCONNECTED -> to == TunnelState.CONNECTING
        TunnelState.CONNECTING -> to == TunnelState.CONNECTED || to == TunnelState.DISCONNECTED
        TunnelState.CONNECTED ->
            to == TunnelState.ACTIVE ||
                to == TunnelState.DISCONNECTING ||
                to == TunnelState.DISCONNECTED

        TunnelState.ACTIVE ->
            to == TunnelState.DISCONNECTING ||
                to == TunnelState.CONNECTED ||
                to == TunnelState.DISCONNECTED

        TunnelState.DISCONNECTING -> to == TunnelState.DISCONNECTED
    }
}
