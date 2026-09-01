package com.rousecontext.tunnel

import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Timeout

/**
 * What the keepalive path does when the **caller** is cancelled while a Ping is
 * in flight (#646).
 *
 * Three broad catches sit on top of each other here, and the order they are
 * fixed in is the whole point:
 *
 * ```
 * MuxDemux.sendPingAwaitPong        catch (_: Exception) { false }
 *   TunnelClientImpl.runKeepaliveLoop  catch (_: Exception) { false }   -> counts a miss
 *   TunnelClientImpl.healthCheck       catch (_: Exception) { false }   -> "tunnel is dead"
 * ```
 *
 * #650 added a cancellation rethrow to `WakeReconnectDecider`, above
 * `healthCheck`. It could not fire: both frames below it converted cancellation
 * into `false` first. **A guard's presence is not evidence of its
 * reachability**, and the swallow has to be fixed at the lowest frame that
 * performs it or every guard above stays dead. So the [MuxDemux] test here is
 * the load-bearing one; the two [TunnelClientImpl] tests only become meaningful
 * once it passes.
 *
 * The cost of getting this wrong is concrete: a cancelled Ping increments the
 * miss counter until [TunnelClientImpl] synthesises
 * `ConnectionFailed("Keepalive Pings missed N times")` out of an ordinary
 * teardown, and a cancelled `healthCheck` reads as a dead tunnel, after which
 * the wake path proceeds to `disconnect()` and `connect()` -- work performed
 * after cancellation.
 *
 * ## Why these tests cancel a job rather than throwing a cancellation
 *
 * [MuxDemux.sendPingAwaitPong] discriminates on the *job*, not on the exception
 * type, and it has to: [MuxDemux.closeAllQuietly] **cancels** every pending ping
 * waiter when the transport dies (issue #230), and that must keep reading as a
 * failed health check -- a dead relay is not a cancelled caller. A blanket
 * rethrow there hangs `MuxDemuxTest.awaitPongReturnsFalseWhenTransportClosedQuietly`.
 * So the cancellation tests below cancel the calling job, which is the real
 * production vector, and the discriminator itself is pinned by the
 * "transport torn down under an active caller" test.
 *
 * Cancelling the caller is not ambiguous here the way it would be for a thrown
 * result: the defect returns `Result.success(false)` and the fix returns a
 * failure, so the two are distinguishable without relying on which cancellation
 * arrived.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class KeepaliveCancellationTest {

    @Test
    fun `cancelling the caller propagates out of sendPingAwaitPong`() = runBlocking {
        val demux = MuxDemux()
        demux.onOutgoingFrame = { awaitCancellation() }

        var outcome: Result<Boolean>? = null
        val pinger = launch {
            outcome = runCatching { demux.sendPingAwaitPong(timeoutMillis = LONG_TIMEOUT_MS) }
        }
        delay(SETTLE_MS)
        pinger.cancel()
        pinger.join()

        val result = assertNotNull(outcome, "the ping coroutine never recorded an outcome")
        assertTrue(
            result.isFailure,
            "a cancelled Ping must propagate cancellation rather than read as a missed " +
                "Pong, but came back as $result"
        )
        assertTrue(
            result.exceptionOrNull() is CancellationException,
            "expected cancellation, got ${result.exceptionOrNull()}"
        )
    }

    @Test
    fun `an IO failure from the outgoing frame sink is still a missed Pong`(): Unit = runBlocking {
        val demux = MuxDemux()
        demux.onOutgoingFrame = { throw IOException("socket closed") }

        assertFalse(
            demux.sendPingAwaitPong(timeoutMillis = LONG_TIMEOUT_MS),
            "a genuinely broken transport must still come back as a missed Pong"
        )
    }

    @Test
    fun `a transport torn down under an active caller is still a missed Pong`() = runBlocking {
        // Issue #230's property, re-pinned here because the cancellation guard
        // added for #646 is the code that has to preserve it: closeAllQuietly
        // cancels the waiter, and the caller is NOT cancelled, so this must stay
        // `false` rather than propagating.
        val demux = MuxDemux()
        demux.onOutgoingFrame = { /* swallow: the relay is dead */ }

        var outcome: Result<Boolean>? = null
        val pinger = launch {
            outcome = runCatching { demux.sendPingAwaitPong(timeoutMillis = LONG_TIMEOUT_MS) }
        }
        delay(SETTLE_MS)
        demux.closeAllQuietly()
        pinger.join()

        assertEquals(
            Result.success(false),
            outcome,
            "a dead relay is not a cancelled caller: this must stay a failed health check"
        )
    }

    @Test
    fun `cancelling the caller propagates out of healthCheck`() = runBlocking {
        val client = TunnelClientImpl(
            scope = this,
            webSocketFactory = HangingSendFactory(),
            keepaliveIntervalMillis = QUIET_KEEPALIVE_MS
        )
        client.connect(URL)

        var outcome: Result<Boolean>? = null
        val checker = launch { outcome = runCatching { client.healthCheck(LONG_TIMEOUT) } }
        delay(SETTLE_MS)
        checker.cancel()
        checker.join()

        val result = assertNotNull(outcome, "the health check never recorded an outcome")
        assertTrue(
            result.isFailure,
            "a cancelled health check must propagate cancellation so the guard in " +
                "WakeReconnectDecider can fire, but came back as $result"
        )
        assertTrue(
            result.exceptionOrNull() is CancellationException,
            "expected cancellation, got ${result.exceptionOrNull()}"
        )

        coroutineContext.cancelChildren()
    }

    @Test
    fun `a broken transport still makes healthCheck report false`(): Unit = runBlocking {
        val client = TunnelClientImpl(
            scope = this,
            webSocketFactory = FailingSendFactory(IOException("socket closed")),
            keepaliveIntervalMillis = QUIET_KEEPALIVE_MS
        )
        client.connect(URL)

        assertFalse(
            client.healthCheck(SHORT_TIMEOUT),
            "a genuinely broken transport must still report the tunnel as dead"
        )

        coroutineContext.cancelChildren()
    }

    @Test
    fun `tearing the tunnel scope down mid-Ping is not counted as a missed Ping`() = runBlocking {
        val logs = CopyOnWriteArrayList<String>()
        // The client's own scope, so it can be torn down while the test's scope
        // stays alive to make the assertion.
        val tunnelScope = CoroutineScope(coroutineContext + Job(coroutineContext[Job]))
        val client = TunnelClientImpl(
            scope = tunnelScope,
            webSocketFactory = HangingSendFactory(),
            log = { _, message -> logs += message },
            keepaliveIntervalMillis = BRISK_KEEPALIVE_MS,
            keepaliveMaxMisses = 5
        )
        client.connect(URL)
        // The loop is now parked inside the Ping, which is where a service
        // shutdown finds it.
        delay(SETTLE_MS)

        tunnelScope.cancel()
        delay(SETTLE_MS)

        assertTrue(
            logs.none { it.contains("keepalive Ping missed") },
            "a cancelled Ping is teardown, not a missed Pong, but the loop logged: $logs"
        )
    }

    @Test
    fun `a broken transport still trips the keepalive miss counter`() = runBlocking {
        val errors = mutableListOf<TunnelError>()
        val client = TunnelClientImpl(
            scope = this,
            webSocketFactory = FailingSendFactory(IOException("socket closed")),
            keepaliveIntervalMillis = BRISK_KEEPALIVE_MS,
            keepaliveMaxMisses = 1
        )
        val collector = launch { client.errors.collect { errors += it } }
        delay(SETTLE_MS)
        client.connect(URL)
        delay(SETTLE_MS)

        assertTrue(
            errors.any {
                it is TunnelError.ConnectionFailed && it.message?.contains("Keepalive") == true
            },
            "the ordinary dead-tunnel path must be unchanged, but errors held $errors"
        )
        assertEquals(TunnelState.DISCONNECTED, client.state.value)

        collector.cancel()
        coroutineContext.cancelChildren()
    }

    /**
     * Reports the handshake as open immediately, then never completes a send:
     * exactly where a scope teardown finds the keepalive.
     */
    private class HangingSendFactory : WebSocketFactory {
        override fun connect(url: String, listener: WebSocketListener): WebSocketHandle {
            listener.onOpen()
            return object : WebSocketHandle {
                override suspend fun sendBinary(data: ByteArray): Boolean = awaitCancellation()

                override suspend fun sendText(text: String): Boolean = awaitCancellation()

                override suspend fun close(code: Int, reason: String) = Unit
            }
        }
    }

    /** Reports the handshake as open, then fails every send outright. */
    private class FailingSendFactory(private val failure: Throwable) : WebSocketFactory {
        override fun connect(url: String, listener: WebSocketListener): WebSocketHandle {
            listener.onOpen()
            return object : WebSocketHandle {
                override suspend fun sendBinary(data: ByteArray): Boolean = throw failure

                override suspend fun sendText(text: String): Boolean = throw failure

                override suspend fun close(code: Int, reason: String) = Unit
            }
        }
    }

    private companion object {
        const val URL = "ws://127.0.0.1:1/tunnel"

        /** Longer than any test's lifetime: the Pong wait must not time out. */
        const val LONG_TIMEOUT_MS = 600_000L
        val LONG_TIMEOUT = 600.seconds

        val SHORT_TIMEOUT = 1.seconds

        /** Far beyond any test's lifetime: the keepalive loop must not interfere. */
        const val QUIET_KEEPALIVE_MS = 600_000L

        const val BRISK_KEEPALIVE_MS = 30L

        const val SETTLE_MS = 250L
    }
}
