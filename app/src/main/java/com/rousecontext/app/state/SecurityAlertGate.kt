package com.rousecontext.app.state

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * Holds the latest "is the device under a security alert?" verdict, derived
 * from one or more DataStore-backed flows.
 *
 * ### Why a class instead of a `() -> Boolean` lambda
 *
 * The previous implementation in `AppModule.kt` built a
 * `stateIn(..., initialValue = false)` and exposed `{ alertFlag.value }` as a
 * `() -> Boolean` capture. On a freshly-constructed process (cold-start FCM
 * wake), the underlying `combine()` over two DataStore flows had not yet
 * emitted its first value when the first MCP request arrived — so the gate
 * read the `initialValue = false` default regardless of what was persisted on
 * disk. A device whose security check had reported `alert` would still answer
 * the very first request after wake, bypassing the alert. See issue #419
 * finding #1 (and the parent #415 / #414 / #416 chain that introduced the
 * matching `awaitReady` primitive on `ProviderRegistry`).
 *
 * This gate signals readiness on the first emission of [source] and exposes
 * the same shape as `ProviderRegistry`:
 *
 * - [awaitReady] — suspend until the first emission has landed.
 * - [awaitReadyBlocking] — thread-blocking variant, for callers that cannot
 *   suspend (none today, but kept symmetric for parity).
 * - [isAlerting] — `suspend` reader that internally awaits readiness, so
 *   request handlers cannot accidentally read the pre-load default.
 */
class SecurityAlertGate(source: Flow<Boolean>, scope: CoroutineScope) {

    private val readyDeferred = CompletableDeferred<Unit>()
    private val readyLatch = CountDownLatch(1)
    private val flag = MutableStateFlow(false)

    init {
        scope.launch {
            source.collect { value ->
                flag.value = value
                signalReady()
            }
        }
    }

    private fun signalReady() {
        // Idempotent: subsequent emissions just no-op on the already-completed signals.
        readyDeferred.complete(Unit)
        if (readyLatch.count > 0) {
            readyLatch.countDown()
        }
    }

    /**
     * Suspends until [source] has emitted at least once. Subsequent calls return
     * immediately. After this returns, [isAlerting] reflects the loaded state.
     */
    suspend fun awaitReady() {
        readyDeferred.await()
    }

    /**
     * Thread-blocking variant of [awaitReady]. Returns `true` if readiness was
     * signalled within [timeoutMs], `false` otherwise. MUST NOT be called from
     * the main thread.
     */
    fun awaitReadyBlocking(timeoutMs: Long): Boolean =
        readyLatch.await(timeoutMs, TimeUnit.MILLISECONDS)

    /**
     * Returns the current alert verdict. Suspends until the first emission of
     * the underlying flow has landed, so a freshly-constructed gate cannot
     * report "no alert" before the on-disk state has been read.
     */
    suspend fun isAlerting(): Boolean {
        awaitReady()
        return flag.value
    }
}
