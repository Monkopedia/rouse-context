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
            csrGenerator = CsrGenerator(),
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
    fun `full onboarding flow succeeds`(): Unit = runBlocking {
        mockServer.registerHandler = { request ->
            assertTrue(request.csr.contains("BEGIN CERTIFICATE REQUEST"))
            MockResponse(
                status = 201,
                body = RegisterResponse(
                    subdomain = "abc123.rousecontext.com",
                    cert = MockRelayServer.MOCK_CERT_PEM,
                    relayHost = "relay.rousecontext.com"
                )
            )
        }

        val result = flow.execute("abc123.rousecontext.com", FAKE_FIREBASE_TOKEN, FAKE_FCM_TOKEN)

        assertTrue(result is OnboardingResult.Success)
        assertEquals("abc123.rousecontext.com", result.subdomain)
        assertNotNull(store.getCertificate())
        assertEquals("abc123.rousecontext.com", store.getSubdomain())
        assertNotNull(store.getPrivateKey())
    }

    @Test
    fun `relay unreachable returns network error`(): Unit = runBlocking {
        mockServer.stop()

        val client = RelayApiClient(baseUrl = "http://127.0.0.1:1")
        val offlineFlow = OnboardingFlow(
            csrGenerator = CsrGenerator(),
            relayApiClient = client,
            certificateStore = store
        )

        val result = offlineFlow.execute(
            "test.rousecontext.com",
            FAKE_FIREBASE_TOKEN,
            FAKE_FCM_TOKEN
        )

        assertTrue(result is OnboardingResult.NetworkError)
        assertNull(store.getCertificate())
        assertNull(store.getSubdomain())
        assertNull(store.getPrivateKey())
    }

    @Test
    fun `rate limited returns error with retry_after`(): Unit = runBlocking {
        mockServer.registerHandler = { _ ->
            MockResponse(status = 429, retryAfter = 60)
        }

        val result = flow.execute("test.rousecontext.com", FAKE_FIREBASE_TOKEN, FAKE_FCM_TOKEN)

        assertTrue(result is OnboardingResult.RateLimited)
        assertEquals(60L, result.retryAfterSeconds)
        assertNull(store.getCertificate())
    }

    @Test
    fun `partial failure leaves no state`(): Unit = runBlocking {
        store.throwOnStore = RuntimeException("Disk full")

        val result = flow.execute("test.rousecontext.com", FAKE_FIREBASE_TOKEN, FAKE_FCM_TOKEN)

        assertTrue(result is OnboardingResult.StorageFailed)
        assertNull(store.getCertificate())
        assertNull(store.getSubdomain())
        assertNull(store.getPrivateKey())
    }

    @Test
    fun `relay returns server error`(): Unit = runBlocking {
        mockServer.registerHandler = { _ ->
            MockResponse(status = 500)
        }

        val result = flow.execute("test.rousecontext.com", FAKE_FIREBASE_TOKEN, FAKE_FCM_TOKEN)

        assertTrue(result is OnboardingResult.RelayError)
        assertEquals(500, result.statusCode)
    }
}
