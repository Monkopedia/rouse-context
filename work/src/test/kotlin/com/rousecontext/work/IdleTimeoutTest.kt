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
            timeoutProvider = { _ -> 30_000L },
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
            timeoutProvider = { _ -> 30_000L },
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
            timeoutProvider = { _ -> 30_000L },
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
    fun `timer restarts when returning to CONNECTED from ACTIVE`() = runTest {
        val disconnected = CompletableDeferred<Unit>()
        val manager = IdleTimeoutManager(
            timeoutProvider = { _ -> 30_000L },
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
            timeoutProvider = { _ -> 30_000L },
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
            timeoutProvider = { _ -> 30_000L },
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
            timeoutProvider = { _ -> 30_000L },
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
            timeoutProvider = { _ -> 30_000L },
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

    @Test
    fun `disabled provider never arms the timer`() = runTest {
        val disconnected = CompletableDeferred<Unit>()
        val manager = IdleTimeoutManager(
            // null = idle timeout disabled by the user
            timeoutProvider = { _ -> null },
            onTimeout = { disconnected.complete(Unit) }
        )

        val job = launch { manager.observe(stateFlow) }

        stateFlow.value = TunnelState.CONNECTED
        advanceTimeBy(60 * 60 * 1000L)

        assertFalse(
            "Timeout must NOT fire when the provider reports disabled (null)",
            disconnected.isCompleted
        )
        assertFalse("timeoutFired flag should stay false", manager.timeoutFired)
        job.cancel()
    }

    @Test
    fun `provider is re-read each cycle so a changed value takes effect`() = runTest {
        val disconnected = CompletableDeferred<Unit>()
        var current = 30_000L
        val manager = IdleTimeoutManager(
            timeoutProvider = { _ -> current },
            onTimeout = { disconnected.complete(Unit) }
        )

        val job = launch { manager.observe(stateFlow) }

        // First cycle uses 30s; complete it without firing by going ACTIVE.
        stateFlow.value = TunnelState.CONNECTED
        stateFlow.value = TunnelState.ACTIVE
        stateFlow.value = TunnelState.DISCONNECTED
        advanceTimeBy(10L)

        // User shortens the timeout; the next CONNECTED cycle must honor it.
        current = 2_000L
        stateFlow.value = TunnelState.CONNECTED
        advanceTimeBy(2_001L)

        assertTrue(
            "A value changed between cycles must take effect on the next arm",
            disconnected.isCompleted
        )
        job.cancel()
    }

    @Test
    fun `discovery-only cycle arms the quick timeout`() = runTest {
        val tracker = SessionActivityTracker()
        val armedSubstantive = mutableListOf<Boolean>()
        val manager = IdleTimeoutManager(
            timeoutProvider = { substantive ->
                armedSubstantive += substantive
                if (substantive) LONG else QUICK
            },
            onTimeout = { },
            activityTracker = tracker
        )

        val job = launch { manager.observe(stateFlow) }

        // Wake -> stream opens -> only discovery (no tools/call) -> stream closes.
        stateFlow.value = TunnelState.CONNECTED
        stateFlow.value = TunnelState.ACTIVE
        stateFlow.value = TunnelState.CONNECTED
        advanceTimeBy(QUICK + 1)

        // The re-arm on the second CONNECTED must have read substantive == false.
        assertFalse("discovery-only re-arm must be non-substantive", armedSubstantive.last())
        job.cancel()
    }

    @Test
    fun `substantive cycle arms the long timeout and does not fire early`() = runTest {
        val tracker = SessionActivityTracker()
        val disconnected = CompletableDeferred<Unit>()
        val manager = IdleTimeoutManager(
            timeoutProvider = { substantive -> if (substantive) LONG else QUICK },
            onTimeout = { disconnected.complete(Unit) },
            activityTracker = tracker
        )

        val job = launch { manager.observe(stateFlow) }

        stateFlow.value = TunnelState.CONNECTED
        stateFlow.value = TunnelState.ACTIVE
        // Let observe() start (and do its initial reset) before promoting, so the
        // tools/call below models a real mid-cycle call rather than a pre-start one.
        advanceTimeBy(5_000L)
        // A tools/call mid-cycle promotes the cycle to substantive.
        tracker.recordToolCall()
        stateFlow.value = TunnelState.CONNECTED

        // Past the quick timeout but before the long one: must NOT fire.
        advanceTimeBy(QUICK + 1)
        assertFalse("substantive cycle must use the long timeout", disconnected.isCompleted)

        // Past the long timeout: now it fires.
        advanceTimeBy(LONG)
        assertTrue("long timeout should eventually fire", disconnected.isCompleted)
        job.cancel()
    }

    @Test
    fun `spurious wake with no stream uses the quick timeout`() = runTest {
        val tracker = SessionActivityTracker()
        var armed: Boolean? = null
        val manager = IdleTimeoutManager(
            timeoutProvider = { substantive ->
                armed = substantive
                if (substantive) LONG else QUICK
            },
            onTimeout = { },
            activityTracker = tracker
        )

        val job = launch { manager.observe(stateFlow) }

        // Wake with no stream ever opening.
        stateFlow.value = TunnelState.CONNECTED
        advanceTimeBy(QUICK + 1)

        assertFalse("spurious wake must be non-substantive", armed!!)
        job.cancel()
    }

    @Test
    fun `substantiveness resets after DISCONNECTED so next wake uses quick`() = runTest {
        val tracker = SessionActivityTracker()
        val armedSubstantive = mutableListOf<Boolean>()
        val manager = IdleTimeoutManager(
            timeoutProvider = { substantive ->
                armedSubstantive += substantive
                if (substantive) LONG else QUICK
            },
            onTimeout = { },
            activityTracker = tracker
        )

        val job = launch { manager.observe(stateFlow) }

        // First cycle: substantive (tools/call), then disconnect.
        stateFlow.value = TunnelState.CONNECTED
        stateFlow.value = TunnelState.ACTIVE
        // Let observe() start before the tools/call so it is a true mid-cycle call.
        advanceTimeBy(10L)
        tracker.recordToolCall()
        stateFlow.value = TunnelState.CONNECTED
        advanceTimeBy(10L)
        stateFlow.value = TunnelState.DISCONNECTED
        advanceTimeBy(10L)

        // Second cycle: spurious wake must NOT inherit substantiveness.
        stateFlow.value = TunnelState.CONNECTED
        advanceTimeBy(10L)

        // The first cycle's re-arm was substantive; the post-DISCONNECTED reset
        // means the second wake re-arms non-substantive.
        assertTrue("first cycle should have armed substantive", armedSubstantive.contains(true))
        assertFalse("second wake must re-arm non-substantive after reset", armedSubstantive.last())
        job.cancel()
    }

    private companion object {
        const val QUICK = 30_000L
        const val LONG = 300_000L
    }

    private class FakeSpuriousWakeRecorder : SpuriousWakeRecorder {
        var spuriousCount: Int = 0
            private set
        var totalCount: Int = 0
            private set

        override suspend fun recordWakeCycle(hadActiveStream: Boolean) {
            totalCount++
            if (!hadActiveStream) spuriousCount++
        }
    }
}
