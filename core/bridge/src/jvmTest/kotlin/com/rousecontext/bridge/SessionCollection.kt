package com.rousecontext.bridge

import com.rousecontext.tunnel.TunnelClient
import com.rousecontext.tunnel.TunnelError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Test scaffolding: collects [TunnelClient.incomingSessions] into [handler],
 * one child coroutine per stream, and returns the collecting [Job]. Cancel it
 * to stop collecting.
 *
 * This is deliberately a test helper and not a production class. It used to be
 * one -- `TunnelSessionManager` in `jvmMain` -- but nothing in any `main`
 * source set ever constructed it; every call site it ever had was a test, and
 * the collector that actually ships is
 * `TunnelForegroundService.collectIncomingSessions` (see
 * `work/src/main/kotlin/com/rousecontext/work/TunnelForegroundService.kt`).
 * Sitting in `jvmMain` it read as a second, competing production collector one
 * wiring change away from being used, and its comments argued production
 * error-reporting policy for a code path that never runs. Issue #671 moved it
 * here, next to its only callers, and it makes no claims about production.
 *
 * Sessions run on [Dispatchers.IO]: Ktor's internal `runBlocking` bridges (e.g.
 * `CIOApplicationEngine.start`) deadlock if they land on the `runBlocking`
 * event loop driving the test. See issue #223.
 */
internal fun CoroutineScope.collectSessionsInto(
    tunnelClient: TunnelClient,
    handler: SessionHandler
): Job = launch {
    tunnelClient.incomingSessions.collect { stream ->
        launch(Dispatchers.IO) {
            try {
                handler.handleStream(stream)
            } catch (e: CancellationException) {
                // Teardown cancels these children; that is not a failure.
                throw e
            } catch (e: TunnelError.UnhandledTlsState) {
                // A defect in our own TLS layer. Fail the calling test loudly
                // rather than let it read as a quiet EOF and time out.
                throw e
            } catch (_: Exception) {
                // A peer hanging up mid-session -- including the stream teardown
                // at the end of a test. Swallowed so it cannot fail an otherwise
                // green test, and so one bad session does not end collection.
            }
        }
    }
}
