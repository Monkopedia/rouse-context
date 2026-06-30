package com.rousecontext.app.delivery

import androidx.test.core.app.ApplicationProvider
import com.rousecontext.app.auth.DeviceCredentialProvider
import com.rousecontext.app.state.DeviceRegistrationStatus
import com.rousecontext.tunnel.CertificateStore
import com.rousecontext.tunnel.DeviceCredential
import com.rousecontext.tunnel.OnboardingFlow
import com.rousecontext.tunnel.OnboardingResult
import com.rousecontext.tunnel.TunnelClient
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Direct unit tests for the deferred-registration activation logic in
 * [UnifiedPushBackgroundDelivery] (issue #486).
 *
 * Onboarding ([OnboardingFlow.execute]) registers + persists the subdomain
 * FIRST and only then chains ACME cert provisioning (#389). A cert-stage
 * failure therefore returns a `Cert*` result while the device is already
 * registered with its push endpoint reported. Wake-activation (the Home
 * delivery banner) must key off the persisted subdomain — "do we have a push
 * wake target?" — not off whether the cert hop also succeeded. Cert problems
 * are surfaced separately by the dashboard cert-renewal banner.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class UnifiedPushBackgroundDeliveryTest {

    private val credentialProvider = mockk<DeviceCredentialProvider> {
        coEvery { forRegistration() } returns DeviceCredential.Firebase("test-token")
    }
    private val registrationStatus = DeviceRegistrationStatus(initiallyRegistered = false)
    private val tunnelClient = mockk<TunnelClient>(relaxed = true)

    /**
     * In-memory [CertificateStore] whose subdomain is mutated by the faked
     * [OnboardingFlow] (mirroring `registerAndPersist`, which persists the
     * subdomain before the cert hop runs). Only [getSubdomain]/[storeSubdomain]
     * matter here; the rest are relaxed no-ops.
     */
    private val certificateStore = mockk<CertificateStore>(relaxed = true)
    private var persistedSubdomain: String? = null

    init {
        coEvery { certificateStore.getSubdomain() } answers { persistedSubdomain }
    }

    /**
     * Builds an [OnboardingFlow] whose `execute` persists [subdomainOnRegister]
     * (when non-null, modelling the register-before-certs ordering) and then
     * returns [result].
     */
    private fun onboardingFlowReturning(
        result: OnboardingResult,
        subdomainOnRegister: String?
    ): OnboardingFlow = mockk {
        coEvery { execute(any(), any(), any()) } coAnswers {
            if (subdomainOnRegister != null) {
                persistedSubdomain = subdomainOnRegister
            }
            result
        }
    }

    private fun delivery(
        onboardingFlow: OnboardingFlow,
        scope: kotlinx.coroutines.CoroutineScope,
        hasSavedDistributor: Boolean = false
    ) = UnifiedPushBackgroundDelivery(
        appContext = ApplicationProvider.getApplicationContext(),
        onboardingFlow = onboardingFlow,
        credentialProvider = credentialProvider,
        certificateStore = certificateStore,
        registrationStatus = registrationStatus,
        tunnelClient = tunnelClient,
        appScope = scope,
        hasSavedDistributor = { hasSavedDistributor }
    )

    @Test
    fun `cert failure with persisted subdomain activates wake`() = runTest {
        // Subdomain persisted, but the chained cert hop rate-limited (#389).
        val flow = onboardingFlowReturning(
            result = OnboardingResult.CertRateLimited(
                retryAfterSeconds = 60,
                subdomain = "abc.example"
            ),
            subdomainOnRegister = "abc.example"
        )
        val delivery = delivery(flow, this)

        delivery.onEndpoint("https://push.example/endpoint")
        advanceUntilIdle()

        // Cert stumbled but the device is registered with a wake target -> Active.
        assertEquals(DeliveryActivation.Active, delivery.activation.value)
    }

    @Test
    fun `cert relay error with persisted subdomain activates wake`() = runTest {
        val flow = onboardingFlowReturning(
            result = OnboardingResult.CertRelayError(
                statusCode = 500,
                message = "boom",
                subdomain = "abc.example"
            ),
            subdomainOnRegister = "abc.example"
        )
        val delivery = delivery(flow, this)

        delivery.onEndpoint("https://push.example/endpoint")
        advanceUntilIdle()

        assertEquals(DeliveryActivation.Active, delivery.activation.value)
    }

    @Test
    fun `pre-subdomain failure leaves wake needing setup`() = runTest {
        // Registration itself failed -> no subdomain persisted.
        val flow = onboardingFlowReturning(
            result = OnboardingResult.RateLimited(retryAfterSeconds = 60),
            subdomainOnRegister = null
        )
        val delivery = delivery(flow, this)

        delivery.onEndpoint("https://push.example/endpoint")
        advanceUntilIdle()

        assertEquals(DeliveryActivation.NeedsSetup, delivery.activation.value)
    }

    @Test
    fun `success activates wake and marks registration complete`() = runTest {
        val flow = onboardingFlowReturning(
            result = OnboardingResult.Success(subdomain = "abc.example"),
            subdomainOnRegister = "abc.example"
        )
        val delivery = delivery(flow, this)

        delivery.onEndpoint("https://push.example/endpoint")
        advanceUntilIdle()

        assertEquals(DeliveryActivation.Active, delivery.activation.value)
        assertTrue(registrationStatus.complete.value)
    }

    @Test
    fun `init seed with saved distributor and no subdomain is pending setup`() = runTest {
        // The deferred-activation window (#530): the user picked a distributor
        // but its endpoint hasn't landed yet, so no subdomain is persisted.
        persistedSubdomain = null
        val flow = onboardingFlowReturning(
            result = OnboardingResult.Success(subdomain = "unused"),
            subdomainOnRegister = null
        )
        val delivery = delivery(flow, this, hasSavedDistributor = true)
        advanceUntilIdle()

        assertEquals(DeliveryActivation.PendingSetup, delivery.activation.value)
    }

    @Test
    fun `init seed with no distributor and no subdomain needs setup`() = runTest {
        persistedSubdomain = null
        val flow = onboardingFlowReturning(
            result = OnboardingResult.Success(subdomain = "unused"),
            subdomainOnRegister = null
        )
        val delivery = delivery(flow, this, hasSavedDistributor = false)
        advanceUntilIdle()

        assertEquals(DeliveryActivation.NeedsSetup, delivery.activation.value)
    }

    @Test
    fun `selectDistributor flips activation to pending setup`() = runTest {
        // Fresh onboard: nothing saved yet, so the init seed latches NeedsSetup.
        // The hasSavedDistributor seam returns true only after the user picks
        // (modelling saveDistributor persisting synchronously). Picking must flip
        // Home off the alarming NeedsSetup banner immediately (#530), not wait the
        // ~14s for the endpoint to land.
        persistedSubdomain = null
        var saved = false
        val flow = onboardingFlowReturning(
            result = OnboardingResult.Success(subdomain = "unused"),
            subdomainOnRegister = null
        )
        val delivery = UnifiedPushBackgroundDelivery(
            appContext = ApplicationProvider.getApplicationContext(),
            onboardingFlow = flow,
            credentialProvider = credentialProvider,
            certificateStore = certificateStore,
            registrationStatus = registrationStatus,
            tunnelClient = tunnelClient,
            appScope = this,
            hasSavedDistributor = { saved }
        )
        advanceUntilIdle()
        assertEquals(DeliveryActivation.NeedsSetup, delivery.activation.value)

        saved = true
        delivery.selectDistributor("ntfy")

        assertEquals(DeliveryActivation.PendingSetup, delivery.activation.value)
    }

    @Test
    fun `init seed with persisted subdomain is active regardless of distributor`() = runTest {
        persistedSubdomain = "abc.example"
        val flow = onboardingFlowReturning(
            result = OnboardingResult.Success(subdomain = "abc.example"),
            subdomainOnRegister = "abc.example"
        )
        val delivery = delivery(flow, this, hasSavedDistributor = false)
        advanceUntilIdle()

        assertEquals(DeliveryActivation.Active, delivery.activation.value)
    }
}
