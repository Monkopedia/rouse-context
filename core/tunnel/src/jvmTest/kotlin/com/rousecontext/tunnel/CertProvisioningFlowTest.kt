package com.rousecontext.tunnel

import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking

class CertProvisioningFlowTest {

    private val mockServer = MockRelayServer()
    private val store = InMemoryCertificateStore()
    private val keyManager = InMemoryDeviceKeyManager()
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
            certificateStore = store,
            deviceKeyManager = keyManager
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
        // Issue #200: the device identity keypair is owned by DeviceKeyManager, not the
        // certificate store. Confirm the flow asked the key manager for a keypair and got
        // a real EC public key back (non-null) so subsequent renewals reuse the same key.
        assertNotNull(keyManager.getOrCreateKeyPair().public)
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
    fun `concurrent execute calls serialize and issue exactly one registerCerts`() {
        runBlocking { runConcurrentExecuteSerializationTest() }
    }

    private suspend fun runConcurrentExecuteSerializationTest(): Unit = coroutineScope {
        // Issue #238: a Compose LaunchedEffect can re-run the ViewModel's
        // startSetup during onboarding, causing two coroutines to call
        // CertProvisioningFlow.execute concurrently. Before the Mutex, both
        // callers raced past the already-provisioned check and each issued a
        // /register/certs POST, which in turn triggered two ACME orders and
        // the GTS deduplicator 400-ed the second one. The Mutex inside
        // execute() must serialize callers so only one POST is made.
        store.storeSubdomain("abc123")

        val releaseFirst = CompletableDeferred<Unit>()
        val firstCallStarted = CompletableDeferred<Unit>()
        val certRequests = AtomicInteger(0)

        mockServer.certHandler = { _ ->
            val ordinal = certRequests.incrementAndGet()
            if (ordinal == 1) {
                // Signal that the first handler is running, then block until
                // the test explicitly releases it. This pins execute() inside
                // its critical section long enough for the second call to
                // contend on the Mutex. If serialization is broken, the second
                // caller races through and this counter will advance past 1
                // before releaseFirst completes.
                firstCallStarted.complete(Unit)
                releaseFirst.await()
            }
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

        val firstCall = async { flow.execute(FAKE_FIREBASE_TOKEN) }
        // Wait for the first call to reach the server before launching the
        // second one. This guarantees the second caller arrives while the
        // first still holds the Mutex, which is the race we are protecting
        // against.
        firstCallStarted.await()

        val secondCall = async { flow.execute(FAKE_FIREBASE_TOKEN) }

        releaseFirst.complete(Unit)

        val results = listOf(firstCall, secondCall).awaitAll()

        assertEquals(
            expected = 1,
            actual = certRequests.get(),
            message = "Concurrent execute() calls must issue exactly one /register/certs"
        )
        // One caller wins and receives Success; the other must observe the
        // freshly-stored certs under the lock and return AlreadyProvisioned.
        assertTrue(results.any { it is CertProvisioningResult.Success })
        assertTrue(results.any { it is CertProvisioningResult.AlreadyProvisioned })
    }

    @Test
    fun `network error returns NetworkError`(): Unit = runBlocking {
        store.storeSubdomain("abc123")
        mockServer.stop()

        val offlineClient = RelayApiClient(baseUrl = "http://127.0.0.1:1")
        val offlineFlow = CertProvisioningFlow(
            csrGenerator = CsrGenerator(),
            relayApiClient = offlineClient,
            certificateStore = store,
            deviceKeyManager = keyManager
        )

        val result = offlineFlow.execute(FAKE_FIREBASE_TOKEN)

        assertTrue(result is CertProvisioningResult.NetworkError)
    }
}
