package com.rousecontext.app.ui.viewmodels

import android.app.Application
import app.cash.turbine.ReceiveTurbine
import app.cash.turbine.test
import com.rousecontext.api.IntegrationStateStore
import com.rousecontext.app.cert.LazyWebSocketFactory
import com.rousecontext.app.state.DeviceRegistrationStatus
import com.rousecontext.app.ui.screens.SettingUpVariant
import com.rousecontext.tunnel.CertProvisioningFlow
import com.rousecontext.tunnel.CertProvisioningResult
import com.rousecontext.tunnel.CertificateStore
import com.rousecontext.tunnel.RelayApiClient
import com.rousecontext.tunnel.RelayApiResult
import com.rousecontext.tunnel.UpdateSecretsResponse
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
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
            coEvery { isUserEnabled("health") } returns true
            coEvery { isUserEnabled("usage") } returns true
            coEvery { isUserEnabled(any()) } returns false
        }
        val certProvisioningFlow = mockk<CertProvisioningFlow> {
            coEvery { execute(any()) } returns CertProvisioningResult.AlreadyProvisioned
        }
        val webSocketFactory = mockk<LazyWebSocketFactory>(relaxed = true)
        val registrationStatus = DeviceRegistrationStatus(initiallyRegistered = true)
        val relayApiClient = mockk<RelayApiClient> {
            coEvery { updateSecrets(any(), any()) } returns
                RelayApiResult.Success(
                    UpdateSecretsResponse(
                        success = true,
                        secrets = mapOf(
                            "health" to "brave-health",
                            "usage" to "brave-usage"
                        )
                    )
                )
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
    fun `updateSecrets forwards integrationIds unchanged and stores response secrets`() =
        runTest(testDispatcher) {
            val stateStore = mockk<IntegrationStateStore>(relaxed = true) {
                coEvery { isUserEnabled(any()) } returns false
                coEvery { isUserEnabled("health") } returns true
                coEvery { isUserEnabled("usage") } returns true
            }
            val certProvisioningFlow = mockk<CertProvisioningFlow> {
                coEvery { execute(any()) } returns CertProvisioningResult.AlreadyProvisioned
            }
            val webSocketFactory = mockk<LazyWebSocketFactory>(relaxed = true)
            val registrationStatus = DeviceRegistrationStatus(initiallyRegistered = true)
            val capturedIntegrationIds = slot<List<String>>()
            // Server generates a fresh secret for every configured integration and
            // returns the mapping; the client persists exactly what the server sent.
            val serverSecrets = mapOf(
                "health" to "brave-health",
                "usage" to "clever-usage"
            )
            val relayApiClient = mockk<RelayApiClient> {
                coEvery { updateSecrets(any(), capture(capturedIntegrationIds)) } returns
                    RelayApiResult.Success(
                        UpdateSecretsResponse(success = true, secrets = serverSecrets)
                    )
            }
            val storedSlot = slot<Map<String, String>>()
            val certStore = mockk<CertificateStore> {
                coEvery { getSubdomain() } returns "cool-penguin"
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

            assertTrue(
                "updateSecrets should have captured integration IDs",
                capturedIntegrationIds.isCaptured
            )
            assertEquals(
                "integrationIds forwarded to the relay must match the configured list exactly",
                listOf("health", "usage"),
                capturedIntegrationIds.captured
            )
            assertTrue("storeIntegrationSecrets should have been called", storedSlot.isCaptured)
            assertEquals(
                "Client must persist the exact secret map returned by the relay",
                serverSecrets,
                storedSlot.captured
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
    fun `retry after push failure re-pushes without restarting cert provisioning`() =
        runTest(testDispatcher) {
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
                    // First three attempts (initial push, 3x retries) all fail.
                    if (attempt <= 3) {
                        RelayApiResult.NetworkError(RuntimeException("boom"))
                    } else {
                        RelayApiResult.Success(
                            UpdateSecretsResponse(
                                success = true,
                                secrets = mapOf(
                                    "health" to "brave-health",
                                    "usage" to "clever-usage"
                                )
                            )
                        )
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

            // After initial failure, cert flow called once.
            assertTrue(vm.state.value is IntegrationSetupState.Failed)
            coVerify(exactly = 1) { certProvisioningFlow.execute(any()) }
            coVerify(exactly = 3) { relayApiClient.updateSecrets(any(), any()) }

            vm.retry()
            advanceUntilIdle()
            advanceTimeBy(10_000)
            runCurrent()
            advanceUntilIdle()

            assertEquals(IntegrationSetupState.Complete, vm.state.value)
            // Cert provisioning NOT called a second time — retry skipped it.
            coVerify(exactly = 1) { certProvisioningFlow.execute(any()) }
        }

    @Test
    fun `retry after push failure still failing leaves state Failed`() = runTest(testDispatcher) {
        val stateStore = mockk<IntegrationStateStore>(relaxed = true)
        val certProvisioningFlow = mockk<CertProvisioningFlow> {
            coEvery { execute(any()) } returns CertProvisioningResult.AlreadyProvisioned
        }
        val webSocketFactory = mockk<LazyWebSocketFactory>(relaxed = true)
        val registrationStatus = DeviceRegistrationStatus(initiallyRegistered = true)
        val relayApiClient = mockk<RelayApiClient> {
            coEvery { updateSecrets(any(), any()) } returns
                RelayApiResult.NetworkError(RuntimeException("still broken"))
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

        assertTrue(vm.state.value is IntegrationSetupState.Failed)

        vm.retry()
        advanceUntilIdle()
        advanceTimeBy(10_000)
        runCurrent()
        advanceUntilIdle()

        assertTrue(
            "Expected Failed after retry, got ${vm.state.value}",
            vm.state.value is IntegrationSetupState.Failed
        )
        // Initial push: 3 attempts. Retry push: 3 more attempts.
        coVerify(exactly = 6) { relayApiClient.updateSecrets(any(), any()) }
        // Cert flow still only called once — retry skipped it.
        coVerify(exactly = 1) { certProvisioningFlow.execute(any()) }
    }

    @Test
    fun `retry after cert provisioning failure re-runs full flow`() = runTest(testDispatcher) {
        val stateStore = mockk<IntegrationStateStore>(relaxed = true)
        var certAttempt = 0
        val certProvisioningFlow = mockk<CertProvisioningFlow> {
            coEvery { execute(any()) } answers {
                certAttempt++
                if (certAttempt == 1) {
                    CertProvisioningResult.NetworkError(RuntimeException("no net"))
                } else {
                    CertProvisioningResult.AlreadyProvisioned
                }
            }
        }
        val webSocketFactory = mockk<LazyWebSocketFactory>(relaxed = true)
        val registrationStatus = DeviceRegistrationStatus(initiallyRegistered = true)
        val relayApiClient = mockk<RelayApiClient> {
            coEvery { updateSecrets(any(), any()) } returns
                RelayApiResult.Success(
                    UpdateSecretsResponse(
                        success = true,
                        secrets = mapOf(
                            "health" to "brave-health",
                            "usage" to "clever-usage"
                        )
                    )
                )
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

        assertTrue(
            "Expected Failed after cert provisioning error, got ${vm.state.value}",
            vm.state.value is IntegrationSetupState.Failed
        )
        coVerify(exactly = 1) { certProvisioningFlow.execute(any()) }
        coVerify(exactly = 0) { relayApiClient.updateSecrets(any(), any()) }

        vm.retry()
        advanceUntilIdle()

        assertEquals(IntegrationSetupState.Complete, vm.state.value)
        // Retry ran the full flow — cert provisioning executed a second time.
        coVerify(exactly = 2) { certProvisioningFlow.execute(any()) }
        coVerify(exactly = 1) { relayApiClient.updateSecrets(any(), any()) }
    }

    @Test
    fun `retry is no-op when state is not Failed`() = runTest(testDispatcher) {
        val stateStore = mockk<IntegrationStateStore>(relaxed = true)
        val certProvisioningFlow = mockk<CertProvisioningFlow>(relaxed = true)
        val webSocketFactory = mockk<LazyWebSocketFactory>(relaxed = true)
        val registrationStatus = DeviceRegistrationStatus(initiallyRegistered = true)
        val relayApiClient = mockk<RelayApiClient>(relaxed = true)
        val certStore = mockk<CertificateStore>(relaxed = true)

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

        // State is Idle — retry should do nothing.
        vm.retry()
        advanceUntilIdle()

        assertEquals(IntegrationSetupState.Idle, vm.state.value)
        coVerify(exactly = 0) { certProvisioningFlow.execute(any()) }
        coVerify(exactly = 0) { relayApiClient.updateSecrets(any(), any()) }
    }

    @Test
    fun `duplicate startSetup while provisioning only invokes cert flow once`() =
        runTest(testDispatcher) {
            // Issue #238: IntegrationSetupDestination fires startSetup from a
            // LaunchedEffect that can re-run during recomposition/navigation,
            // leading to two viewModelScope coroutines each calling
            // CertProvisioningFlow.execute. The Provisioning-state guard at
            // the top of startSetup must short-circuit the second call so
            // execute() is never invoked twice.
            val stateStore = mockk<IntegrationStateStore>(relaxed = true)
            val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
            val certProvisioningFlow = mockk<CertProvisioningFlow> {
                coEvery { execute(any()) } coAnswers {
                    // Suspend so the second startSetup call arrives while the
                    // first is still in flight, reproducing the recomposition
                    // race.
                    gate.await()
                    CertProvisioningResult.AlreadyProvisioned
                }
            }
            val webSocketFactory = mockk<LazyWebSocketFactory>(relaxed = true)
            val registrationStatus = DeviceRegistrationStatus(initiallyRegistered = true)
            val relayApiClient = mockk<RelayApiClient> {
                coEvery { updateSecrets(any(), any()) } returns
                    RelayApiResult.Success(
                        UpdateSecretsResponse(
                            success = true,
                            secrets = mapOf("health" to "brave-health")
                        )
                    )
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
                integrationIds = listOf("health"),
                firebaseTokenProvider = { "test-firebase-token" }
            )

            vm.startSetup("health")
            // Let the first call reach certProvisioningFlow.execute and suspend.
            advanceUntilIdle()
            assertTrue(
                "First startSetup should have moved state to Provisioning",
                vm.state.value is IntegrationSetupState.Provisioning
            )

            // Second call lands while first is still mid-flight. Must be
            // rejected by the Provisioning-state guard.
            vm.startSetup("health")
            advanceUntilIdle()

            gate.complete(Unit)
            advanceUntilIdle()

            assertEquals(IntegrationSetupState.Complete, vm.state.value)
            coVerify(exactly = 1) { certProvisioningFlow.execute(any()) }
        }

    @Test
    fun `onboarded device with stale registration status skips Registering state`() =
        runTest(testDispatcher) {
            // Issue #242: DeviceRegistrationStatus starts `false` and flips
            // async. If the subdomain is already on disk the VM must short-
            // circuit rather than flashing "Registering" on a returning user.
            val registrationStatus = DeviceRegistrationStatus(initiallyRegistered = false)
            val vm = buildVm(
                registrationStatus = registrationStatus,
                subdomain = "cool-penguin"
            )

            vm.state.test {
                assertEquals(IntegrationSetupState.Idle, awaitItem())
                vm.startSetup("health")
                awaitNoRegisteringUntilComplete()
            }

            assertTrue(
                "awaitRegistrationIfNeeded must sync DeviceRegistrationStatus via markComplete",
                registrationStatus.complete.value
            )
        }

    @Test
    fun `not-onboarded device with false registration still shows Registering`() =
        runTest(testDispatcher) {
            // Guardrail for the #242 fix: when the cert store has no
            // subdomain AND DeviceRegistrationStatus is false, the VM must
            // still display Registering and wait for markComplete().
            var subdomainOnDisk: String? = null
            val registrationStatus = DeviceRegistrationStatus(initiallyRegistered = false)
            val vm = buildVm(
                registrationStatus = registrationStatus,
                subdomainProvider = { subdomainOnDisk }
            )

            vm.state.test {
                assertEquals(IntegrationSetupState.Idle, awaitItem())
                vm.startSetup("health")
                awaitRegistering()
                // Onboarding completes asynchronously: subdomain lands on disk
                // and DeviceRegistrationStatus flips, VM proceeds to Complete.
                subdomainOnDisk = "cool-penguin"
                registrationStatus.markComplete()
                advanceUntilIdle()
                while (vm.state.value != IntegrationSetupState.Complete) awaitItem()
                cancelAndIgnoreRemainingEvents()
            }
            assertEquals(IntegrationSetupState.Complete, vm.state.value)
        }

    /** Asserts the VM never emits Provisioning(Registering) on its way to Complete. */
    private suspend fun ReceiveTurbine<IntegrationSetupState>.awaitNoRegisteringUntilComplete() {
        while (true) {
            val next = awaitItem()
            if (next is IntegrationSetupState.Provisioning) {
                assertTrue(
                    "Must not pass through Registering for onboarded device; got $next",
                    next.settingUpState.variant != SettingUpVariant.Registering
                )
            }
            if (next == IntegrationSetupState.Complete) {
                cancelAndIgnoreRemainingEvents()
                return
            }
        }
    }

    /** Waits until the VM emits Provisioning(Registering). Fails on terminal state first. */
    private suspend fun ReceiveTurbine<IntegrationSetupState>.awaitRegistering() {
        while (true) {
            val next = awaitItem()
            if (next is IntegrationSetupState.Provisioning &&
                next.settingUpState.variant == SettingUpVariant.Registering
            ) {
                return
            }
            assertTrue(
                "Must pass through Registering when no subdomain + false registration; got $next",
                next !is IntegrationSetupState.Complete && next !is IntegrationSetupState.Failed
            )
        }
    }

    private fun buildVm(
        registrationStatus: DeviceRegistrationStatus,
        subdomain: String? = null,
        subdomainProvider: suspend () -> String? = { subdomain }
    ): IntegrationSetupViewModel {
        val certStore = mockk<CertificateStore> {
            coEvery { getSubdomain() } coAnswers { subdomainProvider() }
            coEvery { getIntegrationSecrets() } returns mapOf("health" to "brave-health")
            coEvery { storeIntegrationSecrets(any()) } just Runs
        }
        val relayApiClient = mockk<RelayApiClient> {
            coEvery { updateSecrets(any(), any()) } returns RelayApiResult.Success(
                UpdateSecretsResponse(
                    success = true,
                    secrets = mapOf("health" to "brave-health")
                )
            )
        }
        val certProvisioningFlow = mockk<CertProvisioningFlow> {
            coEvery { execute(any()) } returns CertProvisioningResult.AlreadyProvisioned
        }
        return IntegrationSetupViewModel(
            stateStore = mockk(relaxed = true),
            certProvisioningFlow = certProvisioningFlow,
            lazyWebSocketFactory = mockk(relaxed = true),
            registrationStatus = registrationStatus,
            relayApiClient = relayApiClient,
            certStore = certStore,
            integrationIds = listOf("health"),
            firebaseTokenProvider = { "test-firebase-token" }
        )
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
                    RelayApiResult.Success(
                        UpdateSecretsResponse(
                            success = true,
                            secrets = mapOf(
                                "health" to "brave-health",
                                "usage" to "clever-usage"
                            )
                        )
                    )
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
