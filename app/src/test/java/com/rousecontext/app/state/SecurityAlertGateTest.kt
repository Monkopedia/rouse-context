package com.rousecontext.app.state

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Regression tests for issue #419 finding #1: the security alert flag was read
 * synchronously from a `stateIn(..., initialValue = false)` flow, so a request
 * that arrived before the flow had emitted its first value would always be
 * served, even if the persisted state said "alert".
 *
 * [SecurityAlertGate] mirrors [com.rousecontext.app.registry.IntegrationProviderRegistry]'s
 * `awaitReady` shape: callers MUST suspend (or block briefly) on readiness before
 * reading the alert state.
 */
class SecurityAlertGateTest {

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
    fun `isAlerting suspends until first emission then returns the alert value`() = runBlocking {
        // Slow source: flow emits only after the gate completes. Without the
        // awaitReady fix, isAlerting() would return false (the stateIn default)
        // even though the persisted state says "alert".
        val gate = CompletableDeferred<Unit>()
        val source: Flow<Boolean> = flow {
            gate.await()
            emit(true)
        }
        val alertGate = SecurityAlertGate(source = source, scope = scope)

        // While the source has not emitted, awaitReadyBlocking with a short
        // timeout reports `false` (i.e. not yet ready).
        assertFalse(alertGate.awaitReadyBlocking(SHORT_TIMEOUT_MS))

        // Open the gate; isAlerting awaits readiness then returns the loaded value.
        gate.complete(Unit)
        val alerting = withTimeout(TIMEOUT_MS) { alertGate.isAlerting() }
        assertTrue(
            "isAlerting must reflect the persisted alert state once the flow emits",
            alerting
        )
    }

    @Test
    fun `isAlerting returns false when first emission says no alert`() = runBlocking {
        // An empty/no-alert initial state is a valid first emission and must
        // unblock awaiters — they shouldn't hang waiting for "true" specifically.
        val source = MutableStateFlow(false)
        val alertGate = SecurityAlertGate(source = source, scope = scope)

        val alerting = withTimeout(TIMEOUT_MS) { alertGate.isAlerting() }
        assertFalse(alerting)
    }

    @Test
    fun `awaitReady is idempotent across multiple calls`() = runBlocking {
        val source = MutableStateFlow(false)
        val alertGate = SecurityAlertGate(source = source, scope = scope)

        withTimeout(TIMEOUT_MS) { alertGate.awaitReady() }
        // Second call must return immediately.
        withTimeout(TIMEOUT_MS) { alertGate.awaitReady() }
        // And awaitReadyBlocking with a tiny timeout must succeed.
        assertTrue(alertGate.awaitReadyBlocking(SHORT_TIMEOUT_MS))
    }

    @Test
    fun `isAlerting reflects subsequent emissions after first`() = runBlocking {
        val source = MutableStateFlow(false)
        val alertGate = SecurityAlertGate(source = source, scope = scope)

        // First emission: not alerting.
        assertEquals(false, withTimeout(TIMEOUT_MS) { alertGate.isAlerting() })

        // Flip to alerting; the next call MUST see the new value.
        source.value = true
        assertEquals(true, withTimeout(TIMEOUT_MS) { alertGate.isAlerting() })
    }

    @Test
    fun `awaitReady does not complete before first emission lands`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val source: Flow<Boolean> = flow {
            gate.await()
            emit(false)
        }
        val alertGate = SecurityAlertGate(source = source, scope = scope)

        // While gated, a coroutine awaiting readiness must remain suspended.
        val waiter = CompletableDeferred<Unit>()
        val awaiting = launch(Dispatchers.Default) {
            alertGate.awaitReady()
            waiter.complete(Unit)
        }

        delay(SUSPEND_DELAY_MS)
        assertFalse(
            "awaitReady must not complete before the source emits",
            waiter.isCompleted
        )

        gate.complete(Unit)
        withTimeout(TIMEOUT_MS) { waiter.await() }
        awaiting.join()
    }

    companion object {
        private const val TIMEOUT_MS = 5_000L
        private const val SHORT_TIMEOUT_MS = 50L
        private const val SUSPEND_DELAY_MS = 100L
    }
}
