package com.rousecontext.app.state

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DeviceRegistrationStatusTest {

    private lateinit var scope: CoroutineScope
    private lateinit var job: Job

    @Before
    fun setUp() {
        job = SupervisorJob()
        scope = CoroutineScope(Dispatchers.Unconfined + job)
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

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

    @Test
    fun `synchronous constructor signals awaitReady immediately`() = runBlocking {
        // Both `true` and `false` synchronous-construction paths represent
        // "the answer is known" and must not block awaitReady — the gate is
        // only for the async-check (`create`) path.
        val ready = DeviceRegistrationStatus(initiallyRegistered = true)
        withTimeout(TIMEOUT_MS) { ready.awaitReady() }
        assertTrue(ready.awaitReadyBlocking(SHORT_TIMEOUT_MS))

        val notReady = DeviceRegistrationStatus(initiallyRegistered = false)
        withTimeout(TIMEOUT_MS) { notReady.awaitReady() }
        assertTrue(notReady.awaitReadyBlocking(SHORT_TIMEOUT_MS))
    }

    @Test
    fun `create signals awaitReady once initialCheck resolves to true`() = runBlocking {
        val status = DeviceRegistrationStatus.create(scope) { true }
        withTimeout(TIMEOUT_MS) { status.awaitReady() }
        assertTrue(
            "complete.value must reflect the authoritative on-disk answer after awaitReady",
            status.complete.value
        )
    }

    @Test
    fun `create signals awaitReady once initialCheck resolves to false`() = runBlocking {
        // Empty initial state is a valid first emission and must NOT block
        // awaitReady forever — see #419 finding #3 / #414's awaitReady semantics.
        val status = DeviceRegistrationStatus.create(scope) { false }
        withTimeout(TIMEOUT_MS) { status.awaitReady() }
        assertFalse(status.complete.value)
    }

    @Test
    fun `create defers awaitReady until initialCheck completes`() = runBlocking {
        // Regression for #419 finding #3: synchronous readers of
        // `complete.value` immediately after construction would see `false`
        // even on an already-registered device. With awaitReady in place,
        // callers can wait for the authoritative answer to land.
        val gate = CompletableDeferred<Unit>()
        val status = DeviceRegistrationStatus.create(scope) {
            gate.await()
            true
        }

        // While the check is gated, awaitReadyBlocking with a short timeout
        // reports `false` and complete.value is still the pre-load default.
        assertFalse(status.awaitReadyBlocking(SHORT_TIMEOUT_MS))
        assertFalse(status.complete.value)

        // Open the gate; awaitReady completes and complete.value reflects the
        // loaded "registered" verdict.
        gate.complete(Unit)
        withTimeout(TIMEOUT_MS) { status.awaitReady() }
        assertTrue(status.complete.value)
    }

    @Test
    fun `awaitReady is idempotent across multiple calls`() = runBlocking {
        val status = DeviceRegistrationStatus.create(scope) { true }
        withTimeout(TIMEOUT_MS) { status.awaitReady() }
        // A second call returns immediately.
        withTimeout(TIMEOUT_MS) { status.awaitReady() }
        assertTrue(status.awaitReadyBlocking(SHORT_TIMEOUT_MS))
    }

    companion object {
        private const val TIMEOUT_MS = 5_000L
        private const val SHORT_TIMEOUT_MS = 50L
        private const val SUSPEND_DELAY_MS = 100L
    }
}
