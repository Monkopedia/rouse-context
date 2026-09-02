package com.rousecontext.app.ui.viewmodels

import android.app.Application
import com.rousecontext.api.IntegrationStateStore
import com.rousecontext.app.cert.LazyWebSocketFactory
import com.rousecontext.app.state.DeviceRegistrationStatus
import com.rousecontext.app.testing.MainDispatcherRule
import com.rousecontext.tunnel.CertProvisioningFlow
import com.rousecontext.tunnel.CertificateStore
import com.rousecontext.tunnel.DeviceCredential
import com.rousecontext.tunnel.RelayApiClient
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pins both directions of the credential-fetch catch in
 * [IntegrationSetupViewModel.beginProvisioningAsync] (issue #667).
 *
 * The guard there is defence in depth rather than a live-bug fix: this runs on
 * `viewModelScope`, so the only thing that delivers cancellation is
 * `onCleared()`, at which point the setup screen has left composition and the
 * `IntegrationSetupState.Failed` that `setFailed` publishes renders to nobody.
 * (The `google` credential binding bottoms out in `Task.await()`, which throws a
 * bare `CancellationException` only when `Task.isCanceled` — and neither
 * firebase-auth nor firebase-messaging references `CancellationTokenSource` or
 * `Tasks.forCanceled`, so they never produce a cancelled Task. The `foss`
 * binding catches internally and returns `null`.) The guard still earns its
 * place, because "the ViewModel is being torn down" is a claim about today's
 * callers and expires with nothing going red.
 *
 * **The cancellation case and the [IllegalStateException] case are a
 * discriminating PAIR.** `CancellationException` extends `IllegalStateException`
 * on the JVM, so a guard mis-written as `catch (e: IllegalStateException)
 * { throw e }` passes the cancellation case while wrongly unwinding genuine
 * credential failures that must keep landing on the retryable Failed screen
 * (#108). Do not weaken the ISE case to `RuntimeException`, and do not move the
 * cancellation clause below the broad catch — Kotlin does not diagnose the
 * resulting dead clause, and only the cancellation case goes red on it.
 *
 * The secrets-persist catch further down the same class is covered separately by
 * [IntegrationSecretsPersistCancellationTest]; it deliberately has no guard.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class IntegrationSetupViewModelCancellationTest {

    private val testDispatcher = StandardTestDispatcher()

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(testDispatcher)

    @Test
    fun `credential cancellation propagates instead of publishing a Failed screen`() =
        runTest(testDispatcher) {
            val certProvisioningFlow = mockk<CertProvisioningFlow>()
            val vm = createViewModel(
                certProvisioningFlow = certProvisioningFlow,
                credentialFailure = { CancellationException("viewModelScope cleared") }
            )

            vm.startSetup("health")
            advanceUntilIdle()

            assertTrue(
                "cancellation from the credential fetch must propagate out of " +
                    "beginProvisioningAsync, but IntegrationSetupViewModel published " +
                    "${vm.state.value} -- the broad catch swallowed it. Make sure " +
                    "`catch (e: CancellationException) { throw e }` is present AND sits " +
                    "ABOVE the broad catch (a clause below it is dead code Kotlin will not " +
                    "warn about).",
                vm.state.value is IntegrationSetupState.Provisioning
            )
            coVerify(exactly = 0) { certProvisioningFlow.execute(any<DeviceCredential>()) }
        }

    @Test
    fun `credential IllegalStateException is still swallowed into the Failed screen`() =
        runTest(testDispatcher) {
            val vm = createViewModel(
                certProvisioningFlow = mockk<CertProvisioningFlow>(),
                credentialFailure = { IllegalStateException("keystore locked") }
            )

            vm.startSetup("health")
            advanceUntilIdle()

            assertEquals(
                "an ordinary IllegalStateException from the credential fetch must still land " +
                    "on the retryable Failed screen -- the cancellation guard has been " +
                    "widened to a supertype and is now unwinding real credential failures " +
                    "into a stuck spinner",
                IntegrationSetupState.Failed("Authentication error: keystore locked"),
                vm.state.value
            )
        }

    private fun createViewModel(
        certProvisioningFlow: CertProvisioningFlow,
        credentialFailure: () -> Throwable
    ): IntegrationSetupViewModel {
        val stateStore = mockk<IntegrationStateStore>(relaxed = true) {
            coEvery { setUserEnabled(any(), any()) } just Runs
        }
        val certStore = mockk<CertificateStore> {
            coEvery { getSubdomain() } returns "device"
        }
        return IntegrationSetupViewModel(
            stateStore = stateStore,
            certProvisioningFlow = certProvisioningFlow,
            lazyWebSocketFactory = mockk<LazyWebSocketFactory>(relaxed = true),
            registrationStatus = DeviceRegistrationStatus(initiallyRegistered = true),
            relayApiClient = mockk<RelayApiClient>(),
            certStore = certStore,
            integrationIds = listOf("health"),
            credentialProvider = { throw credentialFailure() }
        )
    }
}
