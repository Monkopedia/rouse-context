package com.rousecontext.app.state

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Shared observable for whether the device has completed relay registration
 * (i.e. has a subdomain). Provided as a Koin singleton so that
 * [com.rousecontext.app.ui.viewmodels.OnboardingViewModel] can signal
 * completion and [com.rousecontext.app.ui.viewmodels.IntegrationSetupViewModel]
 * can wait for it.
 *
 * ### Cold-start readiness (issue #419 finding #3)
 *
 * Pre-#419 callers constructed this with `initiallyRegistered = false` and
 * relied on a separate `appScope.launch{}` to consult the cert store and call
 * [markComplete]. Between construction and that async check landing, [complete]
 * reported `false` even on already-registered devices — see
 * `IntegrationSetupViewModel.awaitRegistrationIfNeeded` (issue #242 workaround).
 *
 * Production code now uses [create], which seeds the status from a suspend
 * "is registered" check and exposes the same [awaitReady] / [awaitReadyBlocking]
 * shape as [com.rousecontext.mcp.core.ProviderRegistry]. Callers that need to
 * act on [complete]'s value MUST first await readiness — otherwise they may
 * read the pre-load `false` default. The legacy [awaitComplete] suspends until
 * registration is *positively* complete (the original semantics) and is left
 * untouched for existing UX-flow callers (#419 hard rule: don't touch
 * `IntegrationSetupViewModel.awaitRegistrationIfNeeded`).
 *
 * The synchronous constructor remains available for tests that already know
 * the answer at construction time; in that case the status is immediately
 * [awaitReady]-ready.
 */
class DeviceRegistrationStatus internal constructor(
    initiallyRegistered: Boolean,
    initiallyReady: Boolean
) {

    /** Public synchronous constructor: tests / callers that know the answer up front. */
    constructor(initiallyRegistered: Boolean = false) :
        this(initiallyRegistered = initiallyRegistered, initiallyReady = true)

    private val _complete = MutableStateFlow(initiallyRegistered)
    val complete: StateFlow<Boolean> = _complete.asStateFlow()

    private val readyDeferred = CompletableDeferred<Unit>()
    private val readyLatch = CountDownLatch(1)

    init {
        if (initiallyReady) {
            signalReady()
        }
    }

    fun markComplete() {
        _complete.value = true
    }

    internal fun signalReady() {
        readyDeferred.complete(Unit)
        if (readyLatch.count > 0) {
            readyLatch.countDown()
        }
    }

    /**
     * Suspends until registration is complete. Returns immediately if already done.
     *
     * NOTE: this is the legacy "wait until positively registered" hook used by
     * `IntegrationSetupViewModel`. It does NOT return when the initial check
     * concludes "not registered" — for that, use [awaitReady].
     */
    suspend fun awaitComplete() {
        complete.first { it }
    }

    /**
     * Suspends until the initial registration check has resolved (regardless of
     * its verdict). Mirrors the
     * [com.rousecontext.mcp.core.ProviderRegistry.awaitReady] shape introduced
     * in #416 / #414. After this returns, [complete]'s value reflects the
     * authoritative on-disk answer (i.e. it is no longer the pre-load default).
     */
    suspend fun awaitReady() {
        readyDeferred.await()
    }

    /**
     * Thread-blocking variant of [awaitReady]. MUST NOT be called from the main
     * thread.
     */
    fun awaitReadyBlocking(timeoutMs: Long): Boolean =
        readyLatch.await(timeoutMs, TimeUnit.MILLISECONDS)

    companion object {
        /**
         * Production factory: launches [initialCheck] in [scope] and signals
         * [awaitReady] once it resolves. If the check returns `true`, marks
         * the status complete; otherwise leaves [complete] at `false` until a
         * subsequent [markComplete] call.
         *
         * Returns immediately; the returned status is in a "not ready"
         * pre-load window for as long as [initialCheck] takes to suspend
         * past its first DataStore / disk read.
         */
        fun create(
            scope: CoroutineScope,
            initialCheck: suspend () -> Boolean
        ): DeviceRegistrationStatus {
            val status = DeviceRegistrationStatus(
                initiallyRegistered = false,
                initiallyReady = false
            )
            scope.launch {
                val registered = initialCheck()
                if (registered) {
                    status.markComplete()
                }
                status.signalReady()
            }
            return status
        }
    }
}
