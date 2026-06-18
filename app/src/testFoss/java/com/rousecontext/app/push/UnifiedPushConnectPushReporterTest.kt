package com.rousecontext.app.push

import androidx.test.core.app.ApplicationProvider
import com.rousecontext.app.auth.DeviceCredentialProvider
import com.rousecontext.app.delivery.UnifiedPushBackgroundDelivery
import com.rousecontext.app.state.DeviceRegistrationStatus
import com.rousecontext.tunnel.CertificateStore
import com.rousecontext.tunnel.OnboardingFlow
import com.rousecontext.tunnel.TunnelClient
import com.rousecontext.tunnel.TunnelState
import com.rousecontext.work.ConnectPushReporter
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Confirm-then-fix test for the foss connect-time endpoint reporter (issue #485).
 *
 * The strand: a UnifiedPush endpoint can rotate while the tunnel is DOWN. In
 * that window [UnifiedPushBackgroundDelivery.onEndpoint] persists the new
 * endpoint but its `refreshEndpoint` path defers the relay update (the tunnel
 * isn't connected), trusting "a future reconnect re-reports it". The google
 * flavor's `FcmConnectPushReporter` makes that true by re-sending on every
 * connect; the foss flavor must do the same for its persisted endpoint, else
 * the relay keeps waking the STALE endpoint and the device is unwakeable.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class UnifiedPushConnectPushReporterTest {

    private val credentialProvider = mockk<DeviceCredentialProvider>(relaxed = true)
    private val registrationStatus = DeviceRegistrationStatus(initiallyRegistered = true)
    private val onboardingFlow = mockk<OnboardingFlow>(relaxed = true)

    // Device already registered: a subdomain is persisted, so onEndpoint takes
    // the refresh path (not deferred deferred-registration).
    private val certificateStore = mockk<CertificateStore>(relaxed = true).also {
        coEvery { it.getSubdomain() } returns "abc.example"
    }

    // Tunnel is DOWN when the endpoint rotates, so refreshEndpoint defers.
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

    @Test
    fun `reports rotated endpoint on connect after rotation while disconnected`() = runTest {
        val delivery = delivery(this)

        // Endpoint rotates while the tunnel is down -> persisted, refresh deferred.
        delivery.onEndpoint(ROTATED_ENDPOINT)
        advanceUntilIdle()
        coVerify(exactly = 0) { tunnelClient.sendPushEndpoint(any(), any()) }

        // Tunnel connects -> the connect reporter fires (post-connect).
        val reporter: ConnectPushReporter = UnifiedPushConnectPushReporter(
            tunnelClient = tunnelClient,
            delivery = delivery
        )
        reporter.reportOnConnect()

        // The relay must now receive the NEW endpoint.
        coVerify(exactly = 1) {
            tunnelClient.sendPushEndpoint("unifiedpush", ROTATED_ENDPOINT)
        }
    }

    @Test
    fun `does nothing when no endpoint persisted`() = runTest {
        coEvery { certificateStore.getSubdomain() } returns null
        val delivery = delivery(this)

        val reporter = UnifiedPushConnectPushReporter(
            tunnelClient = tunnelClient,
            delivery = delivery
        )
        reporter.reportOnConnect()

        coVerify(exactly = 0) { tunnelClient.sendPushEndpoint(any(), any()) }
    }

    private companion object {
        const val ROTATED_ENDPOINT = "https://push.example/rotated-endpoint"
    }
}
