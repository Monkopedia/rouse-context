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
        mockServer.requestSubdomainHandler = { _ ->
            MockRequestSubdomainResponse(
                status = 200,
                body = RequestSubdomainResponse(
                    subdomain = "abc123",
                    baseDomain = "rousecontext.com",
                    fqdn = "abc123.rousecontext.com",
                    reservationTtlSeconds = 600
                )
            )
        }
        mockServer.registerHandler = { _ ->
            MockRegisterResponse(
                status = 201,
                body = RegisterResponse(
                    subdomain = "abc123",
                    relayHost = "relay.rousecontext.com",
                    secrets = mapOf(
                        "health" to "brave-health",
                        "notifications" to "swift-notifications"
                    )
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
        // Issue #200: store.getPrivateKey() is always null (device key lives in
        // DeviceKeyManager). Kept as a sanity check that onboarding doesn't try to
        // write into the deprecated PEM slot.
        @Suppress("DEPRECATION")
        assertNull(store.getPrivateKey())
    }

    @Test
    fun `onboarding calls requestSubdomain before register`(): Unit = runBlocking {
        val callOrder = mutableListOf<String>()
        mockServer.requestSubdomainHandler = { _ ->
            callOrder += "request-subdomain"
            MockRequestSubdomainResponse(
                status = 200,
                body = RequestSubdomainResponse(
                    subdomain = "zephyr",
                    baseDomain = "rousecontext.com",
                    fqdn = "zephyr.rousecontext.com",
                    reservationTtlSeconds = 600
                )
            )
        }
        mockServer.registerHandler = { _ ->
            callOrder += "register"
            MockRegisterResponse(
                status = 201,
                body = RegisterResponse(
                    subdomain = "zephyr",
                    relayHost = "relay.rousecontext.com",
                    secrets = emptyMap()
                )
            )
        }

        val result = flow.execute(FAKE_FIREBASE_TOKEN, FAKE_FCM_TOKEN)

        assertTrue(result is OnboardingResult.Success)
        assertEquals(listOf("request-subdomain", "register"), callOrder)
        assertEquals("zephyr", store.getSubdomain())
    }

    @Test
    fun `onboarding rate limited on requestSubdomain returns rate limited result`(): Unit =
        runBlocking {
            mockServer.requestSubdomainHandler = { _ ->
                MockRequestSubdomainResponse(status = 429, retryAfter = 20)
            }
            mockServer.registerHandler = { _ ->
                error("register must not be called when requestSubdomain fails")
            }

            val result = flow.execute(FAKE_FIREBASE_TOKEN, FAKE_FCM_TOKEN)

            assertTrue(result is OnboardingResult.RateLimited)
            assertEquals(20L, result.retryAfterSeconds)
            assertNull(store.getSubdomain())
        }

    @Test
    fun `onboarding network error on requestSubdomain returns network error`(): Unit = runBlocking {
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
    fun `onboarding stores relay-provided integration secrets locally`(): Unit = runBlocking {
        val relaySecrets = mapOf(
            "health" to "brave-health",
            "notifications" to "swift-notifications"
        )
        mockServer.registerHandler = { _ ->
            MockRegisterResponse(
                status = 201,
                body = RegisterResponse(
                    subdomain = "abc123",
                    relayHost = "relay.rousecontext.com",
                    secrets = relaySecrets
                )
            )
        }

        val result = flow.execute(FAKE_FIREBASE_TOKEN, FAKE_FCM_TOKEN)

        assertTrue(result is OnboardingResult.Success)
        assertEquals("abc123", store.getSubdomain())
        val stored = store.getIntegrationSecrets()
        assertNotNull(stored)
        assertEquals(relaySecrets, stored)
    }

    @Test
    fun `onboarding sends integration ids to relay`(): Unit = runBlocking {
        var capturedRequest: RegisterRequest? = null
        mockServer.registerHandler = { request ->
            capturedRequest = request
            MockRegisterResponse(
                status = 201,
                body = RegisterResponse(
                    subdomain = "abc123",
                    relayHost = "relay.rousecontext.com",
                    secrets = emptyMap()
                )
            )
        }

        val result = flow.execute(FAKE_FIREBASE_TOKEN, FAKE_FCM_TOKEN)

        assertTrue(result is OnboardingResult.Success)
        assertNotNull(capturedRequest)
        assertEquals(
            listOf("health", "notifications"),
            capturedRequest!!.integrations
        )
    }

    @Test
    fun `onboarding with no integrations stores no secrets`(): Unit = runBlocking {
        mockServer.registerHandler = { _ ->
            MockRegisterResponse(
                status = 201,
                body = RegisterResponse(
                    subdomain = "abc123",
                    relayHost = "relay.rousecontext.com",
                    secrets = emptyMap()
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

    // --- #389: onboarding chains cert provisioning so fresh installs land
    //           with all three PEMs persisted, not just the subdomain.

    private fun flowWithCerts(): OnboardingFlow {
        val client = RelayApiClient(baseUrl = mockServer.baseUrl)
        val certFlow = CertProvisioningFlow(
            csrGenerator = CsrGenerator(),
            relayApiClient = client,
            certificateStore = store,
            deviceKeyManager = InMemoryDeviceKeyManager()
        )
        return OnboardingFlow(
            relayApiClient = client,
            certificateStore = store,
            integrationIds = listOf("health", "notifications"),
            certProvisioningFlow = certFlow
        )
    }

    @Test
    fun `onboarding with cert provisioning persists all three PEMs on success`(): Unit =
        runBlocking {
            val chained = flowWithCerts()

            val result = chained.execute(FAKE_FIREBASE_TOKEN, FAKE_FCM_TOKEN)

            assertTrue(
                result is OnboardingResult.Success,
                "expected Success, got: $result"
            )
            assertEquals("test123", (result as OnboardingResult.Success).subdomain)
            assertEquals("test123", store.getSubdomain())
            assertNotNull(store.getCertificate())
            assertNotNull(store.getClientCertificate())
            assertNotNull(store.getRelayCaCert())
        }

    @Test
    fun `onboarding surfaces CertRateLimited when cert issuance is rate limited`(): Unit =
        runBlocking {
            mockServer.certHandler = { _ ->
                MockCertResponse(status = 429, retryAfter = 120)
            }

            val chained = flowWithCerts()
            val result = chained.execute(FAKE_FIREBASE_TOKEN, FAKE_FCM_TOKEN)

            assertTrue(
                result is OnboardingResult.CertRateLimited,
                "expected CertRateLimited, got: $result"
            )
            val rateLimited = result as OnboardingResult.CertRateLimited
            assertEquals(120L, rateLimited.retryAfterSeconds)
            assertEquals("test123", rateLimited.subdomain)
            // Subdomain must survive a cert failure (#163) so the user can
            // retry cert issuance without re-registering.
            assertEquals("test123", store.getSubdomain())
            assertNull(store.getCertificate())
            assertNull(store.getClientCertificate())
        }

    @Test
    fun `onboarding surfaces CertRelayError on non-429 cert failure`(): Unit = runBlocking {
        mockServer.certHandler = { _ ->
            MockCertResponse(status = 503)
        }

        val chained = flowWithCerts()
        val result = chained.execute(FAKE_FIREBASE_TOKEN, FAKE_FCM_TOKEN)

        assertTrue(
            result is OnboardingResult.CertRelayError,
            "expected CertRelayError, got: $result"
        )
        assertEquals(503, (result as OnboardingResult.CertRelayError).statusCode)
        assertEquals("test123", store.getSubdomain())
    }

    @Test
    fun `onboarding surfaces CertStorageFailed when persisting certs throws`(): Unit = runBlocking {
        // Need to store subdomain successfully first, then fail on cert write.
        // InMemoryCertificateStore.throwOnStore throws on all writes, so we
        // trip it only after onboarding's subdomain write has landed: wrap
        // the store so the cert-only writes see the exception.
        val storeWithCertFailure = object : CertificateStore by store {
            override suspend fun storeCertificate(pemChain: String): Unit =
                throw java.io.IOException("disk full")
        }
        val client = RelayApiClient(baseUrl = mockServer.baseUrl)
        val certFlow = CertProvisioningFlow(
            csrGenerator = CsrGenerator(),
            relayApiClient = client,
            certificateStore = storeWithCertFailure,
            deviceKeyManager = InMemoryDeviceKeyManager()
        )
        val chained = OnboardingFlow(
            relayApiClient = client,
            certificateStore = storeWithCertFailure,
            integrationIds = emptyList(),
            certProvisioningFlow = certFlow
        )

        val result = chained.execute(FAKE_FIREBASE_TOKEN, FAKE_FCM_TOKEN)

        assertTrue(
            result is OnboardingResult.CertStorageFailed,
            "expected CertStorageFailed, got: $result"
        )
        // Subdomain must still be there — only cert state should be
        // rolled back (#163).
        assertEquals("test123", store.getSubdomain())
    }
}
