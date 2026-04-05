package com.rousecontext.work

import com.rousecontext.tunnel.TunnelState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WakelockManagerTest {

    private val stateFlow = MutableStateFlow(TunnelState.DISCONNECTED)
    private val fakeLock = FakeWakeLockHandle()

    @Test
    fun `ACTIVE acquires wakelock`() = runBlocking {
        val manager = WakelockManager(fakeLock)
        val job = launch { manager.observe(stateFlow) }

        stateFlow.value = TunnelState.ACTIVE
        // Give the collector a chance to process
        kotlinx.coroutines.yield()

        assertTrue("Wakelock should be held when ACTIVE", fakeLock.isHeld)
        job.cancel()
    }

    @Test
    fun `CONNECTED releases wakelock`() = runBlocking {
        val manager = WakelockManager(fakeLock)
        val job = launch { manager.observe(stateFlow) }

        stateFlow.value = TunnelState.ACTIVE
        kotlinx.coroutines.yield()
        assertTrue(fakeLock.isHeld)

        stateFlow.value = TunnelState.CONNECTED
        kotlinx.coroutines.yield()
        assertFalse("Wakelock should be released when CONNECTED (idle)", fakeLock.isHeld)
        job.cancel()
    }

    @Test
    fun `CONNECTING acquires wakelock`() = runBlocking {
        val manager = WakelockManager(fakeLock)
        val job = launch { manager.observe(stateFlow) }

        stateFlow.value = TunnelState.CONNECTING
        kotlinx.coroutines.yield()

        assertTrue("Wakelock should be held when CONNECTING", fakeLock.isHeld)
        job.cancel()
    }

    @Test
    fun `DISCONNECTED releases wakelock`() = runBlocking {
        val manager = WakelockManager(fakeLock)
        val job = launch { manager.observe(stateFlow) }

        stateFlow.value = TunnelState.CONNECTING
        kotlinx.coroutines.yield()
        assertTrue(fakeLock.isHeld)

        stateFlow.value = TunnelState.DISCONNECTED
        kotlinx.coroutines.yield()
        assertFalse("Wakelock should be released when DISCONNECTED", fakeLock.isHeld)
        job.cancel()
    }

    @Test
    fun `rapid transitions are balanced - no leak`() = runBlocking {
        val manager = WakelockManager(fakeLock)
        val job = launch { manager.observe(stateFlow) }

        stateFlow.value = TunnelState.CONNECTING
        kotlinx.coroutines.yield()
        stateFlow.value = TunnelState.CONNECTED
        kotlinx.coroutines.yield()
        stateFlow.value = TunnelState.ACTIVE
        kotlinx.coroutines.yield()
        stateFlow.value = TunnelState.DISCONNECTING
        kotlinx.coroutines.yield()
        stateFlow.value = TunnelState.DISCONNECTED
        kotlinx.coroutines.yield()

        assertFalse("Wakelock should not be held after full lifecycle", fakeLock.isHeld)
        assertEquals(
            "Acquire count should equal release count",
            fakeLock.acquireCount,
            fakeLock.releaseCount
        )
        job.cancel()
    }

    @Test
    fun `double acquire does not double-lock`() = runBlocking {
        val manager = WakelockManager(fakeLock)
        val job = launch { manager.observe(stateFlow) }

        stateFlow.value = TunnelState.CONNECTING
        kotlinx.coroutines.yield()
        stateFlow.value = TunnelState.ACTIVE
        kotlinx.coroutines.yield()

        // Should only acquire once since CONNECTING already acquired
        assertEquals(1, fakeLock.acquireCount)
        assertTrue(fakeLock.isHeld)
        job.cancel()
    }

    @Test
    fun `double release does not double-unlock`() = runBlocking {
        val manager = WakelockManager(fakeLock)
        val job = launch { manager.observe(stateFlow) }

        stateFlow.value = TunnelState.CONNECTING
        kotlinx.coroutines.yield()
        stateFlow.value = TunnelState.CONNECTED
        kotlinx.coroutines.yield()
        stateFlow.value = TunnelState.DISCONNECTED
        kotlinx.coroutines.yield()

        // Only one release, not two
        assertEquals(1, fakeLock.releaseCount)
        job.cancel()
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
