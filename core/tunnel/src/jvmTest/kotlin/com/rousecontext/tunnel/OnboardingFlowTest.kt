package com.rousecontext.tunnel

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class OnboardingFlowTest {

    private val mockServer = MockRelayServer()
    private val store = InMemoryCertificateStore()
    private lateinit var flow: OnboardingFlow

    @BeforeTest
    fun setUp() {
        mockServer.start()
        val client = RelayApiClient(baseUrl = mockServer.baseUrl)
        flow = OnboardingFlow(
            relayApiClient = client,
            certificateStore = store
        )
    }

    companion object {
        private const val FAKE_FIREBASE_TOKEN = "fake-firebase-id-token"
        private const val FAKE_FCM_TOKEN = "fake-fcm-registration-token"
    }

    @AfterTest
    fun tearDown() {
        mockServer.stop()
    }

    @Test
    fun `onboarding registers subdomain without requesting certs`(): Unit = runBlocking {
        mockServer.registerHandler = { _ ->
            MockRegisterResponse(
                status = 201,
                body = RegisterResponse(
                    subdomain = "abc123",
                    relayHost = "relay.rousecontext.com"
                )
            )
        }

        val result = flow.execute(FAKE_FIREBASE_TOKEN, FAKE_FCM_TOKEN)

        assertTrue(result is OnboardingResult.Success)
        assertEquals("abc123", result.subdomain)
        assertEquals("abc123", store.getSubdomain())
        // No certs should be stored during onboarding
        assertNull(store.getCertificate())
        assertNull(store.getClientCertificate())
        assertNull(store.getRelayCaCert())
        assertNull(store.getPrivateKey())
    }

    @Test
    fun `relay unreachable returns network error`(): Unit = runBlocking {
        mockServer.stop()

        val client = RelayApiClient(baseUrl = "http://127.0.0.1:1")
        val offlineFlow = OnboardingFlow(
            relayApiClient = client,
            certificateStore = store
        )

        val result = offlineFlow.execute(FAKE_FIREBASE_TOKEN, FAKE_FCM_TOKEN)

        assertTrue(result is OnboardingResult.NetworkError)
        assertNull(store.getSubdomain())
    }

    @Test
    fun `rate limited on register returns error with retry_after`(): Unit = runBlocking {
        mockServer.registerHandler = { _ ->
            MockRegisterResponse(status = 429, retryAfter = 60)
        }

        val result = flow.execute(FAKE_FIREBASE_TOKEN, FAKE_FCM_TOKEN)

        assertTrue(result is OnboardingResult.RateLimited)
        assertEquals(60L, result.retryAfterSeconds)
        assertNull(store.getSubdomain())
    }

    @Test
    fun `storage failure leaves no state`(): Unit = runBlocking {
        store.throwOnStore = RuntimeException("Disk full")

        val result = flow.execute(FAKE_FIREBASE_TOKEN, FAKE_FCM_TOKEN)

        assertTrue(result is OnboardingResult.StorageFailed)
        assertNull(store.getSubdomain())
    }

    @Test
    fun `onboarding stores integration secrets from register response`(): Unit = runBlocking {
        mockServer.registerHandler = { _ ->
            MockRegisterResponse(
                status = 201,
                body = RegisterResponse(
                    subdomain = "abc123",
                    relayHost = "relay.rousecontext.com",
                    integrationSecrets = mapOf(
                        "health" to "brave-health",
                        "notifications" to "swift-notifications"
                    )
                )
            )
        }

        val result = flow.execute(FAKE_FIREBASE_TOKEN, FAKE_FCM_TOKEN)

        assertTrue(result is OnboardingResult.Success)
        assertEquals("abc123", store.getSubdomain())
        assertEquals(
            mapOf("health" to "brave-health", "notifications" to "swift-notifications"),
            store.getIntegrationSecrets()
        )
        assertEquals("brave-health", store.getSecretForIntegration("health"))
    }

    @Test
    fun `onboarding without integration secrets leaves them null`(): Unit = runBlocking {
        mockServer.registerHandler = { _ ->
            MockRegisterResponse(
                status = 201,
                body = RegisterResponse(
                    subdomain = "abc123",
                    relayHost = "relay.rousecontext.com"
                )
            )
        }

        val result = flow.execute(FAKE_FIREBASE_TOKEN, FAKE_FCM_TOKEN)

        assertTrue(result is OnboardingResult.Success)
        assertEquals("abc123", store.getSubdomain())
        assertNull(store.getIntegrationSecrets())
    }

    @Test
    fun `relay returns server error on register`(): Unit = runBlocking {
        mockServer.registerHandler = { _ ->
            MockRegisterResponse(status = 500)
        }

        val result = flow.execute(FAKE_FIREBASE_TOKEN, FAKE_FCM_TOKEN)

        assertTrue(result is OnboardingResult.RelayError)
        assertEquals(500, result.statusCode)
    }
}
