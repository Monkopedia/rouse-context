package com.rousecontext.bridge

import com.rousecontext.tunnel.TunnelClient
import com.rousecontext.tunnel.TunnelError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Collects incoming sessions from [TunnelClient.incomingSessions] and handles each
 * one using a [SessionHandler].
 *
 * Each incoming mux stream is handled in its own child coroutine, allowing concurrent
 * MCP sessions. If one session fails, others continue independently.
 *
 * Usage:
 * ```
 * val manager = TunnelSessionManager(tunnelClient, sessionHandler, lifecycleScope)
 * manager.start()
 * // Sessions are handled automatically until the scope is cancelled
 * ```
 */
class TunnelSessionManager(
    private val tunnelClient: TunnelClient,
    private val sessionHandler: SessionHandler,
    private val scope: CoroutineScope
) {

    private var collectionJob: Job? = null

    /**
     * Starts collecting incoming sessions. Safe to call multiple times;
     * subsequent calls are no-ops if already running.
     *
     * Each session is dispatched to [Dispatchers.IO] so that blocking socket
     * I/O and Ktor's internal `runBlocking` bridges (e.g. `CIOApplicationEngine.start`)
     * do not run on the caller's dispatcher. In production that dispatcher is
     * typically the Android main thread; in tests it is the `runBlocking` event
     * loop. Pinning handler work to IO prevents the nested runBlocking calls
     * inside Ktor from deadlocking on the same thread that is driving the
     * outer coroutine. See issue #223.
     */
    fun start() {
        if (collectionJob?.isActive == true) return

        collectionJob = scope.launch {
            tunnelClient.incomingSessions.collect { stream ->
                launch(Dispatchers.IO) {
                    try {
                        sessionHandler.handleStream(stream)
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        // On the JVM this is a java.util.concurrent.Cancellation-
                        // Exception, which extends IllegalStateException, so the
                        // broad clause below would otherwise eat it: order here is
                        // load-bearing, not decoration. Today nothing observable
                        // changes if this clause goes -- the handler call is the
                        // last thing in the coroutine, so an already-cancelled job
                        // ends up cancelled either way, and the test suite cannot
                        // tell the difference (it was ablated to check). It stays
                        // because it matches SessionHandler's guard and because it
                        // becomes load-bearing the moment anything is added after
                        // the try.
                        throw e
                    } catch (e: TunnelError.UnhandledTlsState) {
                        // NOT a peer going away: our own TLS layer reached a state
                        // it has no handling for. The same discriminator the copy
                        // loops in SessionHandler apply (#616, #626, #630).
                        //
                        // This used to fall into the broad clause below, whose
                        // comment claimed "errors are logged at the session
                        // level". That was never true of this one: SessionHandler
                        // deliberately rethrows it precisely because :core:bridge
                        // is a KMP jvm target with no Android Log or CrashReporter
                        // on its classpath. Letting it leave this coroutine IS the
                        // report -- swallowing it here reproduced the clean EOF
                        // #615/#616/#626/#630 spent four issues eliminating (#638).
                        throw e
                    } catch (_: Exception) {
                        // A peer hanging up mid-session: normal, frequent, and not
                        // worth a word. Swallowed so one bad session cannot take
                        // the collector down -- which is all this clause was ever
                        // meant to do.
                    }
                }
            }
        }
    }

    /**
     * Stops collecting incoming sessions. Active sessions continue until they
     * finish or the parent scope is cancelled.
     */
    fun stop() {
        collectionJob?.cancel()
        collectionJob = null
    }
}
