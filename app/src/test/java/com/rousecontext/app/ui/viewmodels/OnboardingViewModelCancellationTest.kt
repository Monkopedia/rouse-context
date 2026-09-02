package com.rousecontext.app.ui.viewmodels

import com.rousecontext.app.auth.DeviceCredentialProvider
import com.rousecontext.app.auth.FcmTokenProvider
import com.rousecontext.app.delivery.NoOpBackgroundDelivery
import com.rousecontext.app.state.DeviceRegistrationStatus
import com.rousecontext.app.testing.MainDispatcherRule
import com.rousecontext.tunnel.CertificateStore
import com.rousecontext.tunnel.DeviceCredential
import com.rousecontext.tunnel.OnboardingFlow
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Pins both directions of the two broad catches in
 * [OnboardingViewModel.performRegistration] (issue #667).
 *
 * The guards there are defence in depth, not live-bug fixes — see the
 * [OnboardingViewModel] KDoc for the measured reachability argument (nothing in
 * production cancels `appScope`, and `Task.await()` cannot manufacture a
 * `CancellationException` on a live job because neither firebase-auth nor
 * firebase-messaging ever produces a cancelled `Task`). This suite exists so
 * that argument does not have to be re-derived: it fails if either guard is
 * removed, reordered, or widened.
 *
 * **The cancellation cases and the [IllegalStateException] cases are a
 * discriminating PAIR.** `CancellationException` extends `IllegalStateException`
 * on the JVM, so a guard mis-written as `catch (e: IllegalStateException)
 * { throw e }` passes every cancellation case here while wrongly converting a
 * genuine auth failure — the case that must keep landing on the retryable
 * [OnboardingState.Failed] screen — into a silent unwind. The ISE cases are the
 * half that catches that widening; do not weaken them to `RuntimeException`.
 * Clause ordering matters too: a cancellation clause placed below the broad
 * catch is dead code that Kotlin does not diagnose, and only the cancellation
 * cases below go red on it.
 *
 * Propagation is observed through [OnboardingViewModel.state] rather than a
 * thrown exception, because `performRegistration` runs inside `appScope.launch`
 * and a `CancellationException` escaping a launched coroutine is absorbed
 * silently by its `Job`. A swallow publishes [OnboardingState.Failed]; a rethrow
 * leaves the in-flight [OnboardingState.InProgress] in place.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class OnboardingViewModelCancellationTest {

    private val testDispatcher = StandardTestDispatcher()

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(testDispatcher)

    private val certStore = mockk<CertificateStore> {
        coEvery { getSubdomain() } returns null
        coEvery { isOnboardingComplete() } returns false
    }
    private val onboardingFlow = mockk<OnboardingFlow>()

    // --- credentialProvider.forRegistration() (the auth catch) ---

    @Test
    fun `auth cancellation propagates instead of publishing a Failed screen`() = runBlocking {
        val appScope = CoroutineScope(SupervisorJob() + testDispatcher)
        val vm = createViewModel(
            appScope = appScope,
            authFailure = { CancellationException("appScope cancelled") }
        )

        testDispatcher.scheduler.advanceUntilIdle()
        vm.startOnboarding()
        testDispatcher.scheduler.advanceUntilIdle()

        assertNotFailed("auth", vm)
        appScope.cancel()
    }

    @Test
    fun `auth IllegalStateException is still swallowed into the Failed screen`() = runBlocking {
        val appScope = CoroutineScope(SupervisorJob() + testDispatcher)
        val vm = createViewModel(
            appScope = appScope,
            authFailure = { IllegalStateException("sign-in unavailable") }
        )

        testDispatcher.scheduler.advanceUntilIdle()
        vm.startOnboarding()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(
            "an ordinary IllegalStateException from the credential fetch must still land " +
                "on the retryable Failed screen -- the cancellation guard has been widened " +
                "to a supertype and is now unwinding real auth failures into a stuck spinner",
            OnboardingState.Failed("Authentication error: sign-in unavailable"),
            vm.state.value
        )
        appScope.cancel()
    }

    // --- fcmTokenProvider.currentToken() (the FCM catch) ---

    @Test
    fun `fcm cancellation propagates instead of publishing a Failed screen`() = runBlocking {
        val appScope = CoroutineScope(SupervisorJob() + testDispatcher)
        val vm = createViewModel(
            appScope = appScope,
            fcmFailure = { CancellationException("appScope cancelled") }
        )

        testDispatcher.scheduler.advanceUntilIdle()
        vm.startOnboarding()
        testDispatcher.scheduler.advanceUntilIdle()

        assertNotFailed("fcm", vm)
        appScope.cancel()
    }

    @Test
    fun `fcm IllegalStateException is still swallowed into the Failed screen`() = runBlocking {
        val appScope = CoroutineScope(SupervisorJob() + testDispatcher)
        val vm = createViewModel(
            appScope = appScope,
            fcmFailure = { IllegalStateException("fcm unavailable") }
        )

        testDispatcher.scheduler.advanceUntilIdle()
        vm.startOnboarding()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(
            "an ordinary IllegalStateException from the FCM token fetch must still land " +
                "on the retryable Failed screen -- the cancellation guard has been widened " +
                "to a supertype and is now unwinding real FCM failures into a stuck spinner",
            OnboardingState.Failed("Couldn't reach Firebase Cloud Messaging: fcm unavailable"),
            vm.state.value
        )
        appScope.cancel()
    }

    private fun assertNotFailed(which: String, vm: OnboardingViewModel) {
        assertTrue(
            "cancellation from the $which fetch must propagate out of performRegistration, " +
                "but OnboardingViewModel published ${vm.state.value} -- the broad catch " +
                "swallowed it and turned a torn-down flow into a user-facing error. Make " +
                "sure `catch (e: CancellationException) { throw e }` is present AND sits " +
                "ABOVE the broad catch (a clause below it is dead code Kotlin will not " +
                "warn about).",
            vm.state.value is OnboardingState.InProgress
        )
    }

    private fun createViewModel(
        appScope: CoroutineScope,
        authFailure: (() -> Throwable)? = null,
        fcmFailure: (() -> Throwable)? = null
    ): OnboardingViewModel {
        val credentialProvider = object : DeviceCredentialProvider {
            override suspend fun forRegistration(): DeviceCredential? {
                authFailure?.let { throw it() }
                return DeviceCredential.Firebase("token")
            }

            override suspend fun forProvisioning(): DeviceCredential? =
                DeviceCredential.Firebase("token")
        }
        val fcmTokenProvider = object : FcmTokenProvider {
            override suspend fun currentToken(): String {
                fcmFailure?.let { throw it() }
                return "fcm-token"
            }
        }
        return OnboardingViewModel(
            certificateStore = certStore,
            onboardingFlow = onboardingFlow,
            registrationStatus = DeviceRegistrationStatus(initiallyRegistered = false),
            credentialProvider = credentialProvider,
            fcmTokenProvider = fcmTokenProvider,
            backgroundDelivery = NoOpBackgroundDelivery,
            appScope = appScope
        )
    }
}
