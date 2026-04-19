package com.rousecontext.app.integration

import com.rousecontext.app.integration.TunnelTestSupport.awaitState
import com.rousecontext.app.integration.harness.AppIntegrationTestHarness
import com.rousecontext.app.integration.harness.TestEchoMcpIntegration
import com.rousecontext.bridge.SessionHandler
import com.rousecontext.bridge.TunnelSessionManager
import com.rousecontext.tunnel.CertificateStore
import com.rousecontext.tunnel.TunnelClientImpl
import com.rousecontext.tunnel.TunnelState
import java.security.cert.X509Certificate
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Proof that BouncyCastle JSSE (issue #262) restores SNI emission from the
 * synthetic AI client, so the relay's SNI-routed passthrough actually
 * reaches the device. Before this fix, Robolectric's default Conscrypt
 * provider silently dropped the SNI extension from every ClientHello and
 * the relay rejected every AI-client connection with `no valid SNI`.
 *
 * ## What this test proves
 *
 * 1. The synthetic AI client's outbound `ClientHello` contains the SNI
 *    extension set via [javax.net.ssl.SSLParameters.serverNames].
 * 2. The relay (Rust / Rustls) parses the SNI, classifies it as
 *    `DevicePassthrough`, and attempts to forward the connection onto
 *    the device's mux session. The test-mode hook in
 *    `relay/src/main.rs::handle_connection` records the routed SNI into
 *    [com.rousecontext.tunnel.integration.TestRelayManager.routedPassthroughSnis].
 * 3. The real device-side [TunnelClientImpl] + [TunnelSessionManager] +
 *    [SessionHandler] from the production Koin graph are wired up and
 *    receive the inbound mux stream (the stream handshakes the inner
 *    TLS session before hitting MCP routing).
 *
 * ## What this test does NOT exercise
 *
 * - The inner TLS handshake between the AI client and the device's
 *   `CertStoreTlsCertProvider`. The test-mode relay's stub ACME returns
 *   the literal string `"stub-cert"` as the device's server cert (see
 *   [StubAcme] in `relay/src/main.rs`), which is not a parseable X.509
 *   cert — the device cannot complete a server-side TLS handshake in
 *   harness mode. The AI client's handshake therefore fails after the
 *   relay has already routed (and recorded) the SNI. That failure is
 *   swallowed; the routed-SNI assertion is what matters for #262.
 * - MCP JSON-RPC round-trips. Those are exercised by
 *   [ToolCallHappyPathTest] and siblings via the loopback
 *   [McpTestDriver], which bypasses the relay/TLS path entirely. Those
 *   scenarios stay as-is per #262's "leave the loopback version alone
 *   if you add a new SNI test" guidance.
 */
@RunWith(RobolectricTestRunner::class)
class ToolCallViaSniPassthroughTest {

    private val harness = AppIntegrationTestHarness(
        integrationsFactory = { listOf(TestEchoMcpIntegration()) }
    )
    private lateinit var tunnelScope: CoroutineScope
    private var tunnel: TunnelClientImpl? = null
    private var sessionManager: TunnelSessionManager? = null

    @Before
    fun setUp() {
        harness.start()
        tunnelScope = TunnelTestSupport.newTunnelScope()
    }

    @After
    fun tearDown() {
        sessionManager?.stop()
        sessionManager = null
        runBlocking { tunnel?.disconnect() }
        tunnel = null
        tunnelScope.cancel()
        harness.stop()
    }

    @Test
    fun `synthetic AI client ClientHello carries SNI and reaches relay passthrough`() =
        runBlocking {
            val subdomain = harness.provisionDevice()
            harness.enableIntegrations(listOf(TestEchoMcpIntegration.ID))

            // Bring the device-to-relay tunnel up and wire inbound mux streams
            // through the production SessionHandler. Without a live mux
            // session the relay routes passthrough attempts straight to
            // fcm-wake + timeout instead of recording a routed-SNI.
            val connectedTunnel = TunnelTestSupport.buildTunnel(harness, tunnelScope)
                .also { tunnel = it }
            connectedTunnel.connect(TunnelTestSupport.tunnelUrl(harness))
            connectedTunnel.awaitState(
                TunnelState.CONNECTED,
                timeout = TunnelTestSupport.DEFAULT_CONNECT_TIMEOUT
            )

            val sessionHandler: SessionHandler = harness.koin.get()
            sessionManager = TunnelSessionManager(
                tunnelClient = connectedTunnel,
                sessionHandler = sessionHandler,
                scope = tunnelScope
            ).also { it.start() }

            // Per-integration secret is the prefix the relay uses to match the
            // SNI label to a specific integration on this device.
            val certStore: CertificateStore = harness.koin.get()
            val integrationSecret = certStore
                .getSecretForIntegration(TestEchoMcpIntegration.ID)
                ?: error(
                    "provisionDevice did not persist an integration secret for " +
                        "`${TestEchoMcpIntegration.ID}` — harness is mis-wired"
                )
            // SNI shape the relay expects for device-passthrough routes:
            //   {integrationSecret}.{subdomain}.{baseDomain}
            // where `baseDomain` is the relay_hostname with its leading
            // "relay." prefix stripped (see `RouteDecision::from_sni` in
            // `relay/src/sni.rs`). The harness runs with
            // `relay_hostname = "relay.test.local"`, so the base domain
            // is `test.local` — the SNI does NOT include "relay.".
            val sniHost = "$integrationSecret.$subdomain.test.local"

            // Open a TLS socket with the SNI extension set and force the
            // ClientHello onto the wire. The handshake will NOT complete
            // end-to-end (stub-cert; see kdoc). What matters for #262 is
            // that the bytes the relay sees contain the SNI extension —
            // the relay's routing handler records the SNI *before* any
            // inner handshake completes, so a failed downstream handshake
            // is fine.
            withContext(Dispatchers.IO) {
                openSniSocket(harness.relayPort, sniHost).use { socket ->
                    // `startHandshake()` flushes the ClientHello; wrap in
                    // runCatching because the inner TLS handshake the
                    // device attempts on the other side of the passthrough
                    // will fail against our trust-all context when the
                    // relay closes the connection with no valid device
                    // cert downstream.
                    runCatching { socket.startHandshake() }
                    // Give the relay a beat to finish parsing SNI and
                    // record it before we assert.
                    runCatching { socket.outputStream.flush() }
                }
                kotlinx.coroutines.delay(POST_HANDSHAKE_SETTLE_MS)
            }

            // The relay records the reconstructed hostname
            // `{secret}.{subdomain}.{base_domain_config}` rather than the
            // exact SNI string off the wire (the parsed router pipes the
            // prefix-stripped form through). In harness mode the config's
            // base_domain is `relay.test.local`, so the recorded form is
            // `{secret}.{subdomain}.relay.test.local`. The key invariant
            // for #262 is that a device-passthrough SNI was *routed at
            // all* — empty means Conscrypt silently stripped SNI and the
            // router classified every attempt as Reject/RelayApi.
            val routed = harness.fixture.routedPassthroughSnis()
            assertTrue(
                "relay must have recorded a routed-passthrough SNI — before the BC JSSE " +
                    "swap Conscrypt stripped SNI and the relay rejected every " +
                    "AI-client connection; saw: $routed",
                routed.isNotEmpty()
            )
            val expectedRecordedSni = "$integrationSecret.$subdomain.relay.test.local"
            assertEquals(
                "the routed SNI must name the integration and subdomain we targeted",
                expectedRecordedSni,
                routed.last()
            )
        }

    // --- helpers ---

    private fun openSniSocket(relayPort: Int, sniHost: String): SSLSocket {
        // Trust-all: we are not asserting anything about the inner TLS, only
        // about the relay's view of the outer ClientHello. Using a
        // trust-all TM keeps the test focused on SNI emission.
        val trustAll = arrayOf<TrustManager>(
            object : X509TrustManager {
                override fun checkClientTrusted(
                    chain: Array<out X509Certificate>?,
                    authType: String?
                ) = Unit

                override fun checkServerTrusted(
                    chain: Array<out X509Certificate>?,
                    authType: String?
                ) = Unit

                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            }
        )
        // `SSLContext.getInstance("TLS")` resolves to BouncyCastle JSSE
        // because [AppIntegrationTestHarness.start] inserted BC at priority 1.
        // That is the whole point of this test.
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(null, trustAll, null)
        val factory: SSLSocketFactory = ctx.socketFactory

        val socket = factory.createSocket("127.0.0.1", relayPort) as SSLSocket
        socket.soTimeout = HANDSHAKE_TIMEOUT_MS
        val params = socket.sslParameters
        params.serverNames = listOf(SNIHostName(sniHost))
        socket.sslParameters = params
        return socket
    }

    private companion object {
        private const val HANDSHAKE_TIMEOUT_MS = 10_000
        private const val POST_HANDSHAKE_SETTLE_MS = 500L
    }
}
