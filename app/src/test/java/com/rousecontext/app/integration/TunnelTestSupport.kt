package com.rousecontext.app.integration

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.rousecontext.app.integration.harness.AppIntegrationTestHarness
import com.rousecontext.tunnel.DeviceKeyManager
import com.rousecontext.tunnel.TunnelClient
import com.rousecontext.tunnel.TunnelClientImpl
import com.rousecontext.tunnel.TunnelState
import com.rousecontext.tunnel.WebSocketFactory
import com.rousecontext.tunnel.WebSocketHandle
import com.rousecontext.tunnel.WebSocketListener
import java.io.File
import java.net.InetAddress
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509KeyManager
import javax.net.ssl.X509TrustManager
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okio.ByteString
import okio.ByteString.Companion.toByteString

/**
 * Resilience / reconnect scenario plumbing for batch C (#253).
 *
 * The harness used across batch A / B wires the production `TunnelClient` into
 * the Koin graph via `LazyWebSocketFactory`, which in turn uses the production
 * `MtlsWebSocketFactory`. That factory reads the client cert from filesDir
 * (fine — [provisionDevice] writes it there) but does NOT install a DNS
 * override for the fixture hostname (`relay.test.local`), trust the fixture
 * CA, or accept the loose loopback SAN. Wiring that override directly into
 * `appModule` would leak test behaviour into production.
 *
 * Instead, batch C's tests drive a dedicated [TunnelClientImpl] constructed
 * here with a test-friendly [WebSocketFactory]. The factory re-uses the exact
 * device-cert layout production persists (cert PEM + relay CA PEM in filesDir,
 * private key from [DeviceKeyManager]) so the regressions the test guards
 * against — half-open reconnect, cert-rotation survival, stream-failure
 * containment — still exercise the same TLS / mux code paths the production
 * tunnel does.
 *
 * Keepalive is accelerated (2s interval, 1s per-ping timeout, 3 misses) so
 * half-open detection takes ~9s instead of the production ~120s. Matches
 * `:core:tunnel:jvmTest/HalfOpenDetectionTest`.
 */
internal object TunnelTestSupport {

    private const val KEEPALIVE_INTERVAL_MS = 2_000L
    private const val KEEPALIVE_TIMEOUT_MS = 1_000L
    private const val KEEPALIVE_MAX_MISSES = 3

    /** Default CONNECTED-state assertion bound — generous for relay startup. */
    val DEFAULT_CONNECT_TIMEOUT: Duration = 15.seconds

    /**
     * Build a [TunnelClientImpl] wired against the fixture relay.
     *
     * Uses the harness's [AppIntegrationTestHarness.certificateAuthority] for
     * trust and reads the freshly-provisioned client cert from filesDir. Caller
     * must have run [provisionDevice] before constructing so the disk layout
     * is populated.
     *
     * [ownerScope] is the lifecycle scope for the tunnel's internal jobs — use
     * a scope owned by the test (cancelled in `@After`) so nothing leaks.
     */
    fun buildTunnel(
        harness: AppIntegrationTestHarness,
        ownerScope: CoroutineScope
    ): TunnelClientImpl {
        val context: Context = androidContext(harness)
        val deviceKeyManager: DeviceKeyManager = harness.koin.get()
        val fixtureCa: X509Certificate = harness.certificateAuthority.caCert

        val wsFactory = FixtureMtlsWebSocketFactory(
            context = context,
            deviceKeyManager = deviceKeyManager,
            fixtureCa = fixtureCa
        )
        return TunnelClientImpl(
            scope = ownerScope,
            webSocketFactory = wsFactory,
            keepaliveIntervalMillis = KEEPALIVE_INTERVAL_MS,
            keepaliveTimeoutMillis = KEEPALIVE_TIMEOUT_MS,
            keepaliveMaxMisses = KEEPALIVE_MAX_MISSES
        )
    }

    /** Build the wss:// URL the tunnel should connect to (fixture loopback). */
    fun tunnelUrl(harness: AppIntegrationTestHarness): String =
        "wss://relay.test.local:${harness.relayPort}/ws"

    /**
     * Spawn a fresh scope for the tunnel's jobs. Call [CoroutineScope.cancel]
     * on the returned scope in test teardown to clean up.
     */
    fun newTunnelScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Suspend until [target] is seen on [TunnelClient.state], with a timeout. */
    suspend fun TunnelClient.awaitState(target: TunnelState, timeout: Duration) {
        withTimeout(timeout) { state.first { it == target } }
    }

    @Suppress("UNUSED_PARAMETER")
    private fun androidContext(harness: AppIntegrationTestHarness): Context =
        ApplicationProvider.getApplicationContext<Application>()
}

/**
 * OkHttp-backed [WebSocketFactory] configured to reach the harness's fixture
 * relay.
 *
 * - Reads the provisioned client cert chain + relay CA from filesDir, exactly
 *   like production's [com.rousecontext.app.cert.MtlsWebSocketFactory]. The
 *   device private key comes from the harness's [DeviceKeyManager] override.
 * - Trusts the fixture CA (production trusts the JVM default store, which
 *   rejects the fixture's CN=Test CA root).
 * - DNS override maps the fixture hostname to 127.0.0.1, and a loose hostname
 *   verifier accepts the non-FQDN SAN the fixture cert uses. Collocated
 *   rationale in [AppIntegrationTestHarness]'s DNS / hostnameVerifier comments.
 */
private class FixtureMtlsWebSocketFactory(
    private val context: Context,
    private val deviceKeyManager: DeviceKeyManager,
    private val fixtureCa: X509Certificate
) : WebSocketFactory {

    override fun connect(url: String, listener: WebSocketListener): WebSocketHandle {
        val client = buildClient()
        val request = Request.Builder().url(url).build()
        val ws = client.newWebSocket(
            request,
            object : okhttp3.WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    listener.onOpen()
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    listener.onBinaryMessage(bytes.toByteArray())
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    listener.onClosing(code, reason)
                    webSocket.close(code, reason)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    listener.onFailure(t)
                }
            }
        )
        return OkHttpHandle(ws)
    }

    private fun buildClient(): OkHttpClient {
        val certChain = loadCertChain() ?: error(
            "FixtureMtlsWebSocketFactory: no client cert on disk — did you call provisionDevice()?"
        )
        val privateKey = deviceKeyManager.getOrCreateKeyPair().private
        val keyManager = DirectKeyManager(privateKey, certChain.toTypedArray())

        val trustStore = KeyStore.getInstance(KeyStore.getDefaultType())
        trustStore.load(null, null)
        trustStore.setCertificateEntry("fixture-ca", fixtureCa)
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(trustStore)
        val trustManager = tmf.trustManagers
            .first { it is X509TrustManager } as X509TrustManager

        val sslContext = SSLContext.getInstance("TLS").apply {
            init(arrayOf(keyManager), tmf.trustManagers, null)
        }

        return OkHttpClient.Builder()
            .sslSocketFactory(sslContext.socketFactory, trustManager)
            .hostnameVerifier { _, _ -> true }
            .dns { _ -> listOf(InetAddress.getByName("127.0.0.1")) }
            // Short ping interval keeps OkHttp-level keepalive snappy; the
            // mux-level keepalive is what actually drives state transitions.
            .pingInterval(1, TimeUnit.SECONDS)
            .build()
    }

    private fun loadCertChain(): List<X509Certificate>? {
        val certFile = File(context.filesDir, CLIENT_CERT_PEM_FILE)
        if (!certFile.exists()) return null
        return parsePemCertificates(certFile.readText()).takeIf { it.isNotEmpty() }
    }

    private fun parsePemCertificates(pem: String): List<X509Certificate> {
        val factory = CertificateFactory.getInstance("X.509")
        val certs = mutableListOf<X509Certificate>()
        val regex = Regex(
            "-----BEGIN CERTIFICATE-----(.+?)-----END CERTIFICATE-----",
            RegexOption.DOT_MATCHES_ALL
        )
        for (match in regex.findAll(pem)) {
            val base64 = match.groupValues[1].replace("\\s".toRegex(), "")
            val der = Base64.getDecoder().decode(base64)
            val cert = factory.generateCertificate(der.inputStream()) as X509Certificate
            certs.add(cert)
        }
        return certs
    }

    private companion object {
        const val CLIENT_CERT_PEM_FILE = "rouse_client_cert.pem"
    }
}

/**
 * Direct [X509KeyManager] that hands the SSL engine the [privateKey] + chain
 * without any intermediate keystore. Same rationale as production's
 * `DirectX509KeyManager`: hardware-backed keys can't be round-tripped through
 * a PKCS12 / BKS store.
 *
 * Tests use a software-backed EC key ([com.rousecontext.app.integration.harness
 * .SoftwareDeviceKeyManager]) but the same key manager shape works there too.
 */
private class DirectKeyManager(
    private val privateKey: PrivateKey,
    private val chain: Array<X509Certificate>
) : X509KeyManager {
    private val alias = "device"

    override fun chooseClientAlias(
        keyType: Array<out String>?,
        issuers: Array<out java.security.Principal>?,
        socket: java.net.Socket?
    ): String = alias

    override fun chooseServerAlias(
        keyType: String?,
        issuers: Array<out java.security.Principal>?,
        socket: java.net.Socket?
    ): String? = null

    override fun getCertificateChain(alias: String?): Array<X509Certificate> = chain

    override fun getClientAliases(
        keyType: String?,
        issuers: Array<out java.security.Principal>?
    ): Array<String> = arrayOf(alias)

    override fun getServerAliases(
        keyType: String?,
        issuers: Array<out java.security.Principal>?
    ): Array<String> = emptyArray()

    override fun getPrivateKey(alias: String?): PrivateKey = privateKey
}

private class OkHttpHandle(private val ws: WebSocket) : WebSocketHandle {
    override suspend fun sendBinary(data: ByteArray): Boolean = ws.send(data.toByteString())
    override suspend fun sendText(text: String): Boolean = ws.send(text)
    override suspend fun close(code: Int, reason: String) {
        ws.close(code, reason)
    }
}
