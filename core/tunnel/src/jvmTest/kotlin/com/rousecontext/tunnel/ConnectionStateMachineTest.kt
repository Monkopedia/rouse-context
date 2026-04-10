package com.rousecontext.tunnel

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class ConnectionStateMachineTest {

    @Test
    fun initialStateIsDisconnected() {
        val sm = ConnectionStateMachine()
        assertEquals(TunnelState.DISCONNECTED, sm.state.value)
    }

    @Test
    fun disconnectedToConnecting() {
        val sm = ConnectionStateMachine()
        sm.transition(TunnelState.CONNECTING)
        assertEquals(TunnelState.CONNECTING, sm.state.value)
    }

    @Test
    fun connectingToConnected() {
        val sm = ConnectionStateMachine()
        sm.transition(TunnelState.CONNECTING)
        sm.transition(TunnelState.CONNECTED)
        assertEquals(TunnelState.CONNECTED, sm.state.value)
    }

    @Test
    fun connectingToDisconnectedOnFailure() {
        val sm = ConnectionStateMachine()
        sm.transition(TunnelState.CONNECTING)
        sm.transition(TunnelState.DISCONNECTED)
        assertEquals(TunnelState.DISCONNECTED, sm.state.value)
    }

    @Test
    fun connectedToActive() {
        val sm = ConnectionStateMachine()
        sm.transition(TunnelState.CONNECTING)
        sm.transition(TunnelState.CONNECTED)
        sm.transition(TunnelState.ACTIVE)
        assertEquals(TunnelState.ACTIVE, sm.state.value)
    }

    @Test
    fun connectedToDisconnecting() {
        val sm = ConnectionStateMachine()
        sm.transition(TunnelState.CONNECTING)
        sm.transition(TunnelState.CONNECTED)
        sm.transition(TunnelState.DISCONNECTING)
        assertEquals(TunnelState.DISCONNECTING, sm.state.value)
    }

    @Test
    fun activeToDisconnecting() {
        val sm = ConnectionStateMachine()
        sm.transition(TunnelState.CONNECTING)
        sm.transition(TunnelState.CONNECTED)
        sm.transition(TunnelState.ACTIVE)
        sm.transition(TunnelState.DISCONNECTING)
        assertEquals(TunnelState.DISCONNECTING, sm.state.value)
    }

    @Test
    fun activeToConnectedWhenLastStreamCloses() {
        val sm = ConnectionStateMachine()
        sm.transition(TunnelState.CONNECTING)
        sm.transition(TunnelState.CONNECTED)
        sm.transition(TunnelState.ACTIVE)
        sm.transition(TunnelState.CONNECTED)
        assertEquals(TunnelState.CONNECTED, sm.state.value)
    }

    @Test
    fun disconnectingToDisconnected() {
        val sm = ConnectionStateMachine()
        sm.transition(TunnelState.CONNECTING)
        sm.transition(TunnelState.CONNECTED)
        sm.transition(TunnelState.DISCONNECTING)
        sm.transition(TunnelState.DISCONNECTED)
        assertEquals(TunnelState.DISCONNECTED, sm.state.value)
    }

    @Test
    fun fullLifecycle() {
        val sm = ConnectionStateMachine()
        assertEquals(TunnelState.DISCONNECTED, sm.state.value)

        sm.transition(TunnelState.CONNECTING)
        sm.transition(TunnelState.CONNECTED)
        sm.transition(TunnelState.ACTIVE)
        sm.transition(TunnelState.DISCONNECTING)
        sm.transition(TunnelState.DISCONNECTED)

        assertEquals(TunnelState.DISCONNECTED, sm.state.value)
    }

    @Test
    fun invalidTransitionDisconnectedToConnectedReturnsFalse() {
        val sm = ConnectionStateMachine()
        assertFalse(sm.transition(TunnelState.CONNECTED))
        assertEquals(TunnelState.DISCONNECTED, sm.state.value)
    }

    @Test
    fun invalidTransitionDisconnectedToActiveReturnsFalse() {
        val sm = ConnectionStateMachine()
        assertFalse(sm.transition(TunnelState.ACTIVE))
        assertEquals(TunnelState.DISCONNECTED, sm.state.value)
    }

    @Test
    fun invalidTransitionConnectingToActiveReturnsFalse() {
        val sm = ConnectionStateMachine()
        sm.transition(TunnelState.CONNECTING)
        assertFalse(sm.transition(TunnelState.ACTIVE))
        assertEquals(TunnelState.CONNECTING, sm.state.value)
    }

    @Test
    fun invalidTransitionConnectedToConnectingReturnsFalse() {
        val sm = ConnectionStateMachine()
        sm.transition(TunnelState.CONNECTING)
        sm.transition(TunnelState.CONNECTED)
        assertFalse(sm.transition(TunnelState.CONNECTING))
        assertEquals(TunnelState.CONNECTED, sm.state.value)
    }

    @Test
    fun invalidTransitionActiveToConnectingReturnsFalse() {
        val sm = ConnectionStateMachine()
        sm.transition(TunnelState.CONNECTING)
        sm.transition(TunnelState.CONNECTED)
        sm.transition(TunnelState.ACTIVE)
        assertFalse(sm.transition(TunnelState.CONNECTING))
        assertEquals(TunnelState.ACTIVE, sm.state.value)
    }

    @Test
    fun invalidTransitionDisconnectingToActiveReturnsFalse() {
        val sm = ConnectionStateMachine()
        sm.transition(TunnelState.CONNECTING)
        sm.transition(TunnelState.CONNECTED)
        sm.transition(TunnelState.DISCONNECTING)
        assertFalse(sm.transition(TunnelState.ACTIVE))
        assertEquals(TunnelState.DISCONNECTING, sm.state.value)
    }

    @Test
    fun transitionToSameStateReturnsFalse() {
        val sm = ConnectionStateMachine()
        assertFalse(sm.transition(TunnelState.DISCONNECTED))
        assertEquals(TunnelState.DISCONNECTED, sm.state.value)
    }
}
