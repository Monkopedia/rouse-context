package com.rousecontext.app.integration

import com.rousecontext.app.integration.harness.AppIntegrationTestHarness
import com.rousecontext.tunnel.RelayApiResult
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Regression guard for issue #237: `RelayApiClient.updateSecrets`
 * (`POST /rotate-secret`) MUST present the device mTLS client certificate.
 *
 * Before #237 the shared OkHttp client was built without a KeyManager, so
 * every call to `/rotate-secret` came back `401 Valid client certificate
 * required` the moment onboarding finished.
 *
 * The fix routes every [com.rousecontext.tunnel.RelayApiClient] call through a
 * `FileMtlsCertSource` that reads the device client cert + key from the
 * [com.rousecontext.tunnel.CertificateStore] on each handshake. This test
 * locks that down end-to-end against the real relay binary.
 */
@RunWith(RobolectricTestRunner::class)
class PostProvisionMtlsTest {

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
    fun `updateSecrets presents mTLS client cert after provisioning`() = runBlocking {
        val subdomain = harness.provisionDevice()

        val result = harness.relayApiClient.updateSecrets(
            subdomain = subdomain,
            integrationIds = listOf("usage")
        )

        assertTrue(
            "updateSecrets must succeed (401 here would indicate missing client cert): $result",
            result is RelayApiResult.Success
        )

        assertEquals(
            "relay should see exactly one /rotate-secret call",
            1,
            harness.fixture.rotateSecretCalls()
        )

        assertEquals(
            "`POST /rotate-secret` must carry the device's mTLS client certificate " +
                "(regression guard for #237)",
            true,
            harness.fixture.lastRequestHadClientCert("/rotate-secret")
        )
    }
}
