package com.rousecontext.tunnel.integration

import com.rousecontext.tunnel.CsrGenerator
import com.rousecontext.tunnel.CsrResult
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
import java.security.KeyFactory
import java.security.KeyStore
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.Dns
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

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
 *   cd relay && cargo build
 */
@Tag("integration")
class CertRotationIntegrationTest {

    companion object {
        private const val RELAY_HOSTNAME = "relay.test.internal"
        private const val BASE_DOMAIN = "relay.test.internal"
        private const val DUMMY_FIREBASE_TOKEN = "test-firebase-token-rotation"
        private const val DUMMY_FCM_TOKEN = "test-fcm-token-rotation"
        private const val SESSION_REGISTRATION_DELAY_MS = 500L
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
            "Relay binary not found. Build with: cd relay && cargo build"
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
        relayManager = TestRelayManager(
            tempDir,
            RELAY_HOSTNAME,
            deviceCaPaths = deviceCaKeyFile to deviceCaCertFile,
            disableFirebaseVerification = true,
            baseDomainOverride = BASE_DOMAIN
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

            val regCsr = CsrGenerator().generate(commonName = fqdn)
            val initialCerts = assertSuccess(
                api.registerCerts(csrPem = regCsr.csrPem, firebaseToken = DUMMY_FIREBASE_TOKEN)
            )

            // --- Step 2: Generate renewal CSR with fresh ECDSA P-256 keys ---
            val renewCsr = CsrGenerator().generate(commonName = fqdn)

            // --- Step 3: Sign renewal CSR DER with the registration private key ---
            val signature = signCsrDer(regCsr, renewCsr.csrDer)

            // --- Step 4: Call renewWithMtls against the real relay ---
            val renewedCerts = assertSuccess(
                api.renewWithMtls(
                    csrPem = renewCsr.csrPem,
                    subdomain = subdomain,
                    signature = signature
                )
            )

            // --- Step 5: Assert the relay returned a NEW cert triple ---
            // client_cert must differ (signed over new CSR with new public key)
            assertNotEquals(
                initialCerts.clientCert,
                renewedCerts.clientCert,
                "Renewed client_cert must differ from the original"
            )
            // relay_ca_cert may or may not differ (same CA), but must be present
            assertTrue(
                renewedCerts.relayCaCert.isNotBlank(),
                "Renewed relay_ca_cert must be non-blank"
            )
            assertPem(renewedCerts.clientCert, "CERTIFICATE", "renewed client_cert")
            assertPem(renewedCerts.relayCaCert, "CERTIFICATE", "renewed relay_ca_cert")

            // --- Step 6: Build an mTLS SSLContext from the renewed client cert ---
            val renewedSslContext = buildMtlsSslContextFromPem(
                clientCertPem = renewedCerts.clientCert,
                privateKeyPem = renewCsr.privateKeyPem,
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

            // Allow session registration on the relay side
            delay(SESSION_REGISTRATION_DELAY_MS)

            // Clean disconnect
            tunnelClient.disconnect()
        }

    /**
     * Second renewal uses the *original* registration private key to sign
     * (the relay does not rotate the stored public key on renewal). Verifies
     * that consecutive renewals work and each produces a distinct client cert.
     */
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
        val regCsr = CsrGenerator().generate(commonName = fqdn)
        assertSuccess(
            api.registerCerts(
                csrPem = regCsr.csrPem,
                firebaseToken = DUMMY_FIREBASE_TOKEN + "-double"
            )
        )

        // First renewal -- signed with the original registration key
        val renewCsr1 = CsrGenerator().generate(commonName = fqdn)
        val sig1 = signCsrDer(regCsr, renewCsr1.csrDer)
        val renewed1 = assertSuccess(
            api.renewWithMtls(
                csrPem = renewCsr1.csrPem,
                subdomain = reg.subdomain,
                signature = sig1
            )
        )

        // Second renewal -- also signed with the original registration key
        val renewCsr2 = CsrGenerator().generate(commonName = fqdn)
        val sig2 = signCsrDer(regCsr, renewCsr2.csrDer)
        val renewed2 = assertSuccess(
            api.renewWithMtls(
                csrPem = renewCsr2.csrPem,
                subdomain = reg.subdomain,
                signature = sig2
            )
        )

        assertNotEquals(
            renewed1.clientCert,
            renewed2.clientCert,
            "Second renewal must produce a different client_cert"
        )
        assertPem(renewed2.clientCert, "CERTIFICATE", "second renewal client_cert")

        // Reconnect with second renewal certs
        val sslContext = buildMtlsSslContextFromPem(
            clientCertPem = renewed2.clientCert,
            privateKeyPem = renewCsr2.privateKeyPem,
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
        delay(SESSION_REGISTRATION_DELAY_MS)
        tunnelClient.disconnect()
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /**
     * Sign [renewCsrDer] with SHA256withECDSA using the private key from [registrationCsr].
     */
    private fun signCsrDer(registrationCsr: CsrResult, renewCsrDer: ByteArray): String {
        val pem = registrationCsr.privateKeyPem
        val header = "-----BEGIN PRIVATE KEY-----"
        val footer = "-----END PRIVATE KEY-----"
        val base64 = pem
            .substringAfter(header)
            .substringBefore(footer)
            .replace("\\s".toRegex(), "")
        val pkcs8 = Base64.getDecoder().decode(base64)
        val privateKey = KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(pkcs8))
        val signer = Signature.getInstance("SHA256withECDSA")
        signer.initSign(privateKey)
        signer.update(renewCsrDer)
        return Base64.getEncoder().encodeToString(signer.sign())
    }

    /**
     * Build an mTLS [SSLContext] from PEM strings returned by the relay.
     * The client cert + private key are loaded into a PKCS12 KeyStore;
     * the relay CA cert and the test CA cert are loaded as trust anchors.
     */
    @Suppress("LongMethod")
    private fun buildMtlsSslContextFromPem(
        clientCertPem: String,
        privateKeyPem: String,
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

        // Parse private key
        val keyHeader = "-----BEGIN PRIVATE KEY-----"
        val keyFooter = "-----END PRIVATE KEY-----"
        val keyBase64 = privateKeyPem
            .substringAfter(keyHeader)
            .substringBefore(keyFooter)
            .replace("\\s".toRegex(), "")
        val keyBytes = Base64.getDecoder().decode(keyBase64)
        val privateKey = KeyFactory.getInstance("EC")
            .generatePrivate(PKCS8EncodedKeySpec(keyBytes))

        // Build PKCS12 KeyStore with the client cert chain + private key
        val keyStore = KeyStore.getInstance("PKCS12")
        keyStore.load(null, null)
        keyStore.setKeyEntry(
            "device",
            privateKey,
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
