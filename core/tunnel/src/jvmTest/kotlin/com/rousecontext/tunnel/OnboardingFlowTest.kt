package com.rousecontext.tunnel

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
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
            certificateStore = store,
            integrationIds = listOf("health", "notifications")
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
    fun `onboarding generates and stores integration secrets locally`(): Unit = runBlocking {
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
        val secrets = store.getIntegrationSecrets()
        assertNotNull(secrets)
        assertEquals(2, secrets.size)
        assertTrue(secrets.containsKey("health"))
        assertTrue(secrets.containsKey("notifications"))
        // Secrets follow "{adjective}-{integration}" format
        assertTrue(secrets["health"]!!.endsWith("-health"))
        assertTrue(secrets["notifications"]!!.endsWith("-notifications"))
    }

    @Test
    fun `onboarding sends valid_secrets to relay`(): Unit = runBlocking {
        var capturedRequest: RegisterRequest? = null
        mockServer.registerHandler = { request ->
            capturedRequest = request
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
        assertNotNull(capturedRequest)
        assertEquals(2, capturedRequest!!.validSecrets.size)
        // The secrets sent to relay should match what's stored locally
        val storedSecrets = store.getIntegrationSecrets()!!
        assertTrue(capturedRequest!!.validSecrets.containsAll(storedSecrets.values))
    }

    @Test
    fun `onboarding with no integrations stores no secrets`(): Unit = runBlocking {
        mockServer.registerHandler = { _ ->
            MockRegisterResponse(
                status = 201,
                body = RegisterResponse(
                    subdomain = "abc123",
                    relayHost = "relay.rousecontext.com"
                )
            )
        }

        val client = RelayApiClient(baseUrl = mockServer.baseUrl)
        val noIntegrationsFlow = OnboardingFlow(
            relayApiClient = client,
            certificateStore = store,
            integrationIds = emptyList()
        )

        val result = noIntegrationsFlow.execute(FAKE_FIREBASE_TOKEN, FAKE_FCM_TOKEN)

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
