package com.rousecontext.app.ui.viewmodels

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
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
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
