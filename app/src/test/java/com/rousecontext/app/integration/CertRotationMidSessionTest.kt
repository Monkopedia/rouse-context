package com.rousecontext.app.integration

import com.rousecontext.app.integration.TunnelTestSupport.awaitState
import com.rousecontext.app.integration.harness.AppIntegrationTestHarness
import com.rousecontext.tunnel.CertRenewalFlow
import com.rousecontext.tunnel.CertificateStore
import com.rousecontext.tunnel.CsrSigner
import com.rousecontext.tunnel.DeviceKeyManager
import com.rousecontext.tunnel.RenewalResult
import com.rousecontext.tunnel.TunnelState
import java.security.Signature
import java.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Regression guard for #237-class bugs: running [CertRenewalFlow] against a
 * *live* tunnel must not knock the existing WebSocket off the relay, and the
 * renewal must still present a valid client cert on `/renew`.
 *
 * The assertions line up with the #253 spec:
 *  - Existing tunnel stays [TunnelState.CONNECTED] across the renewal.
 *  - Relay sees exactly one `/renew` call with a valid mTLS client cert.
 *  - Client cert on disk has actually rolled (server cert is stub-cert
 *    byte-identical, documented in [CertRotationIntegrationTest]).
 *
 * #262 note: no synthetic AI client, all assertions are device-side.
 */
@RunWith(RobolectricTestRunner::class)
class CertRotationMidSessionTest {

    private val harness = AppIntegrationTestHarness()
    private lateinit var tunnelScope: CoroutineScope

    @Before
    fun setUp() {
        harness.start()
        tunnelScope = TunnelTestSupport.newTunnelScope()
    }

    @After
    fun tearDown() {
        tunnelScope.cancel()
        harness.stop()
    }

    @Test
    fun `renewWithMtls while tunnel is CONNECTED does not tear it down`() = runBlocking {
        harness.provisionDevice()

        val certStore: CertificateStore = harness.koin.get()
        val initialClientCert = certStore.getClientCertificate()
        assertEquals(
            "renew call count starts at zero",
            0,
            harness.fixture.renewCalls()
        )

        val tunnel = TunnelTestSupport.buildTunnel(harness, tunnelScope)
        tunnel.connect(TunnelTestSupport.tunnelUrl(harness))
        tunnel.awaitState(
            TunnelState.CONNECTED,
            timeout = TunnelTestSupport.DEFAULT_CONNECT_TIMEOUT
        )

        // --- Trigger renewal while the tunnel is live. ---
        val renewalFlow: CertRenewalFlow = harness.koin.get()
        val keyManager: DeviceKeyManager = harness.koin.get()
        val csrSigner = CsrSigner { csrDer ->
            val signature = Signature.getInstance("SHA256withECDSA")
            signature.initSign(keyManager.getOrCreateKeyPair().private)
            signature.update(csrDer)
            Base64.getEncoder().encodeToString(signature.sign())
        }

        val renewalResult = renewalFlow.renewWithMtls(csrSigner = csrSigner)
        assertTrue(
            "renewal must succeed, got: $renewalResult",
            renewalResult is RenewalResult.Success
        )

        // --- Tunnel must still be CONNECTED. ---
        //
        // Renewal goes over a fresh /renew HTTPS request and does NOT touch
        // the device's WebSocket to the relay. If the tunnel flipped out of
        // CONNECTED here, something in the cert rotation path would have
        // side-effected the TunnelClient's socket — exactly the class of
        // regression this test guards against.
        assertEquals(
            "tunnel must stay CONNECTED across cert renewal (mid-session)",
            TunnelState.CONNECTED,
            tunnel.state.value
        )

        // --- Renewal counter + mTLS proof-of-possession. ---
        assertEquals(
            "relay should see exactly one /renew call",
            1,
            harness.fixture.renewCalls()
        )
        assertEquals(
            "/renew must carry the device's mTLS client certificate",
            true,
            harness.fixture.lastRequestHadClientCert("/renew")
        )

        // --- Client cert must have rolled. ---
        // The test relay runs with a stub ACME client (returns "stub-cert"
        // verbatim), so the server cert is byte-identical across renewals.
        // The client cert, signed by the device-CA, does roll. Verifying
        // that gives us real coverage of the new-cert-surfaces path.
        val renewedClientCert = certStore.getClientCertificate()
        assertNotEquals(
            "renewed client cert must differ from the initial one — otherwise " +
                "cert rotation silently kept the stale cert",
            initialClientCert,
            renewedClientCert
        )
    }
}
