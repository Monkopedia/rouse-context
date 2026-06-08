package com.rousecontext.mcp.core

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred

/**
 * One-shot "first emission has landed" gate.
 *
 * Several DataStore-backed holders need to suspend (or block) until their backing
 * flow has emitted at least once, so that the very first request after a cold-start
 * FCM wake cannot read a pre-load default. This gate owns the paired
 * [CompletableDeferred] (for suspending callers) and [CountDownLatch] (for callers
 * that cannot suspend, e.g. the FCM service's background worker thread) and keeps
 * the two in sync.
 *
 * Mirrors the [ProviderRegistry.awaitReady] / [ProviderRegistry.awaitReadyBlocking]
 * contract; see issue #414 / #416 for why this primitive is load-bearing on the
 * cold-start path.
 *
 * Signalling is idempotent: [signalReady] may be called any number of times (e.g.
 * once per flow emission) and only the first call has effect.
 */
class ReadinessGate {

    private val readyDeferred = CompletableDeferred<Unit>()
    private val readyLatch = CountDownLatch(1)

    /**
     * Marks the gate ready, unblocking any current and future [awaitReady] /
     * [awaitReadyBlocking] callers. Idempotent: subsequent calls no-op on the
     * already-completed signals.
     */
    fun signalReady() {
        readyDeferred.complete(Unit)
        if (readyLatch.count > 0) {
            readyLatch.countDown()
        }
    }

    /** Suspends until [signalReady] has been called. Returns immediately once ready. */
    suspend fun awaitReady() {
        readyDeferred.await()
    }

    /**
     * Thread-blocking variant of [awaitReady]. Returns `true` if the gate became
     * ready within [timeoutMs], `false` otherwise. MUST NOT be called from the
     * main thread.
     */
    fun awaitReadyBlocking(timeoutMs: Long): Boolean =
        readyLatch.await(timeoutMs, TimeUnit.MILLISECONDS)
}
