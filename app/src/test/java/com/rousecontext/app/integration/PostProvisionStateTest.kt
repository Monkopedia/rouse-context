package com.rousecontext.app.integration

import com.rousecontext.app.integration.harness.AppIntegrationTestHarness
import com.rousecontext.tunnel.CertificateStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Post-provision invariants for [CertificateStore] after the full
 * onboarding + cert-provisioning round trip.
 *
 * Deliberately lightweight — the purpose is to fail loudly the moment
 * any single output of the provisioning flow starts silently dropping
 * (e.g. a refactor that stops persisting `relayCaCert`, which would
 * cascade into outer-mTLS handshake failures during subsequent tunnel
 * sessions). Higher-level scenarios (onboarding happy path, rotation,
 * etc.) already cover the end-to-end effects; this one checks the
 * minimum persistent state per issue #251.
 */
@RunWith(RobolectricTestRunner::class)
class PostProvisionStateTest {

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
    fun `provisioning persists all three cert artifacts and the relay-assigned subdomain`() =
        runBlocking {
            val subdomain = harness.provisionDevice()
            val certStore: CertificateStore = harness.koin.get()

            assertNotNull(
                "server cert must be non-null after provisioning",
                certStore.getCertificate()
            )
            assertNotNull(
                "client cert must be non-null after provisioning",
                certStore.getClientCertificate()
            )
            assertNotNull(
                "relay CA cert must be non-null after provisioning " +
                    "(required for outer-mTLS chain validation on tunnel establish)",
                certStore.getRelayCaCert()
            )
            assertEquals(
                "persisted subdomain must match the one the relay assigned",
                subdomain,
                certStore.getSubdomain()
            )
        }
}
