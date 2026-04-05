package com.rousecontext.tunnel

import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CertRenewalTest {

    private val mockServer = MockRelayServer()
    private val store = InMemoryCertificateStore()

    private val validInspector = object : CertInspector() {
        override fun inspect(pemCertificate: String): CertInfo =
            if (pemCertificate == MockRelayServer.MOCK_MISMATCHED_CERT_PEM) {
                CertInfo(commonName = "other-cn", isExpired = false)
            } else {
                CertInfo(commonName = "test123.rousecontext.com", isExpired = false)
            }
    }

    private val expiredInspector = object : CertInspector() {
        override fun inspect(pemCertificate: String): CertInfo =
            CertInfo(commonName = "test123.rousecontext.com", isExpired = true)
    }

    @BeforeTest
    fun setUp() {
        mockServer.start()
    }

    @AfterTest
    fun tearDown() {
        mockServer.stop()
    }

    private fun createFlow(inspector: CertInspector = validInspector): CertRenewalFlow {
        val client = RelayApiClient(baseUrl = mockServer.baseUrl)
        return CertRenewalFlow(
            csrGenerator = CsrGenerator(),
            relayApiClient = client,
            certificateStore = store,
            certInspector = inspector,
            maxRetries = 2,
            baseRetryDelayMs = 10L,
        )
    }

    @Test
    fun `mTLS renewal succeeds with valid cert`(): Unit = runBlocking {
        store.storeCertificate(MockRelayServer.MOCK_CERT_PEM)
        store.storeSubdomain("test123.rousecontext.com")
        store.storePrivateKey("fake-key")
        val flow = createFlow()

        mockServer.renewHandler = { request ->
            assertEquals("mtls", request.authMethod)
            MockRenewResponse(
                status = 200,
                body = RenewResponse(certificatePem = MockRelayServer.MOCK_CERT_PEM),
            )
        }

        val result = flow.renewWithMtls()
        assertTrue(result is RenewalResult.Success)
    }

    @Test
    fun `expired cert returns CertExpired for mTLS path`(): Unit = runBlocking {
        store.storeCertificate(MockRelayServer.MOCK_CERT_PEM)
        store.storeSubdomain("test123.rousecontext.com")
        val flow = createFlow(inspector = expiredInspector)

        val result = flow.renewWithMtls()
        assertTrue(result is RenewalResult.CertExpired)
    }

    @Test
    fun `Firebase renewal succeeds with expired cert`(): Unit = runBlocking {
        store.storeSubdomain("test123.rousecontext.com")
        store.storeCertificate(MockRelayServer.MOCK_CERT_PEM)
        val flow = createFlow(inspector = expiredInspector)

        mockServer.renewHandler = { request ->
            assertEquals("firebase", request.authMethod)
            assertEquals("fake-token", request.firebaseToken)
            assertEquals("fake-sig", request.signature)
            MockRenewResponse(
                status = 200,
                body = RenewResponse(certificatePem = MockRelayServer.MOCK_CERT_PEM),
            )
        }

        val result = flow.renewWithFirebase(
            firebaseToken = "fake-token",
            signature = "fake-sig",
        )
        assertTrue(result is RenewalResult.Success)
    }

    @Test
    fun `CN mismatch rejected`(): Unit = runBlocking {
        store.storeCertificate(MockRelayServer.MOCK_CERT_PEM)
        store.storeSubdomain("test123.rousecontext.com")
        val flow = createFlow()

        mockServer.renewHandler = { _ ->
            MockRenewResponse(
                status = 200,
                body = RenewResponse(certificatePem = MockRelayServer.MOCK_MISMATCHED_CERT_PEM),
            )
        }

        val result = flow.renewWithMtls()
        assertTrue(result is RenewalResult.CnMismatch)
        assertEquals("test123.rousecontext.com", result.expected)
        assertEquals("other-cn", result.actual)
    }

    @Test
    fun `rate limited schedules retry`(): Unit = runBlocking {
        store.storeCertificate(MockRelayServer.MOCK_CERT_PEM)
        store.storeSubdomain("test123.rousecontext.com")
        val flow = createFlow()

        mockServer.renewHandler = { _ ->
            MockRenewResponse(status = 429, retryAfter = 120)
        }

        val result = flow.renewWithMtls()
        assertTrue(result is RenewalResult.RateLimited)
        assertEquals(120L, result.retryAfterSeconds)
    }

    @Test
    fun `network failure retries with backoff`(): Unit = runBlocking {
        store.storeCertificate(MockRelayServer.MOCK_CERT_PEM)
        store.storeSubdomain("test123.rousecontext.com")

        val client = RelayApiClient(baseUrl = "http://127.0.0.1:1")
        val flow = CertRenewalFlow(
            csrGenerator = CsrGenerator(),
            relayApiClient = client,
            certificateStore = store,
            certInspector = validInspector,
            maxRetries = 2,
            baseRetryDelayMs = 10L,
        )

        val result = flow.renewWithMtls()
        assertTrue(result is RenewalResult.NetworkError)
    }

    @Test
    fun `no certificate returns NoCertificate`(): Unit = runBlocking {
        val flow = createFlow()

        val result = flow.renewWithMtls()
        assertTrue(result is RenewalResult.NoCertificate)
    }
}
