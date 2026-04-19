package com.rousecontext.tunnel

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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

    /**
     * Regression test for #269: `transition()` must be internally atomic.
     *
     * Without internal synchronization, two threads racing to transition
     * from the same "from" state to two different legal "to" states could
     * both observe the same "from" value, both pass the matrix check, and
     * both write — leaving the state machine in whichever state lost the
     * publication race, without the "invalid transition" path being hit.
     *
     * Under the fix, exactly one `transition()` call returns `true`; the
     * other sees the already-updated state and either rejects the
     * transition (returns `false`) or accepts it as a valid follow-on
     * transition. Either way, the final state is well-defined and the
     * total number of "winners" matches the number of state writes.
     *
     * The test fires many rounds from a fresh CONNECTING state, with two
     * racers trying CONNECTED vs DISCONNECTED. Both are legal from
     * CONNECTING, but only one can be "first", and the second must see
     * the result of the first (CONNECTED -> DISCONNECTED is valid;
     * DISCONNECTED -> CONNECTED is NOT valid without going through
     * CONNECTING first). So if both racers succeed in a round, the only
     * legal end state is DISCONNECTED. If only one succeeds, the end
     * state matches that racer's target.
     */
    @Test
    fun concurrentTransitionsAreAtomic() {
        val rounds = 2000
        val corruptionDetected = AtomicInteger(0)
        repeat(rounds) {
            val sm = ConnectionStateMachine()
            sm.transition(TunnelState.CONNECTING)

            val start = CountDownLatch(1)
            val done = CountDownLatch(2)
            val winnerToConnected = AtomicInteger(0)
            val winnerToDisconnected = AtomicInteger(0)

            val t1 = Thread {
                start.await()
                if (sm.transition(TunnelState.CONNECTED)) {
                    winnerToConnected.incrementAndGet()
                }
                done.countDown()
            }
            val t2 = Thread {
                start.await()
                if (sm.transition(TunnelState.DISCONNECTED)) {
                    winnerToDisconnected.incrementAndGet()
                }
                done.countDown()
            }
            t1.start()
            t2.start()
            start.countDown()
            assertTrue(done.await(5, TimeUnit.SECONDS), "threads did not complete in time")

            val finalState = sm.state.value
            val cWins = winnerToConnected.get()
            val dWins = winnerToDisconnected.get()

            when {
                // Both threads think they won.
                // CONNECTING -> CONNECTED is valid, and CONNECTED -> DISCONNECTED is valid,
                // so if the CONNECTED writer ran first the DISCONNECTED writer sees CONNECTED
                // and legally transitions to DISCONNECTED.
                // If the DISCONNECTED writer ran first, the CONNECTED writer sees
                // DISCONNECTED and MUST be rejected (DISCONNECTED -> CONNECTED is invalid).
                // So "both won" can only happen in the first ordering, end state DISCONNECTED.
                cWins == 1 && dWins == 1 -> {
                    if (finalState != TunnelState.DISCONNECTED) {
                        corruptionDetected.incrementAndGet()
                    }
                }
                cWins == 1 && dWins == 0 -> {
                    if (finalState != TunnelState.CONNECTED) {
                        corruptionDetected.incrementAndGet()
                    }
                }
                cWins == 0 && dWins == 1 -> {
                    if (finalState != TunnelState.DISCONNECTED) {
                        corruptionDetected.incrementAndGet()
                    }
                }
                else -> {
                    // Neither won: impossible if state machine is correct, since
                    // both transitions are valid from CONNECTING.
                    corruptionDetected.incrementAndGet()
                }
            }
        }
        assertEquals(
            0,
            corruptionDetected.get(),
            "state machine corruption detected in concurrent transitions"
        )
    }

    @Test
    fun invalidTransitionInvokesLogLambda() {
        val captured = mutableListOf<Pair<LogLevel, String>>()
        val sm = ConnectionStateMachine(log = { level, msg -> captured.add(level to msg) })
        sm.transition(TunnelState.CONNECTED)
        assertEquals(1, captured.size)
        assertEquals(LogLevel.WARN, captured[0].first)
        assertTrue(captured[0].second.contains("invalid transition"))
        assertTrue(captured[0].second.contains("DISCONNECTED"))
        assertTrue(captured[0].second.contains("CONNECTED"))
    }
}
