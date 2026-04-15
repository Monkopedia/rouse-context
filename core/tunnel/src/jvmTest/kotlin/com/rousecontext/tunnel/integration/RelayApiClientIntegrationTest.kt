package com.rousecontext.tunnel.integration

import com.rousecontext.tunnel.CsrGenerator
import com.rousecontext.tunnel.RelayApiClient
import com.rousecontext.tunnel.RelayApiResult
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import java.io.File
import java.net.InetAddress
import java.security.KeyStore
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.Dns
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag

/**
 * End-to-end HTTP wire-contract tests for [RelayApiClient] against the real
 * Rust relay binary. Each test round-trips through the relay's axum router,
 * Firestore (in-memory stub), and device CA so a wire-format change on
 * either side fails loudly here.
 *
 * The relay's Firebase ID token verification is disabled via
 * `[firebase] verify_tokens = false` so tests can use plausible dummy
 * strings instead of minting real tokens. See [TestRelayManager] and the
 * `FirebaseConfig::verify_tokens` field on the Rust side for why that knob
 * is safe.
 *
 * Skipped when the relay binary has not been built. Build with:
 *   cd relay && cargo build
 */
@Tag("integration")
class RelayApiClientIntegrationTest {

    companion object {
        // Use a non-literal hostname so the JDK includes it as SNI in the
        // TLS ClientHello. Plain "localhost" triggers SNI suppression on
        // some JDK paths when it resolves directly to 127.0.0.1, and the
        // relay's SNI router rejects connections with no SNI.
        //
        // The relay derives `base_domain` from `relay_hostname` by stripping
        // a leading "relay." prefix. That leaves `test.internal` as the base
        // domain, which means a device cert CN of `test-device.relay.test.
        // internal` would not strip to a bare `test-device`. We instead
        // override `base_domain` explicitly to `relay.test.internal` so the
        // device CN strips cleanly to `test-device`.
        private const val RELAY_HOSTNAME = "relay.test.internal"
        private const val BASE_DOMAIN = "relay.test.internal"
        private const val DEVICE_SUBDOMAIN = "test-device"
        private const val DUMMY_FIREBASE_TOKEN = "test-firebase-token-ABC"
        private const val DUMMY_FCM_TOKEN = "test-fcm-token-XYZ"
    }

    private lateinit var tempDir: File
    private lateinit var ca: TestCertificateAuthority
    private lateinit var relayManager: TestRelayManager
    private lateinit var baseUrl: String
    private lateinit var httpClient: HttpClient
    private lateinit var api: RelayApiClient

    @BeforeTest
    fun setUp() {
        val relayBinary = findRelayBinary()
        assumeTrue(
            relayBinary.exists() && relayBinary.canExecute(),
            "Relay binary not found. Build with: cd relay && cargo build"
        )

        tempDir = File.createTempFile("relay-api-integration-", "")
        tempDir.delete()
        tempDir.mkdirs()

        ca = TestCertificateAuthority(tempDir, RELAY_HOSTNAME, DEVICE_SUBDOMAIN)
        ca.generate()

        // Hand the test CA to the relay as its device CA so it can issue
        // client certs from the same trust root the tests already trust.
        val deviceCaKeyFile = File(tempDir, "device-ca-key.pem")
        ca.writeCaKey(deviceCaKeyFile)
        val deviceCaCertFile = ca.caCertPemFile()

        val port = findFreePort()
        relayManager = TestRelayManager(
            tempDir,
            RELAY_HOSTNAME,
            deviceCaPaths = deviceCaKeyFile to deviceCaCertFile,
            disableFirebaseVerification = true,
            baseDomainOverride = BASE_DOMAIN
        )
        relayManager.start(port)

        // Keep the hostname as `relay.test.internal` so the TLS ClientHello
        // SNI matches `server.relay_hostname` and routes to the relay API
        // handler. A custom OkHttp DNS override below forces the lookup to
        // 127.0.0.1 (where the relay is listening).
        baseUrl = "https://$RELAY_HOSTNAME:$port"

        val (trustingSsl, trustingTm) = buildTrustingSslContext(ca.caCert)
        httpClient = HttpClient(OkHttp) {
            engine {
                config {
                    sslSocketFactory(trustingSsl.socketFactory, trustingTm)
                    hostnameVerifier { _, _ -> true }
                    dns(Ipv4OnlyDns)
                }
            }
            install(ContentNegotiation) {
                json(
                    Json {
                        ignoreUnknownKeys = true
                        encodeDefaults = true
                    }
                )
            }
            install(HttpTimeout) {
                requestTimeoutMillis = 30_000
                connectTimeoutMillis = 10_000
                socketTimeoutMillis = 30_000
            }
        }
        api = RelayApiClient(baseUrl = baseUrl, httpClient = httpClient)
    }

    @AfterTest
    fun tearDown() {
        if (::httpClient.isInitialized) httpClient.close()
        if (::relayManager.isInitialized) {
            // Print the relay's captured output to stderr so test failures
            // have a chance of being diagnosable without rerunning the
            // subprocess manually.
            System.err.println("--- relay stdout/stderr ---")
            System.err.println(relayManager.capturedOutput())
            System.err.println("--- end relay output ---")
            relayManager.stop()
        }
        if (::tempDir.isInitialized && tempDir.exists()) tempDir.deleteRecursively()
    }

    @Test
    fun `register returns subdomain relay host and per-integration secrets`(): Unit = runBlocking {
        val integrations = listOf("health", "notifications")
        val result = api.register(
            firebaseToken = DUMMY_FIREBASE_TOKEN,
            fcmToken = DUMMY_FCM_TOKEN,
            integrationIds = integrations
        )

        val body = assertSuccess(result)
        assertTrue(
            body.subdomain.isNotBlank(),
            "subdomain must be non-blank, was '${body.subdomain}'"
        )
        assertEquals(
            RELAY_HOSTNAME,
            body.relayHost,
            "relayHost must echo server.relay_hostname"
        )
        assertEquals(
            integrations.toSet(),
            body.secrets.keys,
            "secrets map must be keyed by the requested integration ids"
        )
        for (id in integrations) {
            val secret = body.secrets[id]
            assertNotNull(secret, "secret for '$id' must be present")
            assertTrue(
                secret!!.endsWith("-$id"),
                "secret for '$id' must end with '-$id', was '$secret'"
            )
            assertTrue(
                secret.length > id.length + 1,
                "secret for '$id' must have a non-empty adjective prefix, was '$secret'"
            )
        }
    }

    @Test
    fun `register with empty integrations returns no secrets`(): Unit = runBlocking {
        val result = api.register(
            firebaseToken = DUMMY_FIREBASE_TOKEN + "-alt-1",
            fcmToken = DUMMY_FCM_TOKEN,
            integrationIds = emptyList()
        )

        val body = assertSuccess(result)
        assertTrue(body.subdomain.isNotBlank())
        assertEquals(RELAY_HOSTNAME, body.relayHost)
        assertTrue(
            body.secrets.isEmpty(),
            "no secrets expected when integrations list is empty, got ${body.secrets}"
        )
    }

    @Test
    fun `registerCerts returns fully populated cert bundle`(): Unit = runBlocking {
        // Need to register first so the relay has a device record keyed by
        // the (deterministic test) Firebase UID.
        val firebaseToken = DUMMY_FIREBASE_TOKEN + "-register-certs"
        val reg = assertSuccess(
            api.register(
                firebaseToken = firebaseToken,
                fcmToken = DUMMY_FCM_TOKEN,
                integrationIds = listOf("health")
            )
        )

        val csr = CsrGenerator().generate(commonName = "${reg.subdomain}.$RELAY_HOSTNAME")
        val result = api.registerCerts(csrPem = csr.csrPem, firebaseToken = firebaseToken)

        val body = assertSuccess(result)
        assertEquals(reg.subdomain, body.subdomain)
        assertEquals(RELAY_HOSTNAME, body.relayHost)
        assertPemOrAcmeStub(body.serverCert, "server_cert")
        assertPem(body.clientCert, "CERTIFICATE", "client_cert")
        assertPem(body.relayCaCert, "CERTIFICATE", "relay_ca_cert")
    }

    @Test
    fun `updateSecrets via mTLS returns merged integration secret map`(): Unit = runBlocking {
        // The relay identifies the caller from the mTLS client cert's CN,
        // which must match a subdomain the relay has a device record for.
        // We register first to get a subdomain, then mint a fresh device
        // cert whose CN matches that subdomain so mTLS extraction picks it
        // up when we call /rotate-secret.
        val initialIntegrations = listOf("health")
        val reg = assertSuccess(
            api.register(
                firebaseToken = DUMMY_FIREBASE_TOKEN + "-update-secrets",
                fcmToken = DUMMY_FCM_TOKEN,
                integrationIds = initialIntegrations
            )
        )
        val healthSecret = reg.secrets.getValue("health")
        val subdomain = reg.subdomain

        val keyStoreForSubdomain = ca.createDeviceKeyStore(subdomain)
        val scopedApi = buildMtlsApiClientFor(keyStoreForSubdomain)

        try {
            // Ask for health (already present — must be preserved) +
            // notifications (new — must be freshly minted).
            val requestedIntegrations = listOf("health", "notifications")
            val result = scopedApi.second.updateSecrets(
                subdomain = subdomain,
                integrationIds = requestedIntegrations
            )

            val body = assertSuccess(result)
            assertTrue(body.success, "response.success must be true")
            assertEquals(
                requestedIntegrations.toSet(),
                body.secrets.keys,
                "secrets map must mirror requested integrations after merge-missing"
            )
            assertEquals(
                healthSecret,
                body.secrets["health"],
                "existing 'health' secret must be preserved on merge-missing rotation"
            )
            val notificationsSecret = body.secrets["notifications"]
            assertNotNull(notificationsSecret, "newly-requested 'notifications' must get a secret")
            assertTrue(
                notificationsSecret!!.endsWith("-notifications"),
                "new secret must be in '{adjective}-notifications' form, was '$notificationsSecret'"
            )
            assertTrue(
                notificationsSecret.length > "-notifications".length,
                "new secret must have a non-empty adjective prefix"
            )
        } finally {
            scopedApi.first.close()
        }
    }

    /**
     * KNOWN SCHEMA MISMATCH (to be fixed under a separate ticket):
     *
     * `RelayApiClient.renewWithMtls` serializes `csrPem`, `authMethod`,
     * `currentCertPem`; the relay's `POST /renew` expects `csr`, `subdomain`,
     * `firebase_token`, `signature`. The response shape also differs —
     * client reads `certificatePem`, relay sends `server_cert` + `client_cert`
     * + `relay_ca_cert`. This test locks in the *current* failure mode so
     * that fixing the mismatch produces a test failure, forcing the fix to
     * also replace this assertion with a proper success-shape check.
     *
     * The value of this test today is the same as the issue #167 rationale
     * for /rotate-secret: it proves the client and relay are wired together
     * well enough to deserialize an error response, and will go red the
     * moment either side's wire contract changes.
     */
    @Test
    fun `renewWithMtls currently fails because of client or relay schema mismatch`(): Unit =
        runBlocking {
            val firebaseToken = DUMMY_FIREBASE_TOKEN + "-renew-mtls"
            val reg = assertSuccess(
                api.register(
                    firebaseToken = firebaseToken,
                    fcmToken = DUMMY_FCM_TOKEN,
                    integrationIds = emptyList()
                )
            )
            val renewCsr = CsrGenerator().generate(commonName = "${reg.subdomain}.$RELAY_HOSTNAME")

            val keyStoreForSubdomain = ca.createDeviceKeyStore(reg.subdomain)
            val scopedApi = buildMtlsApiClientFor(keyStoreForSubdomain)

            try {
                val result = scopedApi.second.renewWithMtls(
                    csrPem = renewCsr.csrPem,
                    currentCertPem = "unused-by-relay"
                )

                assertTrue(
                    result is RelayApiResult.Error,
                    "expected an Error result while the renew schema mismatch is unresolved, " +
                        "got $result"
                )
                val err = result as RelayApiResult.Error
                assertEquals(
                    422,
                    err.statusCode,
                    "relay should report a 422 for the missing 'csr' field"
                )
                assertTrue(
                    err.message.contains("csr", ignoreCase = true) ||
                        err.message.contains("Unprocessable", ignoreCase = true),
                    "error body must reference the schema failure, was '${err.message}'"
                )
            } finally {
                scopedApi.first.close()
            }
        }

    /**
     * Same KNOWN SCHEMA MISMATCH as the mTLS renewal path. See that test's
     * comment. /renew does not distinguish auth path at the wire level
     * except by which optional fields are set; both paths currently fail
     * against the real relay for the same `csrPem` vs `csr` reason.
     */
    @Test
    fun `renewWithFirebase currently fails because of client or relay schema mismatch`(): Unit =
        runBlocking {
            val firebaseToken = DUMMY_FIREBASE_TOKEN + "-renew-firebase"
            val reg = assertSuccess(
                api.register(
                    firebaseToken = firebaseToken,
                    fcmToken = DUMMY_FCM_TOKEN,
                    integrationIds = emptyList()
                )
            )
            val renewCsr = CsrGenerator().generate(commonName = "${reg.subdomain}.$RELAY_HOSTNAME")

            val result = api.renewWithFirebase(
                csrPem = renewCsr.csrPem,
                firebaseToken = firebaseToken,
                signature = "dummy-signature-base64"
            )

            assertTrue(
                result is RelayApiResult.Error,
                "expected an Error result while the renew schema mismatch is unresolved, " +
                    "got $result"
            )
            val err = result as RelayApiResult.Error
            assertEquals(
                422,
                err.statusCode,
                "relay should report a 422 for the missing 'csr' field"
            )
            assertTrue(
                err.message.contains("csr", ignoreCase = true) ||
                    err.message.contains("Unprocessable", ignoreCase = true),
                "error body must reference the schema failure, was '${err.message}'"
            )
        }

    // --- helpers ---

    private fun <T> assertSuccess(result: RelayApiResult<T>): T {
        when (result) {
            is RelayApiResult.Success -> return result.data
            is RelayApiResult.Error -> fail(
                "Relay returned error: status=${result.statusCode} message=${result.message}"
            )
            is RelayApiResult.RateLimited -> fail(
                "Relay rate-limited: retryAfter=${result.retryAfterSeconds}"
            )
            is RelayApiResult.NetworkError -> fail("Network error: ${result.cause}")
        }
    }

    private fun assertPem(pem: String, label: String, fieldName: String) {
        assertTrue(pem.isNotBlank(), "$fieldName must be non-blank")
        val header = "-----BEGIN $label-----"
        val footer = "-----END $label-----"
        assertTrue(pem.contains(header), "$fieldName must contain '$header', was:\n$pem")
        assertTrue(pem.contains(footer), "$fieldName must contain '$footer', was:\n$pem")
    }

    /**
     * The relay's ACME issuer is stubbed in test mode and returns the literal
     * string "stub-cert" instead of a real Let's Encrypt cert. Accept that
     * exact stub OR a real PEM, but fail loudly on anything else — in
     * particular on an empty string, which would indicate a schema drift
     * that silently serialized the field away.
     */
    private fun assertPemOrAcmeStub(pem: String, fieldName: String) {
        assertTrue(pem.isNotBlank(), "$fieldName must be non-blank")
        if (pem == "stub-cert") return
        assertPem(pem, "CERTIFICATE", fieldName)
    }

    private fun buildMtlsApiClientFor(keyStore: KeyStore): Pair<HttpClient, RelayApiClient> {
        val (ssl, tm) = buildMtlsSslContext(keyStore, ca.caCert)
        val client = HttpClient(OkHttp) {
            engine {
                config {
                    sslSocketFactory(ssl.socketFactory, tm)
                    hostnameVerifier { _, _ -> true }
                    dns(Ipv4OnlyDns)
                }
            }
            install(ContentNegotiation) {
                json(
                    Json {
                        ignoreUnknownKeys = true
                        encodeDefaults = true
                    }
                )
            }
            install(HttpTimeout) {
                requestTimeoutMillis = 30_000
                connectTimeoutMillis = 10_000
                socketTimeoutMillis = 30_000
            }
        }
        return client to RelayApiClient(baseUrl = baseUrl, httpClient = client)
    }

    private fun buildTrustingSslContext(
        caCert: X509Certificate
    ): Pair<SSLContext, X509TrustManager> {
        val trustStore = KeyStore.getInstance(KeyStore.getDefaultType())
        trustStore.load(null, null)
        trustStore.setCertificateEntry("ca", caCert)
        val tmf = javax.net.ssl.TrustManagerFactory.getInstance(
            javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm()
        )
        tmf.init(trustStore)
        val tm = tmf.trustManagers.first { it is X509TrustManager } as X509TrustManager
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(null, arrayOf(tm), null)
        return ctx to tm
    }

    private fun buildMtlsSslContext(
        deviceKeyStore: KeyStore,
        caCert: X509Certificate
    ): Pair<SSLContext, X509TrustManager> {
        val kmf = javax.net.ssl.KeyManagerFactory.getInstance(
            javax.net.ssl.KeyManagerFactory.getDefaultAlgorithm()
        )
        kmf.init(deviceKeyStore, TestCertificateAuthority.STORE_PASS.toCharArray())

        val trustStore = KeyStore.getInstance(KeyStore.getDefaultType())
        trustStore.load(null, null)
        trustStore.setCertificateEntry("ca", caCert)
        val tmf = javax.net.ssl.TrustManagerFactory.getInstance(
            javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm()
        )
        tmf.init(trustStore)
        val tm = tmf.trustManagers.first { it is X509TrustManager } as X509TrustManager

        val ctx = SSLContext.getInstance("TLS")
        ctx.init(kmf.keyManagers, arrayOf(tm), null)
        return ctx to tm
    }

    /**
     * Force every hostname to resolve to 127.0.0.1. The relay only binds
     * IPv4 loopback, so if we let JDK pick ::1 for "localhost" (default on
     * dual-stack hosts) the connect blows up before we ever hit the relay.
     */
    private object Ipv4OnlyDns : Dns {
        override fun lookup(hostname: String): List<InetAddress> =
            listOf(InetAddress.getByName("127.0.0.1"))
    }
}
