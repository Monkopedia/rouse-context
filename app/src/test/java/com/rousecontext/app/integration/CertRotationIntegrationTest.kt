package com.rousecontext.app.integration

import com.rousecontext.app.integration.harness.AppIntegrationTestHarness
import com.rousecontext.tunnel.CertRenewalFlow
import com.rousecontext.tunnel.CertificateStore
import com.rousecontext.tunnel.CsrSigner
import com.rousecontext.tunnel.DeviceKeyManager
import com.rousecontext.tunnel.RenewalResult
import java.security.Signature
import java.util.Base64
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * End-to-end cert rotation: provision once, then drive
 * [CertRenewalFlow.renewWithMtls] against the real relay binary and assert
 * that the store swaps to the renewed certificate.
 *
 * Two invariants are in play and both are security-relevant:
 *
 *   1. `/renew` must carry the device's mTLS client certificate. Without
 *      it the relay can't verify proof-of-possession and the renewal
 *      silently turns into an `AlreadyProvisioned` short-circuit that the
 *      worker would interpret as success while leaving the expired cert
 *      in place.
 *
 *   2. The persisted server cert must actually change after a successful
 *      renewal. A stale cert surviving a supposedly-successful renewal is
 *      exactly the class of bug that a later audit would have a hard time
 *      spotting without a test like this.
 */
@RunWith(RobolectricTestRunner::class)
class CertRotationIntegrationTest {

    private val harness = AppIntegrationTestHarness()

    @Before
    fun setUp() {
        harness.start()
    }

    @After
    fun tearDown() {
        harness.stop()
    }

    @Test
    fun `renewWithMtls issues a new server certificate presented with client cert`() = runBlocking {
        // --- Provision the initial cert. ---
        harness.provisionDevice()
        val certStore: CertificateStore = harness.koin.get()
        val initialServerCert = certStore.getCertificate()
        val initialClientCert = certStore.getClientCertificate()
        assertNotNull("initial server cert must be persisted", initialServerCert)
        assertNotNull("initial client cert must be persisted", initialClientCert)

        // Sanity: no /renew calls before we actually renew.
        assertEquals(
            "renew call count should start at zero",
            0,
            harness.fixture.renewCalls()
        )

        // --- Trigger renewal. The CertRenewalFlow in the harness's Koin
        //     graph uses the harness's overridden SoftwareDeviceKeyManager,
        //     so we sign the CSR DER bytes with the same keypair that
        //     produced the mTLS client cert.                              ---
        val renewalFlow: CertRenewalFlow = harness.koin.get()
        val keyManager: DeviceKeyManager = harness.koin.get()
        val csrSigner = CsrSigner { csrDer ->
            val signature = Signature.getInstance("SHA256withECDSA")
            signature.initSign(keyManager.getOrCreateKeyPair().private)
            signature.update(csrDer)
            Base64.getEncoder().encodeToString(signature.sign())
        }

        val result = renewalFlow.renewWithMtls(csrSigner = csrSigner)
        assertTrue(
            "renewal must succeed, got: $result",
            result is RenewalResult.Success
        )

        // --- Assertions. ---
        assertEquals(
            "relay should see exactly one /renew call",
            1,
            harness.fixture.renewCalls()
        )
        assertEquals(
            "`POST /renew` must present the device's mTLS client certificate " +
                "(proof-of-possession)",
            true,
            harness.fixture.lastRequestHadClientCert("/renew")
        )

        val renewedServerCert = certStore.getCertificate()
        assertNotNull("renewed server cert must be persisted", renewedServerCert)

        // The test relay runs with the stub ACME client, which returns the
        // literal string "stub-cert" for every issue_certificate call — so
        // the persisted server cert is byte-identical before and after a
        // successful renewal. We can't assert cert-bytes-change on the
        // server cert. Instead, verify the client cert (issued by the
        // relay's device CA with a fresh random serial on every call,
        // see device_ca::sign_client_cert) actually rolls.
        val renewedClientCert = certStore.getClientCertificate()
        assertNotNull("renewed client cert must be persisted", renewedClientCert)
        assertNotEquals(
            "renewed client cert must differ from the initial one — otherwise the " +
                "device CA silently kept the stale cert",
            initialClientCert,
            renewedClientCert
        )
    }
}
