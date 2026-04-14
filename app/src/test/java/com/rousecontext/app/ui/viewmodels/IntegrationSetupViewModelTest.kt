package com.rousecontext.app.ui.viewmodels

import android.app.Application
import app.cash.turbine.test
import com.rousecontext.api.IntegrationStateStore
import com.rousecontext.app.cert.LazyWebSocketFactory
import com.rousecontext.app.state.DeviceRegistrationStatus
import com.rousecontext.tunnel.CertProvisioningFlow
import com.rousecontext.tunnel.CertProvisioningResult
import com.rousecontext.tunnel.CertificateStore
import com.rousecontext.tunnel.RelayApiClient
import com.rousecontext.tunnel.RelayApiResult
import com.rousecontext.tunnel.UpdateSecretsResponse
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Verifies [IntegrationSetupViewModel] pushes integration secrets to the
 * relay after cert provisioning succeeds, and handles transient failures
 * with retries.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class IntegrationSetupViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `enable integration - updateSecrets succeeds - Complete state`() = runTest(testDispatcher) {
        val stateStore = mockk<IntegrationStateStore>(relaxed = true) {
            every { isUserEnabled("health") } returns true
            every { isUserEnabled("usage") } returns true
            every { isUserEnabled(any()) } returns false
        }
        val certProvisioningFlow = mockk<CertProvisioningFlow> {
            coEvery { execute(any()) } returns CertProvisioningResult.AlreadyProvisioned
        }
        val webSocketFactory = mockk<LazyWebSocketFactory>(relaxed = true)
        val registrationStatus = DeviceRegistrationStatus(initiallyRegistered = true)
        val relayApiClient = mockk<RelayApiClient> {
            coEvery { updateSecrets(any(), any()) } returns
                RelayApiResult.Success(UpdateSecretsResponse("ok"))
        }
        val certStore = mockk<CertificateStore> {
            coEvery { getSubdomain() } returns "cool-penguin"
            coEvery { getIntegrationSecrets() } returns mapOf("health" to "brave-health")
            coEvery { storeIntegrationSecrets(any()) } just Runs
        }

        val vm = IntegrationSetupViewModel(
            stateStore = stateStore,
            certProvisioningFlow = certProvisioningFlow,
            lazyWebSocketFactory = webSocketFactory,
            registrationStatus = registrationStatus,
            relayApiClient = relayApiClient,
            certStore = certStore,
            integrationIds = listOf("health", "usage"),
            firebaseTokenProvider = { "test-firebase-token" }
        )

        vm.state.test {
            assertEquals(IntegrationSetupState.Idle, awaitItem())
            vm.startSetup("usage")
            // Provisioning state (Requesting variant)
            val provisioning = awaitItem()
            assertTrue(provisioning is IntegrationSetupState.Provisioning)
            advanceUntilIdle()
            // Complete
            val complete = awaitItem()
            assertEquals(IntegrationSetupState.Complete, complete)
        }

        coVerify(exactly = 1) { relayApiClient.updateSecrets("cool-penguin", any()) }
    }

    @Test
    fun `updateSecrets payload includes existing and newly generated secrets`() =
        runTest(testDispatcher) {
            val stateStore = mockk<IntegrationStateStore>(relaxed = true) {
                every { isUserEnabled(any()) } returns false
                every { isUserEnabled("health") } returns true
                every { isUserEnabled("usage") } returns true
            }
            val certProvisioningFlow = mockk<CertProvisioningFlow> {
                coEvery { execute(any()) } returns CertProvisioningResult.AlreadyProvisioned
            }
            val webSocketFactory = mockk<LazyWebSocketFactory>(relaxed = true)
            val registrationStatus = DeviceRegistrationStatus(initiallyRegistered = true)
            val capturedSecrets = slot<List<String>>()
            val relayApiClient = mockk<RelayApiClient> {
                coEvery { updateSecrets(any(), capture(capturedSecrets)) } returns
                    RelayApiResult.Success(UpdateSecretsResponse("ok"))
            }
            val storedSlot = slot<Map<String, String>>()
            val certStore = mockk<CertificateStore> {
                coEvery { getSubdomain() } returns "cool-penguin"
                // Existing secret for health; usage is new.
                coEvery { getIntegrationSecrets() } returns mapOf("health" to "brave-health")
                coEvery { storeIntegrationSecrets(capture(storedSlot)) } just Runs
            }

            val vm = IntegrationSetupViewModel(
                stateStore = stateStore,
                certProvisioningFlow = certProvisioningFlow,
                lazyWebSocketFactory = webSocketFactory,
                registrationStatus = registrationStatus,
                relayApiClient = relayApiClient,
                certStore = certStore,
                integrationIds = listOf("health", "usage"),
                firebaseTokenProvider = { "test-firebase-token" }
            )

            vm.startSetup("usage")
            advanceUntilIdle()

            assertTrue("updateSecrets should have captured a list", capturedSecrets.isCaptured)
            val pushed = capturedSecrets.captured
            assertTrue(
                "Pushed secrets should include existing health secret, was $pushed",
                pushed.contains("brave-health")
            )
            assertTrue(
                "Pushed secrets should include a usage secret, was $pushed",
                pushed.any { it.endsWith("-usage") }
            )
            assertTrue(
                "Stored secret map should include usage, was ${storedSlot.captured}",
                storedSlot.captured.containsKey("usage")
            )
        }

    @Test
    fun `updateSecrets network error - retries 3 times then fails`() = runTest(testDispatcher) {
        val stateStore = mockk<IntegrationStateStore>(relaxed = true)
        val certProvisioningFlow = mockk<CertProvisioningFlow> {
            coEvery { execute(any()) } returns CertProvisioningResult.AlreadyProvisioned
        }
        val webSocketFactory = mockk<LazyWebSocketFactory>(relaxed = true)
        val registrationStatus = DeviceRegistrationStatus(initiallyRegistered = true)
        val relayApiClient = mockk<RelayApiClient> {
            coEvery { updateSecrets(any(), any()) } returns
                RelayApiResult.NetworkError(RuntimeException("boom"))
        }
        val certStore = mockk<CertificateStore> {
            coEvery { getSubdomain() } returns "cool-penguin"
            coEvery { getIntegrationSecrets() } returns mapOf("health" to "brave-health")
            coEvery { storeIntegrationSecrets(any()) } just Runs
        }

        val vm = IntegrationSetupViewModel(
            stateStore = stateStore,
            certProvisioningFlow = certProvisioningFlow,
            lazyWebSocketFactory = webSocketFactory,
            registrationStatus = registrationStatus,
            relayApiClient = relayApiClient,
            certStore = certStore,
            integrationIds = listOf("health", "usage"),
            firebaseTokenProvider = { "test-firebase-token" }
        )

        vm.startSetup("usage")
        // Drive past the 1s + 2s + 4s backoffs (plus some margin for scheduling)
        advanceUntilIdle()
        advanceTimeBy(10_000)
        runCurrent()
        advanceUntilIdle()

        val state = vm.state.value
        assertTrue(
            "Expected Failed state, got: $state",
            state is IntegrationSetupState.Failed
        )
        coVerify(exactly = 3) { relayApiClient.updateSecrets(any(), any()) }
    }

    @Test
    fun `updateSecrets relay error - retries then fails`() = runTest(testDispatcher) {
        val stateStore = mockk<IntegrationStateStore>(relaxed = true)
        val certProvisioningFlow = mockk<CertProvisioningFlow> {
            coEvery { execute(any()) } returns CertProvisioningResult.AlreadyProvisioned
        }
        val webSocketFactory = mockk<LazyWebSocketFactory>(relaxed = true)
        val registrationStatus = DeviceRegistrationStatus(initiallyRegistered = true)
        val relayApiClient = mockk<RelayApiClient> {
            coEvery { updateSecrets(any(), any()) } returns
                RelayApiResult.Error(500, "server exploded")
        }
        val certStore = mockk<CertificateStore> {
            coEvery { getSubdomain() } returns "cool-penguin"
            coEvery { getIntegrationSecrets() } returns mapOf("health" to "brave-health")
            coEvery { storeIntegrationSecrets(any()) } just Runs
        }

        val vm = IntegrationSetupViewModel(
            stateStore = stateStore,
            certProvisioningFlow = certProvisioningFlow,
            lazyWebSocketFactory = webSocketFactory,
            registrationStatus = registrationStatus,
            relayApiClient = relayApiClient,
            certStore = certStore,
            integrationIds = listOf("health", "usage"),
            firebaseTokenProvider = { "test-firebase-token" }
        )

        vm.startSetup("usage")
        advanceUntilIdle()
        advanceTimeBy(10_000)
        runCurrent()
        advanceUntilIdle()

        val state = vm.state.value
        assertTrue(
            "Expected Failed state, got: $state",
            state is IntegrationSetupState.Failed
        )
        coVerify(exactly = 3) { relayApiClient.updateSecrets(any(), any()) }
    }

    @Test
    fun `updateSecrets succeeds on second attempt`() = runTest(testDispatcher) {
        val stateStore = mockk<IntegrationStateStore>(relaxed = true)
        val certProvisioningFlow = mockk<CertProvisioningFlow> {
            coEvery { execute(any()) } returns CertProvisioningResult.AlreadyProvisioned
        }
        val webSocketFactory = mockk<LazyWebSocketFactory>(relaxed = true)
        val registrationStatus = DeviceRegistrationStatus(initiallyRegistered = true)
        var attempt = 0
        val relayApiClient = mockk<RelayApiClient> {
            coEvery { updateSecrets(any(), any()) } answers {
                attempt++
                if (attempt == 1) {
                    RelayApiResult.NetworkError(RuntimeException("transient"))
                } else {
                    RelayApiResult.Success(UpdateSecretsResponse("ok"))
                }
            }
        }
        val certStore = mockk<CertificateStore> {
            coEvery { getSubdomain() } returns "cool-penguin"
            coEvery { getIntegrationSecrets() } returns mapOf("health" to "brave-health")
            coEvery { storeIntegrationSecrets(any()) } just Runs
        }

        val vm = IntegrationSetupViewModel(
            stateStore = stateStore,
            certProvisioningFlow = certProvisioningFlow,
            lazyWebSocketFactory = webSocketFactory,
            registrationStatus = registrationStatus,
            relayApiClient = relayApiClient,
            certStore = certStore,
            integrationIds = listOf("health", "usage"),
            firebaseTokenProvider = { "test-firebase-token" }
        )

        vm.startSetup("usage")
        advanceUntilIdle()
        advanceTimeBy(10_000)
        runCurrent()
        advanceUntilIdle()

        assertEquals(IntegrationSetupState.Complete, vm.state.value)
        assertEquals(2, attempt)
    }
}
