package com.rousecontext.app.ui.viewmodels

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.rousecontext.api.CrashReporter
import com.rousecontext.api.IntegrationStateStore
import com.rousecontext.app.cert.FileCertificateStore
import com.rousecontext.app.cert.LazyWebSocketFactory
import com.rousecontext.app.state.DeviceRegistrationStatus
import com.rousecontext.app.testing.MainDispatcherRule
import com.rousecontext.tunnel.CertProvisioningFlow
import com.rousecontext.tunnel.CertProvisioningResult
import com.rousecontext.tunnel.CertificateStore
import com.rousecontext.tunnel.DeviceCredential
import com.rousecontext.tunnel.RelayApiClient
import com.rousecontext.tunnel.RelayApiResult
import com.rousecontext.tunnel.UpdateSecretsResponse
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
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
 * Couples `IntegrationSetupViewModel`'s secrets-persist catch — the `try` around
 * `certStore.storeIntegrationSecrets(newSecrets)` inside `pushIntegrationSecrets`
 * — to the invariant that makes it safe (issue #667).
 *
 * That catch is a bare `catch (e: Exception)` with **no**
 * `catch (e: CancellationException) { throw e }` above it, deliberately. It is
 * safe for a *structural* reason, not a caller-dependent one: the guarded call's
 * sole production implementation is [FileCertificateStore] (bound in
 * `AppModule`), which delegates to a **non-suspend** `atomicWrite`, and
 * `withContext` occurs zero times in all of `app/src/main`. A `suspend` function
 * whose `try` contains no suspension point never takes delivery of cooperative
 * cancellation, so the catch cannot see a `CancellationException` today.
 *
 * #665's `OnboardingHalfWriteWindowTest` already fails if `atomicWrite` becomes
 * suspending — but its diagnostic names only `OnboardingFlow`, and its
 * prescribed fix (wrap that pair in `NonCancellable`) turns it green again
 * *without* the author ever learning this second site exists. This test is the
 * missing half: make `atomicWrite` a `suspend fun ... = withContext(...)` and
 * both files go red, this one naming the ViewModel site.
 *
 * Why the site is worth the trouble: if cancellation ever does reach it, the
 * catch calls `CrashReporter.logCaughtException`, which `AcraCrashReporter`
 * dispatches on an application-lifetime scope (#542). The bogus crash record
 * therefore **outlives** the cancelled ViewModel — durable, and shown.
 *
 * The second test is the discriminating other half. `CancellationException`
 * extends `IllegalStateException` on the JVM, so if a future reader "fixes" the
 * site with a mis-widened `catch (e: IllegalStateException) { throw e }`, the
 * first test still passes while genuine storage failures stop reaching the
 * retryable Failed screen. Only the ISE case catches that; do not weaken it to
 * `RuntimeException`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class IntegrationSecretsPersistCancellationTest {

    private val testDispatcher = StandardTestDispatcher()

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(testDispatcher)

    @Test
    fun `an already-cancelled coroutine lands the secrets write without entering the catch`() =
        runTest(testDispatcher) {
            val context = ApplicationProvider.getApplicationContext<Application>()
            val real = FileCertificateStore(context)
            real.storeSubdomain("device")
            val secrets = mapOf("health" to "brisk-health")
            val crashReporter = RecordingCrashReporter()

            val vm = createViewModel(
                certStore = CancelArmingStore(real),
                secrets = secrets,
                crashReporter = crashReporter
            )

            vm.startSetup("health")
            advanceUntilIdle()

            assertTrue(
                DIAGNOSTIC + "\n\nSymptom: the crash reporter recorded " +
                    "${crashReporter.recorded.size} exception(s) " +
                    "(${crashReporter.recorded.map { it::class.simpleName }}) where it must " +
                    "record none.",
                crashReporter.recorded.isEmpty()
            )
            assertEquals(
                DIAGNOSTIC + "\n\nSymptom: the ViewModel published a Failed state instead of " +
                    "Complete.",
                IntegrationSetupState.Complete,
                vm.state.value
            )
            assertEquals(
                "the secrets write itself must have landed",
                secrets,
                real.getIntegrationSecrets()
            )
        }

    @Test
    fun `an ordinary IllegalStateException from the secrets write is still swallowed`() =
        runTest(testDispatcher) {
            val crashReporter = RecordingCrashReporter()
            val certStore = mockk<CertificateStore> {
                coEvery { getSubdomain() } returns "device"
                coEvery { storeIntegrationSecrets(any()) } throws
                    IllegalStateException("disk full")
            }

            val vm = createViewModel(
                certStore = certStore,
                secrets = mapOf("health" to "brisk-health"),
                crashReporter = crashReporter
            )

            vm.startSetup("health")
            advanceUntilIdle()

            assertEquals(
                "an ordinary IllegalStateException from the secrets write must still be " +
                    "swallowed into the retryable Failed screen. If this went red, the site " +
                    "grew a guard widened to a supertype -- CancellationException extends " +
                    "IllegalStateException, so `catch (e: IllegalStateException) { throw e }` " +
                    "rethrows real storage failures too. Narrow it to CancellationException.",
                IntegrationSetupState.Failed(
                    IntegrationSetupViewModel.SECRETS_PUSH_FAILED_MESSAGE
                ),
                vm.state.value
            )
            assertEquals(
                "the swallowed storage failure must still be recorded",
                1,
                crashReporter.recorded.size
            )
        }

    private fun createViewModel(
        certStore: CertificateStore,
        secrets: Map<String, String>,
        crashReporter: CrashReporter
    ): IntegrationSetupViewModel {
        val stateStore = mockk<IntegrationStateStore>(relaxed = true) {
            coEvery { setUserEnabled(any(), any()) } just Runs
        }
        val certProvisioningFlow = mockk<CertProvisioningFlow> {
            coEvery { execute(any<DeviceCredential>()) } returns
                CertProvisioningResult.AlreadyProvisioned
        }
        val relayApiClient = mockk<RelayApiClient> {
            coEvery { updateSecrets(any(), any()) } returns
                RelayApiResult.Success(UpdateSecretsResponse(success = true, secrets = secrets))
        }
        return IntegrationSetupViewModel(
            stateStore = stateStore,
            certProvisioningFlow = certProvisioningFlow,
            lazyWebSocketFactory = mockk<LazyWebSocketFactory>(relaxed = true),
            registrationStatus = DeviceRegistrationStatus(initiallyRegistered = true),
            relayApiClient = relayApiClient,
            certStore = certStore,
            integrationIds = secrets.keys.toList(),
            credentialProvider = { DeviceCredential.Firebase("token") },
            crashReporter = crashReporter
        )
    }

    private companion object {
        val DIAGNOSTIC: String = """
            FileCertificateStore.storeIntegrationSecrets gained a suspension point, which
            ARMS a previously-dormant cancellation swallow in
            IntegrationSetupViewModel.pushIntegrationSecrets -- the bare `catch (e: Exception)`
            around `certStore.storeIntegrationSecrets(newSecrets)`.

            That catch has no `catch (e: CancellationException) { throw e }` above it, on the
            grounds that a suspend function whose try has no suspension point never takes
            delivery of cancellation. Adding a `withContext` (or any other suspension point)
            inside `atomicWrite` retires that reasoning: a user navigating away mid-setup now
            cancels viewModelScope INSIDE the try, the catch converts the cancellation into a
            "Couldn't register integration with relay" screen, and calls
            CrashReporter.logCaughtException -- which AcraCrashReporter dispatches on an
            application-lifetime scope (#542), so the bogus crash record OUTLIVES the
            cancelled ViewModel.

            If you are here because #665's OnboardingHalfWriteWindowTest also went red: do NOT
            just wrap the OnboardingFlow pair in NonCancellable and move on. That fixes the
            other site only. Fix THIS one too, by adding
            `catch (e: CancellationException) { throw e }` ABOVE the broad catch in
            IntegrationSetupViewModel.pushIntegrationSecrets (a clause placed below it is dead
            code Kotlin will not warn about), and keep the IllegalStateException case in this
            file green so the guard is not widened past cancellation. See issue #667.
        """.trimIndent()
    }
}

/**
 * Wraps the real [FileCertificateStore] and cancels the calling coroutine
 * immediately before delegating the secrets write, so the guarded call runs with
 * an already-cancelled `Job`. With a non-suspend `atomicWrite` nothing takes
 * delivery and the write completes; the moment it suspends, the write throws
 * into the catch this file pins.
 */
private class CancelArmingStore(private val delegate: CertificateStore) :
    CertificateStore by delegate {
    override suspend fun storeIntegrationSecrets(secrets: Map<String, String>) {
        currentCoroutineContext().cancel(CancellationException("user navigated away"))
        delegate.storeIntegrationSecrets(secrets)
    }
}

private class RecordingCrashReporter : CrashReporter {
    val recorded = mutableListOf<Throwable>()
    override fun logCaughtException(throwable: Throwable) {
        recorded += throwable
    }

    override fun log(message: String) = Unit
    override fun setCollectionEnabled(enabled: Boolean) = Unit
}
