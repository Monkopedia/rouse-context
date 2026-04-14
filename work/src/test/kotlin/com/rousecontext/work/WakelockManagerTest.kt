package com.rousecontext.work

import com.rousecontext.tunnel.TunnelState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WakelockManagerTest {

    private val stateFlow = MutableStateFlow(TunnelState.DISCONNECTED)
    private val fakeLock = FakeWakeLockHandle()

    private fun TestScope.startManager() = launch {
        WakelockManager(fakeLock).observe(stateFlow)
    }

    @Test
    fun `CONNECTING acquires wakelock`() = runTest {
        startManager()

        stateFlow.value = TunnelState.CONNECTING
        advanceUntilIdle()

        assertTrue("Wakelock should be held when CONNECTING", fakeLock.isHeld)
        coroutineContext.cancelChildren()
    }

    @Test
    fun `ACTIVE acquires wakelock`() = runTest {
        startManager()

        stateFlow.value = TunnelState.ACTIVE
        advanceUntilIdle()

        assertTrue("Wakelock should be held when ACTIVE", fakeLock.isHeld)
        coroutineContext.cancelChildren()
    }

    @Test
    fun `CONNECTED to ACTIVE within grace keeps wakelock held and cancels release`() = runTest {
        startManager()

        stateFlow.value = TunnelState.CONNECTING
        advanceUntilIdle()
        assertTrue(fakeLock.isHeld)

        stateFlow.value = TunnelState.CONNECTED
        // Advance inside the grace window but not past it.
        advanceTimeBy(1_000L)

        assertTrue("Still held during grace", fakeLock.isHeld)
        assertEquals("No release yet during grace", 0, fakeLock.releaseCount)

        stateFlow.value = TunnelState.ACTIVE
        // Advance well past the original grace deadline; release must have been cancelled.
        advanceTimeBy(5_000L)
        advanceUntilIdle()

        assertTrue("Wakelock stays held after ACTIVE during grace", fakeLock.isHeld)
        assertEquals("Release should have been cancelled", 0, fakeLock.releaseCount)
        coroutineContext.cancelChildren()
    }

    @Test
    fun `CONNECTED with no ACTIVE releases wakelock after grace expires`() = runTest {
        startManager()

        stateFlow.value = TunnelState.CONNECTING
        advanceUntilIdle()
        assertTrue(fakeLock.isHeld)

        stateFlow.value = TunnelState.CONNECTED
        advanceTimeBy(WakelockManager.CONNECTED_GRACE_MS - 1)
        assertTrue("Held just before grace expires", fakeLock.isHeld)

        advanceTimeBy(2) // cross the deadline
        advanceUntilIdle()

        assertFalse("Wakelock released after grace expiry", fakeLock.isHeld)
        assertEquals(1, fakeLock.releaseCount)
        coroutineContext.cancelChildren()
    }

    @Test
    fun `CONNECTED to DISCONNECTING within grace releases wakelock immediately`() = runTest {
        startManager()

        stateFlow.value = TunnelState.CONNECTING
        advanceUntilIdle()
        stateFlow.value = TunnelState.CONNECTED
        advanceTimeBy(500L)
        assertTrue(fakeLock.isHeld)

        val releasesBefore = fakeLock.releaseCount
        stateFlow.value = TunnelState.DISCONNECTING
        advanceUntilIdle()

        assertFalse("Wakelock released immediately on DISCONNECTING", fakeLock.isHeld)
        assertEquals(
            "Exactly one immediate release (grace timer cancelled)",
            releasesBefore + 1,
            fakeLock.releaseCount
        )

        // Advance past the original grace deadline - no second release should fire.
        advanceTimeBy(WakelockManager.CONNECTED_GRACE_MS)
        advanceUntilIdle()
        assertEquals(
            "Pending release job was cancelled, no double-release",
            releasesBefore + 1,
            fakeLock.releaseCount
        )
        coroutineContext.cancelChildren()
    }

    @Test
    fun `duplicate CONNECTED emissions reset grace timer and do not double-release`() = runTest {
        startManager()

        stateFlow.value = TunnelState.CONNECTING
        advanceUntilIdle()
        assertTrue(fakeLock.isHeld)

        // First CONNECTED
        stateFlow.value = TunnelState.CONNECTED
        advanceTimeBy(2_000L)
        assertTrue("Still held mid-grace", fakeLock.isHeld)

        // Force a re-emission of CONNECTED by bouncing through ACTIVE briefly.
        // Both transitions happen within the original grace window.
        stateFlow.value = TunnelState.ACTIVE
        advanceTimeBy(1L)
        stateFlow.value = TunnelState.CONNECTED
        // Advance by less than the full grace from the second CONNECTED but more than the
        // remaining time on the first - if the first timer were still alive it would fire here.
        advanceTimeBy(2_000L)

        assertTrue("Held because grace timer was reset by second CONNECTED", fakeLock.isHeld)
        assertEquals("No release yet - timer restarted", 0, fakeLock.releaseCount)

        // Now let the second grace expire.
        advanceTimeBy(WakelockManager.CONNECTED_GRACE_MS)
        advanceUntilIdle()

        assertFalse(fakeLock.isHeld)
        assertEquals("Exactly one release total", 1, fakeLock.releaseCount)
        coroutineContext.cancelChildren()
    }

    @Test
    fun `DISCONNECTED releases wakelock immediately`() = runTest {
        startManager()

        stateFlow.value = TunnelState.CONNECTING
        advanceUntilIdle()
        assertTrue(fakeLock.isHeld)

        stateFlow.value = TunnelState.DISCONNECTED
        advanceUntilIdle()

        assertFalse("Wakelock should be released when DISCONNECTED", fakeLock.isHeld)
        coroutineContext.cancelChildren()
    }

    @Test
    fun `full lifecycle is balanced - no leak`() = runTest {
        startManager()

        stateFlow.value = TunnelState.CONNECTING
        advanceUntilIdle()
        stateFlow.value = TunnelState.CONNECTED
        advanceTimeBy(500L)
        stateFlow.value = TunnelState.ACTIVE
        advanceUntilIdle()
        stateFlow.value = TunnelState.DISCONNECTING
        advanceUntilIdle()
        stateFlow.value = TunnelState.DISCONNECTED
        advanceUntilIdle()

        assertFalse("Wakelock should not be held after full lifecycle", fakeLock.isHeld)
        assertEquals(
            "Acquire count should equal release count",
            fakeLock.acquireCount,
            fakeLock.releaseCount
        )
        coroutineContext.cancelChildren()
    }

    @Test
    fun `CONNECTING then ACTIVE does not double-lock`() = runTest {
        startManager()

        stateFlow.value = TunnelState.CONNECTING
        advanceUntilIdle()
        stateFlow.value = TunnelState.ACTIVE
        advanceUntilIdle()

        assertEquals(1, fakeLock.acquireCount)
        assertTrue(fakeLock.isHeld)
        coroutineContext.cancelChildren()
    }

    @Test
    fun `CONNECTED grace release followed by DISCONNECTED does not double-release`() = runTest {
        startManager()

        stateFlow.value = TunnelState.CONNECTING
        advanceUntilIdle()
        stateFlow.value = TunnelState.CONNECTED
        advanceTimeBy(WakelockManager.CONNECTED_GRACE_MS + 1)
        advanceUntilIdle()
        assertEquals(1, fakeLock.releaseCount)

        stateFlow.value = TunnelState.DISCONNECTED
        advanceUntilIdle()

        // Already released; second release MUST NOT fire because wakeLock.isHeld is false.
        assertEquals("Only one release, not two", 1, fakeLock.releaseCount)
        coroutineContext.cancelChildren()
    }
}

/** Test double for [WakeLockHandle]. */
class FakeWakeLockHandle : WakeLockHandle {
    var acquireCount = 0
        private set
    var releaseCount = 0
        private set

    override val isHeld: Boolean
        get() = acquireCount > releaseCount

    override fun acquire() {
        acquireCount++
    }

    override fun release() {
        releaseCount++
    }
}
