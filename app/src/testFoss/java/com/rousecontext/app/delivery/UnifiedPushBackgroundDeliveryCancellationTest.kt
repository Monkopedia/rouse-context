package com.rousecontext.app.delivery

import android.util.Log
import androidx.test.core.app.ApplicationProvider
import com.rousecontext.app.auth.DeviceCredentialProvider
import com.rousecontext.app.state.DeviceRegistrationStatus
import com.rousecontext.tunnel.CertificateStore
import com.rousecontext.tunnel.OnboardingFlow
import com.rousecontext.tunnel.TunnelClient
import com.rousecontext.tunnel.TunnelState
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLog

/**
 * Pins both directions of the `refreshEndpoint` broad catch (issue #666).
 *
 * Unlike the two connect-time reporters, this one runs on the
 * application-lifetime `appScope`, which nothing cancels today — so the guard is
 * defence in depth. The test cancels the injected scope directly, which is the
 * only way the site can currently take delivery of cancellation, and asserts on
 * the log line the issue names: a cancelled send must not be reported as
 * "Failed to refresh endpoint".
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class UnifiedPushBackgroundDeliveryCancellationTest {

    private val credentialProvider = mockk<DeviceCredentialProvider>(relaxed = true)
    private val registrationStatus = DeviceRegistrationStatus(initiallyRegistered = true)
    private val onboardingFlow = mockk<OnboardingFlow>(relaxed = true)

    // Already registered, so onEndpoint takes the refresh path.
    private val certificateStore = mockk<CertificateStore>(relaxed = true).also {
        coEvery { it.getSubdomain() } returns "abc.example"
    }

    // Tunnel UP, so refreshEndpoint reaches the send instead of deferring.
    private val tunnelClient = mockk<TunnelClient>(relaxed = true).also {
        every { it.state } returns MutableStateFlow(TunnelState.CONNECTED)
    }

    @Before
    fun clearLogs() {
        ShadowLog.clear()
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

    @Test
    fun `cancelling the scope mid-send is not reported as a refresh failure`() {
        val entered = CompletableDeferred<Unit>()
        coEvery { tunnelClient.sendPushEndpoint(any(), any()) } coAnswers {
            entered.complete(Unit)
            awaitCancellation()
        }
        val scope = CoroutineScope(Job() + UnconfinedTestDispatcher())

        delivery(scope).onEndpoint(ENDPOINT)
        runBlocking { entered.await() }

        scope.cancel()
        runBlocking { scope.coroutineContext.job.join() }

        assertEquals(
            "a cancelled send must unwind, not be logged as a failed endpoint refresh",
            emptyList<String>(),
            refreshWarnings()
        )
    }

    /**
     * Doubles as the ordering pin. `CancellationException` extends
     * `IllegalStateException` on the JVM, so a guard written as
     * `catch (e: IllegalStateException) { throw e }` looks right, passes the
     * cancellation test above, and rethrows every genuine ISE. Using ISE (not a
     * plain RuntimeException) for the non-fatal direction is what closes that:
     * on the mis-shaped guard this goes red with `expected:<1> but was:<0>`.
     */
    @Test
    fun `ordinary IllegalStateException still logs and stays non-fatal`() {
        coEvery {
            tunnelClient.sendPushEndpoint(any(), any())
        } throws IllegalStateException("relay refused")
        val scope = CoroutineScope(Job() + UnconfinedTestDispatcher())

        // Must not escape onEndpoint's launch and take the scope down.
        delivery(scope).onEndpoint(ENDPOINT)

        coVerify(exactly = 1) {
            tunnelClient.sendPushEndpoint(UnifiedPushBackgroundDelivery.PUSH_KIND, ENDPOINT)
        }
        assertEquals(1, refreshWarnings().size)
        scope.cancel()
    }

    /** WARN lines from the refresh path, by the message the issue names. */
    private fun refreshWarnings(): List<String> = ShadowLog.getLogs()
        .filter { it.type == Log.WARN && it.tag == "UnifiedPushDelivery" }
        .map { it.msg }
        .filter { it.startsWith("Failed to refresh endpoint") }

    private companion object {
        const val ENDPOINT = "https://push.example/endpoint"
    }
}
