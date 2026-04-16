package com.rousecontext.app.ui.viewmodels

import androidx.lifecycle.ViewModelStore
import com.rousecontext.app.auth.AnonymousAuthClient
import com.rousecontext.app.auth.FcmTokenProvider
import com.rousecontext.app.state.DeviceRegistrationStatus
import com.rousecontext.tunnel.CertificateStore
import com.rousecontext.tunnel.OnboardingFlow
import com.rousecontext.tunnel.OnboardingResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class OnboardingViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    private val certStore = mockk<CertificateStore>()
    private val onboardingFlow = mockk<OnboardingFlow>()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `startOnboarding passes auth and fcm tokens to onboarding flow`() = runBlocking {
        val authToken = "fake-firebase-id-token"
        val fcmToken = "fake-fcm-token"

        coEvery { certStore.getSubdomain() } returns null
        coEvery {
            onboardingFlow.execute(authToken, fcmToken)
        } returns OnboardingResult.Success("test-subdomain")

        val status = DeviceRegistrationStatus(initiallyRegistered = false)

        val vm = createViewModel(
            authToken = authToken,
            fcmToken = fcmToken,
            status = status
        )

        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(OnboardingState.NotOnboarded, vm.state.value)

        vm.startOnboarding()
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 1) { onboardingFlow.execute(authToken, fcmToken) }
        assertTrue(status.complete.value)

        coroutineContext.cancelChildren()
    }

    @Test
    fun `startOnboarding does not call flow when auth returns null`() = runBlocking {
        coEvery { certStore.getSubdomain() } returns null

        val status = DeviceRegistrationStatus(initiallyRegistered = false)

        val vm = createViewModel(
            authToken = null,
            fcmToken = "fcm-token",
            status = status
        )

        testDispatcher.scheduler.advanceUntilIdle()
        vm.startOnboarding()
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 0) { onboardingFlow.execute(any(), any()) }
        assertFalse(status.complete.value)

        coroutineContext.cancelChildren()
    }

    @Test
    fun `startOnboarding handles auth exception gracefully`() = runBlocking {
        coEvery { certStore.getSubdomain() } returns null

        val status = DeviceRegistrationStatus(initiallyRegistered = false)

        val vm = createViewModel(
            authException = RuntimeException("Network error"),
            fcmToken = "fcm-token",
            status = status
        )

        testDispatcher.scheduler.advanceUntilIdle()
        vm.startOnboarding()
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 0) { onboardingFlow.execute(any(), any()) }
        assertFalse(status.complete.value)

        coroutineContext.cancelChildren()
    }

    @Test
    fun `startOnboarding handles fcm exception gracefully`() = runBlocking {
        coEvery { certStore.getSubdomain() } returns null

        val status = DeviceRegistrationStatus(initiallyRegistered = false)

        val vm = createViewModel(
            authToken = "auth-token",
            fcmException = RuntimeException("FCM unavailable"),
            status = status
        )

        testDispatcher.scheduler.advanceUntilIdle()
        vm.startOnboarding()
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 0) { onboardingFlow.execute(any(), any()) }
        assertFalse(status.complete.value)

        coroutineContext.cancelChildren()
    }

    @Test
    fun `init marks onboarded when subdomain exists`() = runBlocking {
        coEvery { certStore.getSubdomain() } returns "existing-subdomain"

        val status = DeviceRegistrationStatus(initiallyRegistered = false)

        val vm = createViewModel(status = status)

        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(OnboardingState.Onboarded, vm.state.value)
        assertTrue(status.complete.value)

        coroutineContext.cancelChildren()
    }

    /**
     * Regression test for the bug fixed in f9234a06: startOnboarding() sets state to
     * Onboarded which triggers navigation away, destroying the ViewModel and cancelling
     * viewModelScope. If performRegistration() ran on viewModelScope it would be killed
     * mid-flight. The fix launches on appScope instead.
     *
     * This test proves registration completes even after the VM is cleared mid-flight.
     * If the fix regresses (work moves back to viewModelScope), the awaitComplete()
     * call will timeout because viewModelScope cancellation kills the registration coroutine.
     */
    @Test
    fun `registration completes on appScope even after VM is cleared`() = runBlocking {
        // Use real concurrency (not the StandardTestDispatcher) so delay() actually waits
        // and ViewModelStore.clear() races with the in-flight registration.
        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        val authClient = object : AnonymousAuthClient {
            override suspend fun signInAnonymouslyAndGetIdToken(): String? {
                delay(200) // simulate network latency
                return "fake-firebase-token"
            }
        }
        val fcmProvider = object : FcmTokenProvider {
            override suspend fun currentToken(): String = "fake-fcm-token"
        }

        coEvery { certStore.getSubdomain() } returns null
        coEvery {
            onboardingFlow.execute("fake-firebase-token", "fake-fcm-token")
        } returns OnboardingResult.Success("test-subdomain")

        val status = DeviceRegistrationStatus(initiallyRegistered = false)

        val vm = OnboardingViewModel(
            certificateStore = certStore,
            onboardingFlow = onboardingFlow,
            registrationStatus = status,
            authClient = authClient,
            fcmTokenProvider = fcmProvider,
            appScope = appScope
        )

        testDispatcher.scheduler.advanceUntilIdle()
        vm.startOnboarding()

        // Give the coroutine just enough time to launch but not finish auth
        delay(50)

        // Clear the ViewModel — this cancels viewModelScope but NOT appScope.
        // ViewModel.clear() is internal in Kotlin, so we use ViewModelStore.clear()
        // which is the same mechanism Android uses when navigation destroys the VM.
        val store = ViewModelStore()
        store.put("test-key", vm)
        store.clear()

        // Registration must still complete on appScope despite VM being cleared.
        // If this were using viewModelScope, the coroutine would have been cancelled
        // by clear() above and awaitComplete() would time out.
        withTimeout(5000) {
            status.awaitComplete()
        }

        assertTrue(status.complete.value)
        appScope.cancel()
    }

    /**
     * Helper to create the VM with configurable fake auth/FCM behavior.
     *
     * - [authToken]: token returned by the fake auth client (null = auth fails).
     * - [authException]: thrown by the fake auth client (overrides [authToken]).
     * - [fcmToken]: token returned by the fake FCM provider.
     * - [fcmException]: thrown by the fake FCM provider (overrides [fcmToken]).
     */
    private fun CoroutineScope.createViewModel(
        authToken: String? = "default-auth",
        authException: Exception? = null,
        fcmToken: String = "default-fcm",
        fcmException: Exception? = null,
        status: DeviceRegistrationStatus = DeviceRegistrationStatus()
    ): OnboardingViewModel {
        val authClient = object : AnonymousAuthClient {
            override suspend fun signInAnonymouslyAndGetIdToken(): String? {
                if (authException != null) throw authException
                return authToken
            }
        }
        val fcmProvider = object : FcmTokenProvider {
            override suspend fun currentToken(): String {
                if (fcmException != null) throw fcmException
                return fcmToken
            }
        }
        return OnboardingViewModel(
            certificateStore = certStore,
            onboardingFlow = onboardingFlow,
            registrationStatus = status,
            authClient = authClient,
            fcmTokenProvider = fcmProvider,
            appScope = this
        )
    }
}
