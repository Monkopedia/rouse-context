package com.rousecontext.tunnel

import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

class CertRenewalTest {

    private val mockServer = MockRelayServer()
    private val store = InMemoryCertificateStore()

    /** A real ECDSA P-256 private key PEM for the store, generated once per test class. */
    private val testKeyPem: String by lazy {
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256r1"))
        val kp = kpg.generateKeyPair()
        val der = kp.private.encoded
        val b64 = Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(der)
        "-----BEGIN PRIVATE KEY-----\n$b64\n-----END PRIVATE KEY-----\n"
    }

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
            baseRetryDelayMs = 10L
        )
    }

    @Test
    fun `mTLS renewal succeeds with valid cert and signature`(): Unit = runBlocking {
        store.storeCertificate(MockRelayServer.MOCK_CERT_PEM)
        store.storeSubdomain("test123")
        store.storePrivateKey(testKeyPem)
        val flow = createFlow()

        var receivedRequest: RenewRequest? = null
        mockServer.renewHandler = { request ->
            receivedRequest = request
            MockRenewResponse(
                status = 200,
                body = RenewResponse(
                    serverCert = MockRelayServer.MOCK_CERT_PEM,
                    clientCert = MockRelayServer.MOCK_CLIENT_CERT_PEM,
                    relayCaCert = MockRelayServer.MOCK_RELAY_CA_PEM
                )
            )
        }

        val result = flow.renewWithMtls(signature = "mtls-signature-b64")
        assertTrue(result is RenewalResult.Success, "expected Success, got $result")

        // Wire contract: request body carries csr, subdomain, signature, and
        // NO firebase_token on the mTLS path.
        val req = assertNotNull(receivedRequest)
        assertTrue(req.csr.isNotBlank(), "csr must be sent")
        assertEquals("test123", req.subdomain)
        assertEquals("mtls-signature-b64", req.signature)
        assertNull(req.firebaseToken, "mTLS path must not send a firebase token")

        // All three cert bundle fields must be stored from the response.
        assertEquals(MockRelayServer.MOCK_CERT_PEM, store.getCertificate())
        assertEquals(MockRelayServer.MOCK_CLIENT_CERT_PEM, store.getClientCertificate())
        assertEquals(MockRelayServer.MOCK_RELAY_CA_PEM, store.getRelayCaCert())
    }

    @Test
    fun `renewal CSR carries the stored registration public key`(): Unit = runBlocking {
        // Generate a known keypair and store it
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256r1"))
        val registrationKp = kpg.generateKeyPair()
        val privDer = registrationKp.private.encoded
        val privPem = "-----BEGIN PRIVATE KEY-----\n" +
            Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(privDer) +
            "\n-----END PRIVATE KEY-----\n"

        store.storeCertificate(MockRelayServer.MOCK_CERT_PEM)
        store.storeSubdomain("test123")
        store.storePrivateKey(privPem)
        val flow = createFlow()

        var capturedCsrPem: String? = null
        mockServer.renewHandler = { request ->
            capturedCsrPem = request.csr
            MockRenewResponse(
                status = 200,
                body = RenewResponse(
                    serverCert = MockRelayServer.MOCK_CERT_PEM,
                    clientCert = MockRelayServer.MOCK_CLIENT_CERT_PEM,
                    relayCaCert = MockRelayServer.MOCK_RELAY_CA_PEM
                )
            )
        }

        val result = flow.renewWithMtls(signature = "sig")
        assertTrue(result is RenewalResult.Success, "expected Success, got $result")

        // Parse the CSR sent to the relay and extract its public key
        val csrPem = assertNotNull(capturedCsrPem, "CSR must be sent to relay")
        val csrDer = Base64.getDecoder().decode(
            csrPem
                .substringAfter("-----BEGIN CERTIFICATE REQUEST-----")
                .substringBefore("-----END CERTIFICATE REQUEST-----")
                .replace("\\s".toRegex(), "")
        )
        val csrPublicKeyDer = extractSubjectPublicKeyInfo(csrDer)

        // The CSR public key must match the registration keypair's public key
        val expectedPublicKeyDer = registrationKp.public.encoded
        assertTrue(
            csrPublicKeyDer.contentEquals(expectedPublicKeyDer),
            "Renewal CSR must carry the stored registration public key, not a fresh one"
        )

        // Private key in store must be unchanged (not overwritten)
        assertEquals(privPem, store.getPrivateKey(), "Private key must not be overwritten")
    }

    @Test
    fun `expired cert returns CertExpired for mTLS path`(): Unit = runBlocking {
        store.storeCertificate(MockRelayServer.MOCK_CERT_PEM)
        store.storeSubdomain("test123")
        store.storePrivateKey(testKeyPem)
        val flow = createFlow(inspector = expiredInspector)

        val result = flow.renewWithMtls(signature = "unused")
        assertTrue(result is RenewalResult.CertExpired)
    }

    @Test
    fun `Firebase renewal succeeds with expired cert`(): Unit = runBlocking {
        store.storeSubdomain("test123")
        store.storeCertificate(MockRelayServer.MOCK_CERT_PEM)
        store.storePrivateKey(testKeyPem)
        val flow = createFlow(inspector = expiredInspector)

        var receivedRequest: RenewRequest? = null
        mockServer.renewHandler = { request ->
            receivedRequest = request
            MockRenewResponse(
                status = 200,
                body = RenewResponse(
                    serverCert = MockRelayServer.MOCK_CERT_PEM,
                    clientCert = MockRelayServer.MOCK_CLIENT_CERT_PEM,
                    relayCaCert = MockRelayServer.MOCK_RELAY_CA_PEM
                )
            )
        }

        val result = flow.renewWithFirebase(
            firebaseToken = "fake-token",
            signature = "fake-sig"
        )
        assertTrue(result is RenewalResult.Success, "expected Success, got $result")

        val req = assertNotNull(receivedRequest)
        assertEquals("test123", req.subdomain)
        assertEquals("fake-token", req.firebaseToken)
        assertEquals("fake-sig", req.signature)
        assertTrue(req.csr.isNotBlank())

        assertEquals(MockRelayServer.MOCK_CERT_PEM, store.getCertificate())
        assertEquals(MockRelayServer.MOCK_CLIENT_CERT_PEM, store.getClientCertificate())
        assertEquals(MockRelayServer.MOCK_RELAY_CA_PEM, store.getRelayCaCert())
    }

    @Test
    fun `CN mismatch rejected`(): Unit = runBlocking {
        store.storeCertificate(MockRelayServer.MOCK_CERT_PEM)
        store.storeSubdomain("test123")
        store.storePrivateKey(testKeyPem)
        val flow = createFlow()

        mockServer.renewHandler = { _ ->
            MockRenewResponse(
                status = 200,
                body = RenewResponse(
                    serverCert = MockRelayServer.MOCK_MISMATCHED_CERT_PEM,
                    clientCert = MockRelayServer.MOCK_CLIENT_CERT_PEM,
                    relayCaCert = MockRelayServer.MOCK_RELAY_CA_PEM
                )
            )
        }

        val result = flow.renewWithMtls(signature = "sig")
        assertTrue(result is RenewalResult.CnMismatch)
        assertEquals("test123.rousecontext.com", result.expected)
        assertEquals("other-cn", result.actual)
    }

    @Test
    fun `rate limited schedules retry`(): Unit = runBlocking {
        store.storeCertificate(MockRelayServer.MOCK_CERT_PEM)
        store.storeSubdomain("test123")
        store.storePrivateKey(testKeyPem)
        val flow = createFlow()

        mockServer.renewHandler = { _ ->
            MockRenewResponse(status = 429, retryAfter = 120)
        }

        val result = flow.renewWithMtls(signature = "sig")
        assertTrue(result is RenewalResult.RateLimited)
        assertEquals(120L, result.retryAfterSeconds)
    }

    @Test
    fun `network failure retries with backoff`(): Unit = runBlocking {
        store.storeCertificate(MockRelayServer.MOCK_CERT_PEM)
        store.storeSubdomain("test123")
        store.storePrivateKey(testKeyPem)

        val client = RelayApiClient(baseUrl = "http://127.0.0.1:1")
        val flow = CertRenewalFlow(
            csrGenerator = CsrGenerator(),
            relayApiClient = client,
            certificateStore = store,
            certInspector = validInspector,
            maxRetries = 2,
            baseRetryDelayMs = 10L
        )

        val result = flow.renewWithMtls(signature = "sig")
        assertTrue(result is RenewalResult.NetworkError)
    }

    @Test
    fun `no certificate returns NoCertificate`(): Unit = runBlocking {
        val flow = createFlow()

        val result = flow.renewWithMtls(signature = "sig")
        assertTrue(result is RenewalResult.NoCertificate)
    }

    /**
     * Wire-contract test: serializes a [RenewRequest] and asserts the JSON
     * field names match what the relay's `RenewRequest` struct deserializes.
     * This is the surgical guard against a future field rename drifting
     * silently as happened with issue #170.
     */
    @Test
    fun `RenewRequest serializes using relay-side snake_case field names`() {
        val req = RenewRequest(
            csr = "dummy-csr-b64",
            subdomain = "brave-falcon",
            firebaseToken = "token",
            signature = "sig"
        )
        val json = Json.encodeToString(RenewRequest.serializer(), req)
        val parsed = Json.parseToJsonElement(json) as JsonObject
        assertEquals("dummy-csr-b64", parsed["csr"]?.jsonPrimitive?.content)
        assertEquals("brave-falcon", parsed["subdomain"]?.jsonPrimitive?.content)
        assertEquals("token", parsed["firebase_token"]?.jsonPrimitive?.content)
        assertEquals("sig", parsed["signature"]?.jsonPrimitive?.content)
        // Client must NOT serialize camelCase fallbacks that the relay rejects.
        assertTrue(!parsed.containsKey("firebaseToken"))
        assertTrue(!parsed.containsKey("csrPem"))
        assertTrue(!parsed.containsKey("authMethod"))
        assertTrue(!parsed.containsKey("currentCertPem"))
    }

    /**
     * Wire-contract test: parses a response body shaped like the relay's
     * actual output (server_cert / client_cert / relay_ca_cert) and asserts
     * the client deserializes every field.
     */
    @Test
    fun `RenewResponse deserializes from relay-side snake_case body`() {
        val body = """
            {
              "server_cert": "SERVER-PEM",
              "client_cert": "CLIENT-PEM",
              "relay_ca_cert": "CA-PEM"
            }
        """.trimIndent()
        val resp = lenientJson.decodeFromString(RenewResponse.serializer(), body)
        assertEquals("SERVER-PEM", resp.serverCert)
        assertEquals("CLIENT-PEM", resp.clientCert)
        assertEquals("CA-PEM", resp.relayCaCert)
    }

    // --- DER parsing helpers (duplicated from CsrGeneratorTest) ---

    private fun extractSubjectPublicKeyInfo(csrDer: ByteArray): ByteArray {
        val outer = derContent(csrDer, 0)
        val certReqInfoContent = derContent(outer.bytes, outer.offset)
        var off = certReqInfoContent.offset
        off = skipTlv(certReqInfoContent.bytes, off) // version
        off = skipTlv(certReqInfoContent.bytes, off) // subject
        return derTlv(certReqInfoContent.bytes, off) // subjectPKInfo
    }

    private fun derContent(bytes: ByteArray, offset: Int): DerPointer {
        val (contentOff, _) = readLength(bytes, offset)
        return DerPointer(bytes, contentOff)
    }

    private fun derTlv(bytes: ByteArray, offset: Int): ByteArray {
        val (contentOff, contentLen) = readLength(bytes, offset)
        return bytes.copyOfRange(offset, contentOff + contentLen)
    }

    private fun skipTlv(bytes: ByteArray, offset: Int): Int {
        val (contentOff, contentLen) = readLength(bytes, offset)
        return contentOff + contentLen
    }

    private fun readLength(bytes: ByteArray, offset: Int): Pair<Int, Int> {
        val lengthByte = bytes[offset + 1].toInt() and 0xFF
        return if (lengthByte < 0x80) {
            Pair(offset + 2, lengthByte)
        } else {
            val numBytes = lengthByte and 0x7F
            var length = 0
            for (i in 0 until numBytes) {
                length = (length shl 8) or (bytes[offset + 2 + i].toInt() and 0xFF)
            }
            Pair(offset + 2 + numBytes, length)
        }
    }

    private data class DerPointer(val bytes: ByteArray, val offset: Int)

    private companion object {
        val lenientJson = Json { ignoreUnknownKeys = true }
    }
}
