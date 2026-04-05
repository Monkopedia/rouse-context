package com.rousecontext.tunnel

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

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
    fun invalidTransitionDisconnectedToConnectedThrows() {
        val sm = ConnectionStateMachine()
        assertFailsWith<IllegalStateException> {
            sm.transition(TunnelState.CONNECTED)
        }
    }

    @Test
    fun invalidTransitionDisconnectedToActiveThrows() {
        val sm = ConnectionStateMachine()
        assertFailsWith<IllegalStateException> {
            sm.transition(TunnelState.ACTIVE)
        }
    }

    @Test
    fun invalidTransitionConnectingToActiveThrows() {
        val sm = ConnectionStateMachine()
        sm.transition(TunnelState.CONNECTING)
        assertFailsWith<IllegalStateException> {
            sm.transition(TunnelState.ACTIVE)
        }
    }

    @Test
    fun invalidTransitionConnectedToConnectingThrows() {
        val sm = ConnectionStateMachine()
        sm.transition(TunnelState.CONNECTING)
        sm.transition(TunnelState.CONNECTED)
        assertFailsWith<IllegalStateException> {
            sm.transition(TunnelState.CONNECTING)
        }
    }

    @Test
    fun invalidTransitionDisconnectingToActiveThrows() {
        val sm = ConnectionStateMachine()
        sm.transition(TunnelState.CONNECTING)
        sm.transition(TunnelState.CONNECTED)
        sm.transition(TunnelState.DISCONNECTING)
        assertFailsWith<IllegalStateException> {
            sm.transition(TunnelState.ACTIVE)
        }
    }

    @Test
    fun transitionToSameStateThrows() {
        val sm = ConnectionStateMachine()
        assertFailsWith<IllegalStateException> {
            sm.transition(TunnelState.DISCONNECTED)
        }
    }
}
