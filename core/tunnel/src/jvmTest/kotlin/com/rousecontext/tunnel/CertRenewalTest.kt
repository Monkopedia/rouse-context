package com.rousecontext.tunnel

import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

    /** A real ECDSA P-256 keypair for the device key manager, generated once per test class. */
    private val testKeyPair: KeyPair by lazy {
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256r1"))
        kpg.generateKeyPair()
    }
    private val keyManager: InMemoryDeviceKeyManager by lazy {
        InMemoryDeviceKeyManager(seed = testKeyPair)
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
            deviceKeyManager = keyManager,
            certInspector = inspector,
            maxRetries = 2,
            baseRetryDelayMs = 10L
        )
    }

    @Test
    fun `mTLS renewal succeeds with valid cert and signature`(): Unit = runBlocking {
        store.storeCertificate(MockRelayServer.MOCK_CERT_PEM)
        store.storeSubdomain("test123")
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
    fun `renewal CSR carries the device key manager public key`(): Unit = runBlocking {
        // Seed the DeviceKeyManager with a known keypair so the test can assert the
        // renewal CSR carries exactly that public key. Issue #199 + #200: renewals
        // must reuse the registration keypair so the relay's stored public-key pin
        // stays valid.
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256r1"))
        val registrationKp = kpg.generateKeyPair()
        val seededKeyManager = InMemoryDeviceKeyManager(seed = registrationKp)

        store.storeCertificate(MockRelayServer.MOCK_CERT_PEM)
        store.storeSubdomain("test123")

        val flow = CertRenewalFlow(
            csrGenerator = CsrGenerator(),
            relayApiClient = RelayApiClient(baseUrl = mockServer.baseUrl),
            certificateStore = store,
            deviceKeyManager = seededKeyManager,
            certInspector = validInspector,
            maxRetries = 2,
            baseRetryDelayMs = 10L
        )

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

        // The CSR public key must match the DeviceKeyManager-owned keypair's public key.
        val expectedPublicKeyDer = registrationKp.public.encoded
        assertTrue(
            csrPublicKeyDer.contentEquals(expectedPublicKeyDer),
            "Renewal CSR must carry the device key manager's public key, not a fresh one"
        )

        // A second call to the key manager must return the same keypair (idempotency
        // guard -- the whole point of DeviceKeyManager.getOrCreateKeyPair is that
        // renewal reuses the registration key).
        val secondCall = seededKeyManager.getOrCreateKeyPair()
        assertTrue(
            secondCall.public.encoded.contentEquals(registrationKp.public.encoded),
            "DeviceKeyManager must return the same keypair across calls"
        )
    }

    @Test
    fun `consecutive renewals reuse the same device key`(): Unit = runBlocking {
        // Issue #200: the DeviceKeyManager contract is that getOrCreateKeyPair() is
        // idempotent. This test asserts that two CertRenewalFlow.renewWithMtls calls
        // in sequence build CSRs carrying identical public keys -- a regression here
        // would invalidate the relay's registration-time public-key pin.
        store.storeCertificate(MockRelayServer.MOCK_CERT_PEM)
        store.storeSubdomain("test123")
        val flow = createFlow()

        val capturedCsrs = mutableListOf<String>()
        mockServer.renewHandler = { request ->
            capturedCsrs += request.csr
            MockRenewResponse(
                status = 200,
                body = RenewResponse(
                    serverCert = MockRelayServer.MOCK_CERT_PEM,
                    clientCert = MockRelayServer.MOCK_CLIENT_CERT_PEM,
                    relayCaCert = MockRelayServer.MOCK_RELAY_CA_PEM
                )
            )
        }

        val first = flow.renewWithMtls(signature = "sig1")
        val second = flow.renewWithMtls(signature = "sig2")
        assertTrue(first is RenewalResult.Success, "first renewal should succeed")
        assertTrue(second is RenewalResult.Success, "second renewal should succeed")

        assertEquals(2, capturedCsrs.size)
        val firstSpki = extractSubjectPublicKeyInfo(pemToDer(capturedCsrs[0]))
        val secondSpki = extractSubjectPublicKeyInfo(pemToDer(capturedCsrs[1]))
        assertTrue(
            firstSpki.contentEquals(secondSpki),
            "Consecutive renewal CSRs must carry the same public key"
        )
    }

    private fun pemToDer(csrPem: String): ByteArray = Base64.getDecoder().decode(
        csrPem
            .substringAfter("-----BEGIN CERTIFICATE REQUEST-----")
            .substringBefore("-----END CERTIFICATE REQUEST-----")
            .replace("\\s".toRegex(), "")
    )

    @Test
    fun `expired cert returns CertExpired for mTLS path`(): Unit = runBlocking {
        store.storeCertificate(MockRelayServer.MOCK_CERT_PEM)
        store.storeSubdomain("test123")
        val flow = createFlow(inspector = expiredInspector)

        val result = flow.renewWithMtls(signature = "unused")
        assertTrue(result is RenewalResult.CertExpired)
    }

    @Test
    fun `Firebase renewal succeeds with expired cert`(): Unit = runBlocking {
        store.storeSubdomain("test123")
        store.storeCertificate(MockRelayServer.MOCK_CERT_PEM)
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

    // --- Real (production) CertInspector tests (issue #495) ---

    @Test
    fun `real CertInspector parses CN and reports non-expired`() {
        val info = CertInspector().inspect(REAL_CERT_TEST123)
        assertEquals("test123.rousecontext.com", info.commonName)
        assertFalse(info.isExpired, "fixture cert is valid until 2036")
    }

    @Test
    fun `real CertInspector extracts CN via the JNDI-free X500Principal path`() {
        // Guards issue #499: CN extraction must parse the X500Principal RFC 2253 string directly,
        // not via javax.naming.ldap.LdapName (JNDI is absent from the Android runtime). Both
        // fixtures must yield their real subject CN through CertificateFactory + the new parser.
        assertEquals(
            "test123.rousecontext.com",
            CertInspector().inspect(REAL_CERT_TEST123).commonName
        )
        assertEquals(
            "other-cn.rousecontext.com",
            CertInspector().inspect(REAL_CERT_OTHER).commonName
        )
    }

    @Test
    fun `real CertInspector flags an expired cert`() {
        val info = CertInspector().inspect(REAL_CERT_EXPIRED)
        assertEquals("test123.rousecontext.com", info.commonName)
        assertTrue(info.isExpired, "fixture cert expired in 2021")
    }

    @Test
    fun `real CertInspector degrades to empty CertInfo on a malformed cert`() {
        val info = CertInspector().inspect("not a real certificate")
        assertNull(info.commonName)
        assertFalse(info.isExpired)
    }

    @Test
    fun `mTLS CN mismatch fires end-to-end with the real inspector`(): Unit = runBlocking {
        store.storeCertificate(REAL_CERT_TEST123)
        store.storeSubdomain("test123")
        val flow = createFlow(inspector = CertInspector())

        mockServer.renewHandler = { _ ->
            MockRenewResponse(
                status = 200,
                body = RenewResponse(
                    serverCert = REAL_CERT_OTHER,
                    clientCert = MockRelayServer.MOCK_CLIENT_CERT_PEM,
                    relayCaCert = MockRelayServer.MOCK_RELAY_CA_PEM
                )
            )
        }

        val result = flow.renewWithMtls(signature = "sig")
        assertTrue(result is RenewalResult.CnMismatch, "expected CnMismatch, got $result")
        assertEquals("test123.rousecontext.com", result.expected)
        assertEquals("other-cn.rousecontext.com", result.actual)
    }

    @Test
    fun `Firebase path CN mismatch fires end-to-end with the real inspector`(): Unit = runBlocking {
        // Guards the firebase-path wiring: the current cert must be passed into
        // handleRenewResponse so the CN-mismatch safety net runs on this path too.
        store.storeCertificate(REAL_CERT_TEST123)
        store.storeSubdomain("test123")
        val flow = createFlow(inspector = CertInspector())

        mockServer.renewHandler = { _ ->
            MockRenewResponse(
                status = 200,
                body = RenewResponse(
                    serverCert = REAL_CERT_OTHER,
                    clientCert = MockRelayServer.MOCK_CLIENT_CERT_PEM,
                    relayCaCert = MockRelayServer.MOCK_RELAY_CA_PEM
                )
            )
        }

        val result = flow.renewWithFirebase(firebaseToken = "t", signature = "s")
        assertTrue(result is RenewalResult.CnMismatch, "expected CnMismatch, got $result")
        assertEquals("test123.rousecontext.com", result.expected)
        assertEquals("other-cn.rousecontext.com", result.actual)
    }

    @Test
    fun `expired current cert returns CertExpired with the real inspector`(): Unit = runBlocking {
        store.storeCertificate(REAL_CERT_EXPIRED)
        store.storeSubdomain("test123")
        val flow = createFlow(inspector = CertInspector())

        val result = flow.renewWithMtls(signature = "unused")
        assertTrue(result is RenewalResult.CertExpired, "expected CertExpired, got $result")
    }

    @Test
    fun `rate limited schedules retry`(): Unit = runBlocking {
        store.storeCertificate(MockRelayServer.MOCK_CERT_PEM)
        store.storeSubdomain("test123")
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

        val client = RelayApiClient(baseUrl = "http://127.0.0.1:1")
        val flow = CertRenewalFlow(
            csrGenerator = CsrGenerator(),
            relayApiClient = client,
            certificateStore = store,
            deviceKeyManager = keyManager,
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

        // Real self-signed ECDSA P-256 certs (openssl) used to exercise the production
        // CertInspector. Unlike the hand-built MockRelayServer PEMs, these parse via
        // CertificateFactory so CN + expiry extraction is real (issue #495).

        // CN=test123.rousecontext.com, valid until 2036-06-15.
        const val REAL_CERT_TEST123 = """-----BEGIN CERTIFICATE-----
MIIBmzCCAUGgAwIBAgIUMZZKurrSJOfzTdyw1djx+MBhlhMwCgYIKoZIzj0EAwIw
IzEhMB8GA1UEAwwYdGVzdDEyMy5yb3VzZWNvbnRleHQuY29tMB4XDTI2MDYxODE5
NTgwOFoXDTM2MDYxNTE5NTgwOFowIzEhMB8GA1UEAwwYdGVzdDEyMy5yb3VzZWNv
bnRleHQuY29tMFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAErYaEDbL/A/2zsnJD
arWcJJPqvV6KZdqn+7Vj4UkgJihvNzUJ9CzfKevOg2+fc+5rvTUV+8MP7mVJiCW9
gg1lvaNTMFEwHQYDVR0OBBYEFAhAgnv2Gl1r5H1Rg4spGlmmrLrEMB8GA1UdIwQY
MBaAFAhAgnv2Gl1r5H1Rg4spGlmmrLrEMA8GA1UdEwEB/wQFMAMBAf8wCgYIKoZI
zj0EAwIDSAAwRQIgALN5eRW0AqhfU/Qrk9UiYFxfGBY7pO6bis/aC/32By8CIQDj
AgNLz8OO6pAjGBI6UOCPjMVrHxqo7nwHj+dSw0BT6A==
-----END CERTIFICATE-----"""

        // CN=other-cn.rousecontext.com, valid until 2036-06-15. Different subject CN
        // than REAL_CERT_TEST123 to trigger the CN-mismatch safety net.
        const val REAL_CERT_OTHER = """-----BEGIN CERTIFICATE-----
MIIBnTCCAUOgAwIBAgIUO51XPyr4cBUmtS/NUQfLFQDXQZkwCgYIKoZIzj0EAwIw
JDEiMCAGA1UEAwwZb3RoZXItY24ucm91c2Vjb250ZXh0LmNvbTAeFw0yNjA2MTgx
OTU4MDhaFw0zNjA2MTUxOTU4MDhaMCQxIjAgBgNVBAMMGW90aGVyLWNuLnJvdXNl
Y29udGV4dC5jb20wWTATBgcqhkjOPQIBBggqhkjOPQMBBwNCAATXmnFgOPwXF6Ds
4JkhLZWQG5nxWh4ZFac48uS6lIGdSkPGJX9geC/yKUsAeH55KdzrHnu7IvMDUfqh
BwIwTFbPo1MwUTAdBgNVHQ4EFgQUQtRk1OzPkFDxyKTjlWr1sEwQigQwHwYDVR0j
BBgwFoAUQtRk1OzPkFDxyKTjlWr1sEwQigQwDwYDVR0TAQH/BAUwAwEB/zAKBggq
hkjOPQQDAgNIADBFAiBeH3eUvuWUIxNH1wk3OFRGFfmudXSn7MMQfrHwYVh67QIh
ALRZfL7MdyG1WYqTbWCLCfJrm4+Bfp0mebM5afDJo2uB
-----END CERTIFICATE-----"""

        // CN=test123.rousecontext.com, expired (2020-01-01 .. 2021-01-01).
        const val REAL_CERT_EXPIRED = """-----BEGIN CERTIFICATE-----
MIIBmzCCAUGgAwIBAgIUCj2Eei46jccndtKjced6sLBEHr0wCgYIKoZIzj0EAwIw
IzEhMB8GA1UEAwwYdGVzdDEyMy5yb3VzZWNvbnRleHQuY29tMB4XDTIwMDEwMTAw
MDAwMFoXDTIxMDEwMTAwMDAwMFowIzEhMB8GA1UEAwwYdGVzdDEyMy5yb3VzZWNv
bnRleHQuY29tMFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEy95XyDTAqJXo3swZ
CgEoipmVqx4LmxQpHdWtfsKOtAe6tVXZukwjC/1JKMAC7OiWRPkp3X2GZiWCAkft
RGvL+6NTMFEwHQYDVR0OBBYEFIgBC5XzzINobe/UBwXJTZ3SVmu3MB8GA1UdIwQY
MBaAFIgBC5XzzINobe/UBwXJTZ3SVmu3MA8GA1UdEwEB/wQFMAMBAf8wCgYIKoZI
zj0EAwIDSAAwRQIhANcXWPuTCGFPopswAwEH6Ts7kHkl17QuUuu71Cz6wrz0AiAw
0DZ+vyzoHioE5Rmi23+PUoNGu6o7dUvuym03n7FEQg==
-----END CERTIFICATE-----"""
    }
}
