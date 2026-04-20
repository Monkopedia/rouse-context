package com.rousecontext.app.state

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceRegistrationStatusTest {

    @Test
    fun `default is not complete`() {
        val status = DeviceRegistrationStatus()
        assertFalse(status.complete.value)
    }

    @Test
    fun `constructor honors initiallyRegistered`() {
        val status = DeviceRegistrationStatus(initiallyRegistered = true)
        assertTrue(status.complete.value)
    }

    @Test
    fun `markComplete flips observable`() {
        val status = DeviceRegistrationStatus()
        status.markComplete()
        assertTrue(status.complete.value)
    }

    @Test
    fun `awaitComplete returns immediately when already complete`() = runBlocking {
        val status = DeviceRegistrationStatus(initiallyRegistered = true)
        withTimeout(TIMEOUT_MS) {
            status.awaitComplete()
        }
    }

    @Test
    fun `awaitComplete suspends until markComplete`() = runBlocking {
        val status = DeviceRegistrationStatus()
        val suspended = CompletableDeferred<Unit>()
        val job = launch(Dispatchers.Default) {
            status.awaitComplete()
            suspended.complete(Unit)
        }

        // Give the awaiter a chance to suspend on the StateFlow.
        delay(SUSPEND_DELAY_MS)
        assertFalse(suspended.isCompleted)

        status.markComplete()
        withTimeout(TIMEOUT_MS) {
            suspended.await()
        }
        assertEquals(true, job.isCompleted || job.isActive)
    }

    companion object {
        private const val TIMEOUT_MS = 5_000L
        private const val SUSPEND_DELAY_MS = 100L
    }
}
