package com.rousecontext.work

import com.rousecontext.tunnel.TunnelState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class IdleTimeoutTest {

    private val stateFlow = MutableStateFlow(TunnelState.DISCONNECTED)

    @Test
    fun `CONNECTED for timeout duration triggers disconnect`() = runTest {
        val disconnected = CompletableDeferred<Unit>()
        val manager = IdleTimeoutManager(
            timeoutMillis = 30_000L,
            batteryExempt = false,
            onTimeout = { disconnected.complete(Unit) }
        )

        val job = launch { manager.observe(stateFlow) }

        stateFlow.value = TunnelState.CONNECTED
        advanceTimeBy(30_001L)

        assertTrue("Timeout should have fired", disconnected.isCompleted)
        job.cancel()
    }

    @Test
    fun `stream arrival cancels timer`() = runTest {
        val disconnected = CompletableDeferred<Unit>()
        val manager = IdleTimeoutManager(
            timeoutMillis = 30_000L,
            batteryExempt = false,
            onTimeout = { disconnected.complete(Unit) }
        )

        val job = launch { manager.observe(stateFlow) }

        stateFlow.value = TunnelState.CONNECTED
        advanceTimeBy(20_000L)

        // Stream arrives before timeout
        stateFlow.value = TunnelState.ACTIVE
        advanceTimeBy(15_000L)

        assertFalse("Timeout should NOT have fired after stream arrival", disconnected.isCompleted)
        job.cancel()
    }

    @Test
    fun `timer disabled when battery exempt`() = runTest {
        val disconnected = CompletableDeferred<Unit>()
        val manager = IdleTimeoutManager(
            timeoutMillis = 30_000L,
            batteryExempt = true,
            onTimeout = { disconnected.complete(Unit) }
        )

        val job = launch { manager.observe(stateFlow) }

        stateFlow.value = TunnelState.CONNECTED
        advanceTimeBy(60_000L)

        assertFalse("Timeout should NOT fire when battery exempt", disconnected.isCompleted)
        job.cancel()
    }

    @Test
    fun `timer restarts when returning to CONNECTED from ACTIVE`() = runTest {
        val disconnected = CompletableDeferred<Unit>()
        val manager = IdleTimeoutManager(
            timeoutMillis = 30_000L,
            batteryExempt = false,
            onTimeout = { disconnected.complete(Unit) }
        )

        val job = launch { manager.observe(stateFlow) }

        stateFlow.value = TunnelState.CONNECTED
        advanceTimeBy(20_000L)

        // Stream opens and closes
        stateFlow.value = TunnelState.ACTIVE
        advanceTimeBy(5_000L)
        stateFlow.value = TunnelState.CONNECTED
        advanceTimeBy(20_000L)

        // Only 20s since returning to CONNECTED, should not have fired
        assertFalse("Timer should restart after stream closes", disconnected.isCompleted)

        advanceTimeBy(11_000L)
        assertTrue("Timeout should fire after full duration from re-idle", disconnected.isCompleted)
        job.cancel()
    }

    @Test
    fun `DISCONNECTED cancels timer`() = runTest {
        val disconnected = CompletableDeferred<Unit>()
        val manager = IdleTimeoutManager(
            timeoutMillis = 30_000L,
            batteryExempt = false,
            onTimeout = { disconnected.complete(Unit) }
        )

        val job = launch { manager.observe(stateFlow) }

        stateFlow.value = TunnelState.CONNECTED
        advanceTimeBy(20_000L)

        stateFlow.value = TunnelState.DISCONNECTED
        advanceTimeBy(30_000L)

        assertFalse("Timer should not fire after disconnect", disconnected.isCompleted)
        job.cancel()
    }
}
