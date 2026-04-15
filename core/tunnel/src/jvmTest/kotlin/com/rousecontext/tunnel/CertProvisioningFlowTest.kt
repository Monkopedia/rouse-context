package com.rousecontext.tunnel

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class CertProvisioningFlowTest {

    private val mockServer = MockRelayServer()
    private val store = InMemoryCertificateStore()
    private lateinit var flow: CertProvisioningFlow

    companion object {
        private const val FAKE_FIREBASE_TOKEN = "fake-firebase-id-token"
    }

    @BeforeTest
    fun setUp() {
        mockServer.start()
        val client = RelayApiClient(baseUrl = mockServer.baseUrl)
        flow = CertProvisioningFlow(
            csrGenerator = CsrGenerator(),
            relayApiClient = client,
            certificateStore = store
        )
    }

    @AfterTest
    fun tearDown() {
        mockServer.stop()
    }

    @Test
    fun `returns NotOnboarded when no subdomain stored`(): Unit = runBlocking {
        val result = flow.execute(FAKE_FIREBASE_TOKEN)
        assertTrue(result is CertProvisioningResult.NotOnboarded)
    }

    @Test
    fun `provisions certs for onboarded device`(): Unit = runBlocking {
        store.storeSubdomain("abc123")

        mockServer.certHandler = { request ->
            assertTrue(request.csr.isNotBlank())
            MockCertResponse(
                status = 201,
                body = CertResponse(
                    subdomain = "abc123",
                    serverCert = MockRelayServer.MOCK_CERT_PEM,
                    clientCert = MockRelayServer.MOCK_CLIENT_CERT_PEM,
                    relayCaCert = MockRelayServer.MOCK_RELAY_CA_PEM,
                    relayHost = "relay.rousecontext.com"
                )
            )
        }

        val result = flow.execute(FAKE_FIREBASE_TOKEN)

        assertTrue(result is CertProvisioningResult.Success)
        assertNotNull(store.getCertificate())
        assertNotNull(store.getClientCertificate())
        assertNotNull(store.getRelayCaCert())
        assertNotNull(store.getPrivateKey())
    }

    @Test
    fun `returns AlreadyProvisioned when certs exist`(): Unit = runBlocking {
        store.storeSubdomain("abc123")
        store.storeCertificate(MockRelayServer.MOCK_CERT_PEM)
        store.storeClientCertificate(MockRelayServer.MOCK_CLIENT_CERT_PEM)

        val result = flow.execute(FAKE_FIREBASE_TOKEN)

        assertTrue(result is CertProvisioningResult.AlreadyProvisioned)
    }

    @Test
    fun `rate limited on cert request returns error`(): Unit = runBlocking {
        store.storeSubdomain("abc123")
        mockServer.certHandler = { _ ->
            MockCertResponse(status = 429, retryAfter = 30)
        }

        val result = flow.execute(FAKE_FIREBASE_TOKEN)

        assertTrue(result is CertProvisioningResult.RateLimited)
        assertTrue((result as CertProvisioningResult.RateLimited).retryAfterSeconds == 30L)
    }

    @Test
    fun `relay error on cert request returns error`(): Unit = runBlocking {
        store.storeSubdomain("abc123")
        mockServer.certHandler = { _ ->
            MockCertResponse(status = 500)
        }

        val result = flow.execute(FAKE_FIREBASE_TOKEN)

        assertTrue(result is CertProvisioningResult.RelayError)
    }

    @Test
    fun `storage failure clears partial state`(): Unit = runBlocking {
        store.storeSubdomain("abc123")
        store.throwOnStore = RuntimeException("Disk full")

        val result = flow.execute(FAKE_FIREBASE_TOKEN)

        assertTrue(result is CertProvisioningResult.StorageFailed)
        assertNull(store.getCertificate())
        assertNull(store.getPrivateKey())
    }

    @Test
    fun `storage failure preserves subdomain (onboarding state)`(): Unit = runBlocking {
        // Regression for issue #163: a cert-provisioning storage failure must
        // not roll back the onboarding-completed state. Otherwise the user is
        // sent back to the Welcome screen on next launch even though the device
        // already has an assigned subdomain.
        store.storeSubdomain("abc123")
        // The relay returns a cert response, but local storage fails.
        mockServer.certHandler = { _ ->
            MockCertResponse(
                status = 201,
                body = CertResponse(
                    subdomain = "abc123",
                    serverCert = MockRelayServer.MOCK_CERT_PEM,
                    clientCert = MockRelayServer.MOCK_CLIENT_CERT_PEM,
                    relayCaCert = MockRelayServer.MOCK_RELAY_CA_PEM,
                    relayHost = "relay.rousecontext.com"
                )
            )
        }
        store.throwOnStore = RuntimeException("Disk full")

        val result = flow.execute(FAKE_FIREBASE_TOKEN)

        assertTrue(result is CertProvisioningResult.StorageFailed)
        assertEquals(
            expected = "abc123",
            actual = store.getSubdomain(),
            message = "Subdomain (onboarding state) must survive cert provisioning rollback"
        )
    }

    @Test
    fun `network error returns NetworkError`(): Unit = runBlocking {
        store.storeSubdomain("abc123")
        mockServer.stop()

        val offlineClient = RelayApiClient(baseUrl = "http://127.0.0.1:1")
        val offlineFlow = CertProvisioningFlow(
            csrGenerator = CsrGenerator(),
            relayApiClient = offlineClient,
            certificateStore = store
        )

        val result = offlineFlow.execute(FAKE_FIREBASE_TOKEN)

        assertTrue(result is CertProvisioningResult.NetworkError)
    }
}
