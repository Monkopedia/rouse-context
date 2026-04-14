package com.rousecontext.work

import com.rousecontext.tunnel.TunnelState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class IdleTimeoutTest {

    private val stateFlow = MutableStateFlow(TunnelState.DISCONNECTED)

    @Test
    fun `CONNECTED without prior ACTIVE fires timeout`() = runTest {
        val disconnected = CompletableDeferred<Unit>()
        val manager = IdleTimeoutManager(
            timeoutMillis = 30_000L,
            batteryExempt = false,
            onTimeout = { disconnected.complete(Unit) }
        )

        val job = launch { manager.observe(stateFlow) }

        stateFlow.value = TunnelState.CONNECTED
        advanceTimeBy(30_001L)

        assertTrue(
            "Timer MUST fire on CONNECTED even without prior ACTIVE",
            disconnected.isCompleted
        )
        assertTrue("timeoutFired flag should be set", manager.timeoutFired)
        job.cancel()
    }

    @Test
    fun `timer starts on ACTIVE to CONNECTED transition`() = runTest {
        val disconnected = CompletableDeferred<Unit>()
        val manager = IdleTimeoutManager(
            timeoutMillis = 30_000L,
            batteryExempt = false,
            onTimeout = { disconnected.complete(Unit) }
        )

        val job = launch { manager.observe(stateFlow) }

        stateFlow.value = TunnelState.CONNECTED
        stateFlow.value = TunnelState.ACTIVE
        advanceTimeBy(5_000L)
        stateFlow.value = TunnelState.CONNECTED
        advanceTimeBy(30_001L)

        assertTrue("Timeout should fire after ACTIVE -> CONNECTED", disconnected.isCompleted)
        assertTrue("timeoutFired flag should be set", manager.timeoutFired)
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

        // Get into a state where timer can start
        stateFlow.value = TunnelState.CONNECTED
        stateFlow.value = TunnelState.ACTIVE
        stateFlow.value = TunnelState.CONNECTED
        advanceTimeBy(20_000L)

        // New stream arrives before timeout
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
        stateFlow.value = TunnelState.ACTIVE
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
    fun `DISCONNECTED cancels timer and resets hasBeenActive`() = runTest {
        val disconnected = CompletableDeferred<Unit>()
        val manager = IdleTimeoutManager(
            timeoutMillis = 30_000L,
            batteryExempt = false,
            onTimeout = { disconnected.complete(Unit) }
        )

        val job = launch { manager.observe(stateFlow) }

        // First session: get ACTIVE then disconnect
        stateFlow.value = TunnelState.CONNECTED
        stateFlow.value = TunnelState.ACTIVE
        stateFlow.value = TunnelState.DISCONNECTED
        advanceTimeBy(5_000L)

        // Reconnect - fresh cycle, timer should arm on CONNECTED (no prior ACTIVE)
        // and fire after timeoutMillis.
        stateFlow.value = TunnelState.CONNECTED
        advanceTimeBy(30_001L)

        assertTrue(
            "Timer should fire on fresh CONNECTED cycle after DISCONNECTED",
            disconnected.isCompleted
        )
        job.cancel()
    }

    @Test
    fun `spurious wake recorded when timeout fires without ACTIVE`() = runTest {
        val recorder = FakeSpuriousWakeRecorder()
        val manager = IdleTimeoutManager(
            timeoutMillis = 30_000L,
            batteryExempt = false,
            onTimeout = { },
            recorder = recorder
        )

        val job = launch { manager.observe(stateFlow) }

        stateFlow.value = TunnelState.CONNECTED
        advanceTimeBy(30_001L)

        // Complete the cycle
        stateFlow.value = TunnelState.DISCONNECTED
        advanceTimeBy(10L)

        assertEquals(1, recorder.spuriousCount)
        assertEquals(1, recorder.totalCount)
        job.cancel()
    }

    @Test
    fun `non-spurious wake only increments total`() = runTest {
        val recorder = FakeSpuriousWakeRecorder()
        val manager = IdleTimeoutManager(
            timeoutMillis = 30_000L,
            batteryExempt = false,
            onTimeout = { },
            recorder = recorder
        )

        val job = launch { manager.observe(stateFlow) }

        stateFlow.value = TunnelState.CONNECTED
        stateFlow.value = TunnelState.ACTIVE
        advanceTimeBy(5_000L)
        stateFlow.value = TunnelState.CONNECTED
        stateFlow.value = TunnelState.DISCONNECTED
        advanceTimeBy(10L)

        assertEquals(0, recorder.spuriousCount)
        assertEquals(1, recorder.totalCount)
        job.cancel()
    }

    @Test
    fun `multiple wake cycles accumulate correctly`() = runTest {
        val recorder = FakeSpuriousWakeRecorder()
        val manager = IdleTimeoutManager(
            timeoutMillis = 30_000L,
            batteryExempt = false,
            onTimeout = { },
            recorder = recorder
        )

        val job = launch { manager.observe(stateFlow) }

        // Spurious wake: CONNECTED then DISCONNECTED without any ACTIVE
        stateFlow.value = TunnelState.CONNECTED
        advanceTimeBy(10L)
        stateFlow.value = TunnelState.DISCONNECTED
        advanceTimeBy(10L)

        // Good wake: CONNECTED -> ACTIVE -> CONNECTED -> DISCONNECTED
        stateFlow.value = TunnelState.CONNECTED
        advanceTimeBy(10L)
        stateFlow.value = TunnelState.ACTIVE
        advanceTimeBy(10L)
        stateFlow.value = TunnelState.CONNECTED
        advanceTimeBy(10L)
        stateFlow.value = TunnelState.DISCONNECTED
        advanceTimeBy(10L)

        // Another spurious wake
        stateFlow.value = TunnelState.CONNECTED
        advanceTimeBy(10L)
        stateFlow.value = TunnelState.DISCONNECTED
        advanceTimeBy(10L)

        assertEquals(2, recorder.spuriousCount)
        assertEquals(3, recorder.totalCount)
        job.cancel()
    }

    private class FakeSpuriousWakeRecorder : SpuriousWakeRecorder {
        var spuriousCount: Int = 0
            private set
        var totalCount: Int = 0
            private set

        override fun recordWakeCycle(hadActiveStream: Boolean) {
            totalCount++
            if (!hadActiveStream) spuriousCount++
        }
    }
}
