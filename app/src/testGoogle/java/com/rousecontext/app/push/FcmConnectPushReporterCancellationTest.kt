package com.rousecontext.app.push

import com.rousecontext.app.auth.FcmTokenProvider
import com.rousecontext.tunnel.TunnelClient
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Pins both directions of [FcmConnectPushReporter]'s broad catch (issue #666).
 *
 * `reportOnConnect` runs on `TunnelForegroundService`'s `lifecycleScope`, which
 * IS cancelled at session teardown, and `tunnelClient.sendFcmToken` genuinely
 * suspends (`WebSocketHandle.sendText` awaits the Ktor session and sends a
 * frame). So an ordinary disconnect races into the catch. Before #666 that was
 * logged as "Failed to send FCM token to relay" and the coroutine continued
 * past the point where it should have unwound.
 *
 * Robolectric only so `android.util.Log` resolves to no-ops.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class FcmConnectPushReporterCancellationTest {

    private val tunnelClient = mockk<TunnelClient>(relaxed = true)
    private val tokenProvider = object : FcmTokenProvider {
        override suspend fun currentToken(): String = "fcm-token"
    }

    private val reporter = FcmConnectPushReporter(
        tunnelClient = tunnelClient,
        tokenProvider = tokenProvider
    )

    @Test
    fun `cancelling the session scope mid-send propagates instead of being logged as a failure`() =
        runTest {
            val entered = CompletableDeferred<Unit>()
            coEvery { tunnelClient.sendFcmToken(any()) } coAnswers {
                entered.complete(Unit)
                awaitCancellation()
            }

            // null == reportOnConnect returned normally, i.e. it swallowed the
            // cancellation and let the caller continue past teardown.
            val escaped = CompletableDeferred<Throwable?>()
            val job = launch {
                try {
                    reporter.reportOnConnect()
                    escaped.complete(null)
                } catch (e: CancellationException) {
                    escaped.complete(e)
                    throw e
                }
            }

            entered.await()
            job.cancel()
            job.join()

            val outcome = escaped.await()
            assertTrue(
                "cancellation must propagate out of reportOnConnect, but it returned " +
                    "normally (swallowed by the broad catch)",
                outcome is CancellationException
            )
        }

    @Test
    fun `ordinary send failure still logs and stays non-fatal`() = runTest {
        coEvery { tunnelClient.sendFcmToken(any()) } throws RuntimeException("relay refused")

        // Must not throw: a failed report is non-fatal, the next connect retries.
        reporter.reportOnConnect()

        coVerify(exactly = 1) { tunnelClient.sendFcmToken("fcm-token") }
    }

    /**
     * `CancellationException` extends `IllegalStateException` on the JVM, so a
     * guard written as `catch (e: IllegalStateException)` would look right and
     * swallow the wrong half. This pins the direction that distinguishes them.
     */
    @Test
    fun `IllegalStateException is still swallowed`() = runTest {
        coEvery { tunnelClient.sendFcmToken(any()) } throws IllegalStateException("bad state")

        val thrown: Throwable? = try {
            reporter.reportOnConnect()
            null
        } catch (e: Throwable) {
            e
        }

        assertNull("a plain IllegalStateException must stay non-fatal", thrown)
    }
}
