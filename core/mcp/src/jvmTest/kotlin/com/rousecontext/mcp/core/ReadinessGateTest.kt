package com.rousecontext.mcp.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadinessGateTest {

    @Test
    fun `awaitReadyBlocking returns false before signal`() {
        val gate = ReadinessGate()
        assertFalse(gate.awaitReadyBlocking(timeoutMs = 20))
    }

    @Test
    fun `awaitReadyBlocking returns true after signal`() {
        val gate = ReadinessGate()
        gate.signalReady()
        assertTrue(gate.awaitReadyBlocking(timeoutMs = 20))
    }

    @Test
    fun `awaitReady suspends until signalled then resumes`() = runBlocking {
        val gate = ReadinessGate()
        val resumed = CompletableDeferred<Unit>()
        val waiter = launch {
            gate.awaitReady()
            resumed.complete(Unit)
        }

        // Not signalled yet: the waiter must still be suspended.
        assertFalse(resumed.isCompleted)

        gate.signalReady()

        withTimeout(1_000) { resumed.await() }
        assertTrue(resumed.isCompleted)
        waiter.cancelAndJoin()
    }

    @Test
    fun `awaitReady returns immediately when already signalled`() = runBlocking {
        val gate = ReadinessGate()
        gate.signalReady()
        withTimeout(1_000) { gate.awaitReady() }
    }

    @Test
    fun `double signal is safe and idempotent`() = runBlocking {
        val gate = ReadinessGate()
        gate.signalReady()
        gate.signalReady()
        gate.signalReady()

        assertTrue(gate.awaitReadyBlocking(timeoutMs = 20))
        withTimeout(1_000) { gate.awaitReady() }
    }
}
