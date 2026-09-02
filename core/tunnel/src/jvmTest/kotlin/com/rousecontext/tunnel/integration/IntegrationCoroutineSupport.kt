package com.rousecontext.tunnel.integration

import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope

/**
 * A [CoroutineScope] for tunnel machinery driven by an integration test, whose
 * uncaught exceptions are recorded instead of printed.
 *
 * ## Why this exists (#600)
 *
 * The integration classes that carry a `SEPARATE_THREAD` `@Timeout` are safe to
 * do so because they emit no output, so there is nothing to race Gradle's
 * per-test output store (#501, #504). That was established by measuring
 * `system-out`/`system-err` on green runs -- which is evidence about the runs
 * that were observed, not a property of the code.
 *
 * **Two** paths escape that measurement, and this class closes the first.
 *
 * 1. `TunnelClientImpl` stores the caller's scope raw -- no re-wrap, no
 *    `SupervisorJob` -- so the handler is in context for all eight of its
 *    `scope.launch` sites, four of them bare
 *    `scope.launch { handleDisconnect(...) }`. A bare
 *    `CoroutineScope(Dispatchers.IO)` is a ROOT scope: its `Job` has no parent,
 *    so a throw out of one of those launches has nowhere to propagate and ends
 *    at the default handler, which writes a stack trace to `System.err` **from
 *    a background thread**. That can only happen on the failure path -- which
 *    is exactly when a ceiling fires and JUnit has stopped listening. No grep
 *    for `println` finds it. **This is the one this class exists to close.**
 *
 * 2. `TestRelayFixture.stop()` prints ~100 bytes to `System.err` when the relay
 *    subprocess survives `destroyForcibly()` plus a 3 s wait, and
 *    `EndToEndSessionTest` calls `stop()` from inside a test body rather than
 *    only from teardown -- so an abandoned thread can reach it. Left unguarded
 *    on purpose: it needs a kernel-level stall AND the ceiling to fire in the
 *    same window, for ~100 bytes, and what it replaces is the unbounded hang
 *    that loses the whole run. Recorded rather than fixed.
 *
 * Installing a [CoroutineExceptionHandler] makes "this class emits no output"
 * structurally true rather than run-dependent, which is the difference between
 * the two kinds of evidence the #600 audit had to keep apart.
 *
 * ## What it deliberately does NOT change
 *
 * No `SupervisorJob`, and no production code. The handler is consulted because
 * these are root coroutines, not because the job is supervised, so the scope
 * keeps the ordinary propagation it has today: a throw still cancels the
 * scope's other children. The only thing that changes is the *destination* of
 * the throwable -- [uncaught] instead of `System.err`. Nothing is swallowed
 * silently; a test that wants to assert on failures can read [uncaught].
 */
class IntegrationScope(context: CoroutineContext) {

    /** Uncaught throwables from coroutines launched in [scope], newest last. */
    val uncaught: MutableList<Throwable> = CopyOnWriteArrayList()

    val scope: CoroutineScope = CoroutineScope(
        context + CoroutineExceptionHandler { _, throwable -> uncaught += throwable }
    )
}

/**
 * Shorthand for [IntegrationScope] when the test only needs the scope itself.
 *
 * Prefer this over a bare `CoroutineScope(Dispatchers.IO)` anywhere the scope is
 * handed to `TunnelClientImpl`, for the reason on [IntegrationScope].
 */
fun integrationScope(context: CoroutineContext): CoroutineScope = IntegrationScope(context).scope
