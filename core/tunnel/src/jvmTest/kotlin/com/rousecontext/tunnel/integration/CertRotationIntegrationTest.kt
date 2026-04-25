package com.rousecontext.tunnel.integration

import com.rousecontext.tunnel.CsrGenerator
import com.rousecontext.tunnel.RelayApiClient
import com.rousecontext.tunnel.RelayApiResult
import com.rousecontext.tunnel.TunnelClientImpl
import com.rousecontext.tunnel.TunnelState
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import java.io.ByteArrayInputStream
import java.io.File
import java.net.InetAddress
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.Dns
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

/**
 * End-to-end cert rotation test against the real Rust relay binary.
 *
 * Exercises the full `/renew` path:
 * 1. Register device, get initial cert bundle via `/register/certs`.
 * 2. Generate a new ECDSA P-256 CSR via [CsrGenerator].
 * 3. Sign the renewal CSR DER with SHA256withECDSA using the registration private key.
 * 4. Call [RelayApiClient.renewWithMtls] against the real relay.
 * 5. Assert the relay returns a NEW cert triple different from the original.
 * 6. Reconnect the tunnel using the renewed client cert and prove TLS handshake succeeds.
 *
 * Uses [RELAY_HOSTNAME] = `relay.test.internal` for API calls (via OkHttp with
 * [Ipv4OnlyDns] to resolve to 127.0.0.1) so the relay's SNI router accepts the
 * connection. WebSocket reconnection also uses this hostname via the same DNS
 * trick through [Ipv4OnlyMtlsWebSocketFactory].
 *
 * Skipped when the relay binary has not been built. Build with:
 *   cd relay && cargo build --features test-mode
 */
@Tag("integration")
@Timeout(value = 180, unit = TimeUnit.SECONDS)
class CertRotationIntegrationTest {

    companion object {
        private const val RELAY_HOSTNAME = "relay.test.internal"
        private const val BASE_DOMAIN = "relay.test.internal"
        private const val DUMMY_FIREBASE_TOKEN = "test-firebase-token-rotation"
        private const val DUMMY_FCM_TOKEN = "test-fcm-token-rotation"

        /**
         * Upper bound for [TestRelayManager.waitForSessionRegistered]. The
         * relay-side `Notify` typically fires within a millisecond of the
         * mux WebSocket upgrade completing; 10s is generous for CI under
         * stress (#400).
         */
        private const val SESSION_REGISTRATION_TIMEOUT_MS = 10_000L
    }

    private lateinit var tempDir: File
    private lateinit var ca: TestCertificateAuthority
    private lateinit var relayManager: TestRelayManager
    private lateinit var httpClient: HttpClient
    private lateinit var api: RelayApiClient
    private var relayPort: Int = 0

    @BeforeEach
    fun setUp() {
        val relayBinary = findRelayBinary()
        assumeTrue(
            relayBinary.exists() && relayBinary.canExecute(),
            "Relay binary not found. Build with: cd relay && cargo build --features test-mode"
        )

        tempDir = File.createTempFile("cert-rotation-integration-", "")
        tempDir.delete()
        tempDir.mkdirs()

        // Use a throwaway subdomain; the relay assigns one dynamically during registration.
        ca = TestCertificateAuthority(tempDir, RELAY_HOSTNAME, "placeholder")
        ca.generate()

        val deviceCaKeyFile = File(tempDir, "device-ca-key.pem")
        ca.writeCaKey(deviceCaKeyFile)
        val deviceCaCertFile = ca.caCertPemFile()

        relayPort = findFreePort()
        // test-mode enables the `/test/wait-session-registered` admin
        // endpoint used after `tunnelClient.connect()` to deterministically
        // wait for the relay-side `SessionRegistry.insert` instead of a
        // blind 500ms sleep (#400).
        relayManager = TestRelayManager(
            tempDir,
            RELAY_HOSTNAME,
            deviceCaPaths = deviceCaKeyFile to deviceCaCertFile,
            disableFirebaseVerification = true,
            baseDomainOverride = BASE_DOMAIN,
            enableTestMode = true
        )
        relayManager.start(relayPort)

        val (trustingSsl, trustingTm) = buildTrustingSslContext()
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
        api = RelayApiClient(
            baseUrl = "https://$RELAY_HOSTNAME:$relayPort",
            httpClient = httpClient
        )
    }

    @AfterEach
    fun tearDown() {
        if (::httpClient.isInitialized) httpClient.close()
        if (::relayManager.isInitialized) {
            System.err.println("--- relay stdout/stderr ---")
            System.err.println(relayManager.capturedOutput())
            System.err.println("--- end relay output ---")
            relayManager.stop()
        }
        if (::tempDir.isInitialized && tempDir.exists()) tempDir.deleteRecursively()
    }

    /**
     * Full cert rotation end-to-end: register, get certs, renew, assert new certs,
     * reconnect with renewed client cert and prove the mux session is accepted.
     */
    @Suppress("LongMethod")
    @Test
    fun `renew returns new cert triple and reconnect with renewed cert succeeds`(): Unit =
        runBlocking {
            // --- Step 1: Register device and get initial cert bundle ---
            val reg = assertSuccess(
                api.register(
                    firebaseToken = DUMMY_FIREBASE_TOKEN,
                    fcmToken = DUMMY_FCM_TOKEN,
                    integrationIds = emptyList()
                )
            )
            val subdomain = reg.subdomain
            val fqdn = "$subdomain.$RELAY_HOSTNAME"

            val regKey = newKeyPair()
            val regCsr = CsrGenerator().generate(commonName = fqdn, keyPair = regKey)
            val initialCerts = assertSuccess(
                api.registerCerts(csrPem = regCsr.csrPem, firebaseToken = DUMMY_FIREBASE_TOKEN)
            )

            // --- Step 2: Generate renewal CSR reusing the registration keypair ---
            val renewCsr = CsrGenerator().generate(commonName = fqdn, keyPair = regKey)

            // --- Step 3: Sign renewal CSR DER with the registration private key ---
            val signature = signCsrDer(regKey, renewCsr.csrDer)

            // --- Step 4: Call renewWithMtls against the real relay ---
            val renewedCerts = assertSuccess(
                api.renewWithMtls(
                    csrPem = renewCsr.csrPem,
                    subdomain = subdomain,
                    signature = signature
                )
            )

            // --- Step 5: Assert the relay returned a NEW cert triple ---
            assertPem(renewedCerts.clientCert, "CERTIFICATE", "renewed client_cert")
            assertPem(renewedCerts.relayCaCert, "CERTIFICATE", "renewed relay_ca_cert")
            assertTrue(
                renewedCerts.relayCaCert.isNotBlank(),
                "Renewed relay_ca_cert must be non-blank"
            )

            // Parse both certs and assert distinct serial numbers. The relay
            // now uses random serials, so even same-key same-second renewals
            // produce different certificates.
            val initialX509 = parsePemCert(initialCerts.clientCert)
            val renewedX509 = parsePemCert(renewedCerts.clientCert)
            assertNotEquals(
                initialX509.serialNumber,
                renewedX509.serialNumber,
                "Renewed cert must have a different serial number"
            )

            // --- Step 6: Build an mTLS SSLContext from the renewed client cert ---
            val renewedSslContext = buildMtlsSslContextFromKey(
                clientCertPem = renewedCerts.clientCert,
                privateKey = regKey,
                caCertPem = renewedCerts.relayCaCert
            )

            // --- Step 7: Reconnect tunnel with new certs, assert session accepted ---
            val wsFactory = Ipv4OnlyMtlsWebSocketFactory(renewedSslContext)
            val tunnelClient = TunnelClientImpl(
                scope = kotlinx.coroutines.CoroutineScope(Dispatchers.IO),
                webSocketFactory = wsFactory
            )
            tunnelClient.connect("wss://$RELAY_HOSTNAME:$relayPort/ws")
            assertEquals(
                TunnelState.CONNECTED,
                tunnelClient.state.value,
                "TunnelClient must reach CONNECTED state after reconnect with renewed cert"
            )

            // Deterministic wait for the relay's `SessionRegistry.insert`
            // after the WS upgrade completes. Replaces the former 500ms
            // blind sleep (#400). Backed by per-subdomain `Notify` on the
            // relay, exposed via the test-mode admin endpoint.
            val registered = relayManager.waitForSessionRegistered(
                subdomain,
                SESSION_REGISTRATION_TIMEOUT_MS
            )
            assertTrue(
                registered,
                "Relay did not register mux session for $subdomain within " +
                    "${SESSION_REGISTRATION_TIMEOUT_MS}ms"
            )

            // Clean disconnect
            tunnelClient.disconnect()
        }

    /**
     * Second renewal uses the *original* registration private key to sign
     * (the relay does not rotate the stored public key on renewal). Verifies
     * that consecutive renewals work and each produces a distinct client cert.
     */
    @Suppress("LongMethod")
    @Test
    fun `consecutive renewals with registration key produce distinct certs`(): Unit = runBlocking {
        // Register + initial certs
        val reg = assertSuccess(
            api.register(
                firebaseToken = DUMMY_FIREBASE_TOKEN + "-double",
                fcmToken = DUMMY_FCM_TOKEN,
                integrationIds = emptyList()
            )
        )
        val fqdn = "${reg.subdomain}.$RELAY_HOSTNAME"
        val regKey = newKeyPair()
        val regCsr = CsrGenerator().generate(commonName = fqdn, keyPair = regKey)
        assertSuccess(
            api.registerCerts(
                csrPem = regCsr.csrPem,
                firebaseToken = DUMMY_FIREBASE_TOKEN + "-double"
            )
        )

        // First renewal -- reusing the original registration keypair
        val renewCsr1 = CsrGenerator().generate(commonName = fqdn, keyPair = regKey)
        val sig1 = signCsrDer(regKey, renewCsr1.csrDer)
        val renewed1 = assertSuccess(
            api.renewWithMtls(
                csrPem = renewCsr1.csrPem,
                subdomain = reg.subdomain,
                signature = sig1
            )
        )

        // Second renewal -- also reusing the original registration keypair
        val renewCsr2 = CsrGenerator().generate(commonName = fqdn, keyPair = regKey)
        val sig2 = signCsrDer(regKey, renewCsr2.csrDer)
        val renewed2 = assertSuccess(
            api.renewWithMtls(
                csrPem = renewCsr2.csrPem,
                subdomain = reg.subdomain,
                signature = sig2
            )
        )

        assertPem(renewed2.clientCert, "CERTIFICATE", "second renewal client_cert")

        // Assert distinct serial numbers between the two renewals
        val renewed1X509 = parsePemCert(renewed1.clientCert)
        val renewed2X509 = parsePemCert(renewed2.clientCert)
        assertNotEquals(
            renewed1X509.serialNumber,
            renewed2X509.serialNumber,
            "Second renewal must produce a cert with a different serial number"
        )

        // Reconnect with second renewal certs
        val sslContext = buildMtlsSslContextFromKey(
            clientCertPem = renewed2.clientCert,
            privateKey = regKey,
            caCertPem = renewed2.relayCaCert
        )
        val wsFactory = Ipv4OnlyMtlsWebSocketFactory(sslContext)
        val tunnelClient = TunnelClientImpl(
            scope = kotlinx.coroutines.CoroutineScope(Dispatchers.IO),
            webSocketFactory = wsFactory
        )
        tunnelClient.connect("wss://$RELAY_HOSTNAME:$relayPort/ws")
        assertEquals(
            TunnelState.CONNECTED,
            tunnelClient.state.value,
            "TunnelClient must connect with second-renewal certs"
        )
        // Deterministic wait for the relay's `SessionRegistry.insert` after
        // the WS upgrade completes. Replaces the former 500ms blind sleep
        // (#400). Backed by per-subdomain `Notify` on the relay, exposed via
        // the test-mode admin endpoint.
        val registered = relayManager.waitForSessionRegistered(
            reg.subdomain,
            SESSION_REGISTRATION_TIMEOUT_MS
        )
        assertTrue(
            registered,
            "Relay did not register mux session for ${reg.subdomain} within " +
                "${SESSION_REGISTRATION_TIMEOUT_MS}ms"
        )
        tunnelClient.disconnect()
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /**
     * Sign [renewCsrDer] with SHA256withECDSA using [registrationKey], which was the keypair
     * the device presented at registration. The relay pins the public half at /register/certs
     * time and verifies the signature against that pinned key on every subsequent /renew.
     */
    private fun signCsrDer(registrationKey: KeyPair, renewCsrDer: ByteArray): String {
        val signer = Signature.getInstance("SHA256withECDSA")
        signer.initSign(registrationKey.private)
        signer.update(renewCsrDer)
        return Base64.getEncoder().encodeToString(signer.sign())
    }

    /** Fresh P-256 keypair for test-owned registration/renewal flows. */
    private fun newKeyPair(): KeyPair {
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256r1"))
        return kpg.generateKeyPair()
    }

    /**
     * Build an mTLS [SSLContext] from the relay-supplied client cert PEM and the
     * caller-owned [privateKey] (which in production lives inside the Android Keystore).
     * The relay CA cert and the test CA cert are loaded as trust anchors.
     */
    @Suppress("LongMethod")
    private fun buildMtlsSslContextFromKey(
        clientCertPem: String,
        privateKey: KeyPair,
        caCertPem: String
    ): SSLContext {
        val certFactory = CertificateFactory.getInstance("X.509")

        // Parse client cert
        val clientCert = certFactory.generateCertificate(
            ByteArrayInputStream(clientCertPem.toByteArray())
        )

        // Parse relay CA cert (issuer of client cert)
        val relayCaCert = certFactory.generateCertificate(
            ByteArrayInputStream(caCertPem.toByteArray())
        )

        // Build PKCS12 KeyStore with the client cert chain + private key
        val keyStore = KeyStore.getInstance("PKCS12")
        keyStore.load(null, null)
        keyStore.setKeyEntry(
            "device",
            privateKey.private,
            KEYSTORE_PASS.toCharArray(),
            arrayOf(clientCert, relayCaCert)
        )

        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(keyStore, KEYSTORE_PASS.toCharArray())

        // Trust both the relay device CA (for client cert chain validation) and
        // the test CA (which signed the relay's TLS server certificate).
        val trustStore = KeyStore.getInstance(KeyStore.getDefaultType())
        trustStore.load(null, null)
        trustStore.setCertificateEntry("relay-ca", relayCaCert)
        trustStore.setCertificateEntry("test-ca", ca.caCert)
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(trustStore)

        val ctx = SSLContext.getInstance("TLS")
        ctx.init(kmf.keyManagers, tmf.trustManagers, null)
        return ctx
    }

    private fun buildTrustingSslContext(): Pair<SSLContext, X509TrustManager> {
        val trustStore = KeyStore.getInstance(KeyStore.getDefaultType())
        trustStore.load(null, null)
        trustStore.setCertificateEntry("ca", ca.caCert)
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(trustStore)
        val tm = tmf.trustManagers.first { it is X509TrustManager } as X509TrustManager
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(null, arrayOf(tm), null)
        return ctx to tm
    }

    private fun <T> assertSuccess(result: RelayApiResult<T>): T = when (result) {
        is RelayApiResult.Success -> result.data
        is RelayApiResult.Error -> fail(
            "Relay returned error: status=${result.statusCode} message=${result.message}"
        )
        is RelayApiResult.RateLimited -> fail(
            "Relay rate-limited: retryAfter=${result.retryAfterSeconds}"
        )
        is RelayApiResult.NetworkError -> fail("Network error: ${result.cause}")
    }

    private fun parsePemCert(pem: String): X509Certificate {
        val cf = CertificateFactory.getInstance("X.509")
        return cf.generateCertificate(ByteArrayInputStream(pem.toByteArray())) as X509Certificate
    }

    private fun assertPem(pem: String, label: String, fieldName: String) {
        assertTrue(pem.isNotBlank(), "$fieldName must be non-blank")
        assertTrue(
            pem.contains("-----BEGIN $label-----"),
            "$fieldName must contain BEGIN $label header, was:\n$pem"
        )
        assertTrue(
            pem.contains("-----END $label-----"),
            "$fieldName must contain END $label footer, was:\n$pem"
        )
    }

    private object Ipv4OnlyDns : Dns {
        override fun lookup(hostname: String): List<InetAddress> =
            listOf(InetAddress.getByName("127.0.0.1"))
    }
}

private const val KEYSTORE_PASS = "changeit"
