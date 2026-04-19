package com.rousecontext.app.integration

import com.rousecontext.app.cert.LazyWebSocketFactory
import com.rousecontext.app.integration.harness.AppIntegrationTestHarness
import com.rousecontext.app.state.DeviceRegistrationStatus
import com.rousecontext.app.ui.viewmodels.IntegrationSetupState
import com.rousecontext.app.ui.viewmodels.IntegrationSetupViewModel
import com.rousecontext.tunnel.CertificateStore
import com.rousecontext.tunnel.RelayApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Happy-path onboarding scenario from issue #251.
 *
 * Drives the real Koin graph through the three stages a brand-new install
 * goes through on first launch + first integration enable:
 *
 *   1. [OnboardingFlow.execute]          — `POST /request-subdomain` + `POST /register`
 *   2. [CertProvisioningFlow.execute]    — `POST /register/certs`
 *   3. [IntegrationSetupViewModel.startSetup] — `POST /rotate-secret`
 *
 * The assertions nail down three invariants that together act as a
 * regression guard against the onboarding flow quietly changing how many
 * times it talks to the relay (issue #238 was the worst offender of that
 * before the mutex/in-flight guard went in):
 *
 *   - Each relay endpoint is hit exactly once — no retry storms, no
 *     missed calls.
 *   - The real [CertificateStore] ends up with both certs + relay CA +
 *     subdomain persisted.
 *   - The assigned subdomain survives the round trip.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class OnboardingHappyPathTest {

    private val harness = AppIntegrationTestHarness()

    @Before
    fun setUp() {
        // viewModelScope dispatches on Dispatchers.Main. Under runBlocking the
        // Android Main Looper isn't automatically pumped, so without a test
        // dispatcher the VM coroutine never actually runs and the test wedges
        // on `vm.state.first { Complete }`. Switch Main to Unconfined — a
        // runBlocking caller then runs VM coroutines inline.
        Dispatchers.setMain(Dispatchers.Unconfined)
        harness.start()
    }

    @After
    fun tearDown() {
        harness.stop()
        Dispatchers.resetMain()
    }

    @Test
    fun `full onboarding provisions cert and pushes integration secrets`() = runBlocking {
        // Step 1-2: onboard + provision certs, Step 3: drive secrets push via VM.
        val subdomain = harness.provisionDevice()
        driveIntegrationSetupToComplete(integrationId = "usage")

        assertRelayCallCountsAreOne()
        assertCertificateStoreFullyPersisted(subdomain)
    }

    /**
     * Spin up [IntegrationSetupViewModel] directly (bypassing Koin's VM
     * binding, which reads `integrationIds` from the empty override
     * `List<McpIntegration>`) and run it to [IntegrationSetupState.Complete].
     */
    private suspend fun driveIntegrationSetupToComplete(integrationId: String) {
        val registrationStatus: DeviceRegistrationStatus = harness.koin.get()
        // The Koin `DeviceRegistrationStatus` launches an async check of
        // `certStore.getSubdomain()` on construction; mark it manually so the
        // VM's `awaitComplete()` returns immediately regardless of ordering.
        registrationStatus.markComplete()
        val vm = IntegrationSetupViewModel(
            stateStore = harness.koin.get(),
            certProvisioningFlow = harness.koin.get(),
            lazyWebSocketFactory = harness.koin.get<LazyWebSocketFactory>(),
            registrationStatus = registrationStatus,
            relayApiClient = harness.koin.get<RelayApiClient>(),
            certStore = harness.koin.get<CertificateStore>(),
            integrationIds = listOf(integrationId),
            firebaseTokenProvider = { TEST_FIREBASE_TOKEN }
        )
        vm.startSetup(integrationId)
        withTimeout(SETUP_TIMEOUT_MS) {
            vm.state.first { it is IntegrationSetupState.Complete }
        }
    }

    private fun assertRelayCallCountsAreOne() {
        assertEquals(
            "relay should see exactly one /register call",
            1,
            harness.fixture.admin!!.registerCalls()
        )
        assertEquals(
            "relay should see exactly one /register/certs call",
            1,
            harness.fixture.registerCertsCalls()
        )
        assertEquals(
            "relay should see exactly one /rotate-secret call",
            1,
            harness.fixture.rotateSecretCalls()
        )
    }

    private suspend fun assertCertificateStoreFullyPersisted(expectedSubdomain: String) {
        val certStore: CertificateStore = harness.koin.get()
        assertNotNull("server certificate must be persisted", certStore.getCertificate())
        assertNotNull("client certificate must be persisted", certStore.getClientCertificate())
        assertNotNull("relay CA cert must be persisted", certStore.getRelayCaCert())
        assertEquals(
            "subdomain must round-trip through CertificateStore",
            expectedSubdomain,
            certStore.getSubdomain()
        )
    }

    companion object {
        // Integration tests hit a real relay subprocess over loopback TLS.
        // Provisioning completes in a few seconds; a 60s cap surfaces hangs
        // loudly rather than letting CI wedge on an infinite suspend.
        private const val SETUP_TIMEOUT_MS = 60_000L
    }
}
