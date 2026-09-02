package com.rousecontext.app.push

import androidx.test.core.app.ApplicationProvider
import com.rousecontext.app.auth.DeviceCredentialProvider
import com.rousecontext.app.delivery.UnifiedPushBackgroundDelivery
import com.rousecontext.app.state.DeviceRegistrationStatus
import com.rousecontext.tunnel.CertificateStore
import com.rousecontext.tunnel.OnboardingFlow
import com.rousecontext.tunnel.TunnelClient
import com.rousecontext.tunnel.TunnelState
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Pins both directions of [UnifiedPushConnectPushReporter]'s broad catch (#666).
 *
 * `reportOnConnect` runs on `TunnelForegroundService`'s `lifecycleScope`, which
 * IS cancelled at session teardown, and `tunnelClient.sendPushEndpoint`
 * genuinely suspends (`WebSocketHandle.sendText` awaits the Ktor session and
 * sends a frame). So an ordinary disconnect races into the catch. Before #666
 * that was logged as "Failed to report UnifiedPush endpoint on connect" — a
 * misleading line during routine teardown — and the coroutine then continued
 * past the point where it should have unwound.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class UnifiedPushConnectPushReporterCancellationTest {

    private val credentialProvider = mockk<DeviceCredentialProvider>(relaxed = true)
    private val registrationStatus = DeviceRegistrationStatus(initiallyRegistered = true)
    private val onboardingFlow = mockk<OnboardingFlow>(relaxed = true)

    // Already registered, so onEndpoint takes the refresh path rather than the
    // deferred registration.
    private val certificateStore = mockk<CertificateStore>(relaxed = true).also {
        coEvery { it.getSubdomain() } returns "abc.example"
    }

    // Tunnel DOWN when the endpoint rotates, so the refresh defers and the only
    // send in the test is the connect-time one.
    private val tunnelClient = mockk<TunnelClient>(relaxed = true).also {
        every { it.state } returns MutableStateFlow(TunnelState.DISCONNECTED)
    }

    private fun delivery(scope: CoroutineScope) = UnifiedPushBackgroundDelivery(
        appContext = ApplicationProvider.getApplicationContext(),
        onboardingFlow = onboardingFlow,
        credentialProvider = credentialProvider,
        certificateStore = certificateStore,
        registrationStatus = registrationStatus,
        tunnelClient = tunnelClient,
        appScope = scope
    )

    private fun reporter(scope: CoroutineScope) = UnifiedPushConnectPushReporter(
        tunnelClient = tunnelClient,
        delivery = delivery(scope).also { it.onEndpoint(ENDPOINT) }
    )

    @Test
    fun `cancelling the session scope mid-send propagates instead of being logged as a failure`() =
        runTest {
            val reporter = reporter(this)
            advanceUntilIdle()

            val entered = CompletableDeferred<Unit>()
            coEvery { tunnelClient.sendPushEndpoint(any(), any()) } coAnswers {
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
        val reporter = reporter(this)
        advanceUntilIdle()
        coEvery {
            tunnelClient.sendPushEndpoint(any(), any())
        } throws RuntimeException("relay refused")

        // Must not throw: a failed report is non-fatal, the next connect retries.
        reporter.reportOnConnect()

        coVerify(exactly = 1) {
            tunnelClient.sendPushEndpoint(UnifiedPushBackgroundDelivery.PUSH_KIND, ENDPOINT)
        }
    }

    /**
     * `CancellationException` extends `IllegalStateException` on the JVM, so a
     * guard written as `catch (e: IllegalStateException)` would look right and
     * swallow the wrong half. This pins the direction that distinguishes them.
     */
    @Test
    fun `IllegalStateException is still swallowed`() = runTest {
        val reporter = reporter(this)
        advanceUntilIdle()
        coEvery {
            tunnelClient.sendPushEndpoint(any(), any())
        } throws IllegalStateException("bad state")

        val thrown: Throwable? = try {
            reporter.reportOnConnect()
            null
        } catch (e: Throwable) {
            e
        }

        assertNull("a plain IllegalStateException must stay non-fatal", thrown)
    }

    private companion object {
        const val ENDPOINT = "https://push.example/endpoint"
    }
}
