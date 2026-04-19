package com.rousecontext.app.integration

import com.rousecontext.app.integration.harness.AppIntegrationTestHarness
import com.rousecontext.tunnel.CertProvisioningFlow
import com.rousecontext.tunnel.CertProvisioningResult
import com.rousecontext.tunnel.CertificateStore
import com.rousecontext.tunnel.OnboardingFlow
import com.rousecontext.tunnel.OnboardingResult
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Smoke test for [AppIntegrationTestHarness] (issue #250).
 *
 * The one real scenario this test covers:
 *  1. Harness boots — Koin starts with the real `appModule` + test
 *     overrides, relay subprocess comes up in `--test-mode`.
 *  2. `OnboardingFlow.execute(...)` registers the device and writes the
 *     subdomain to the real [CertificateStore] (Room-backed).
 *  3. `CertProvisioningFlow.execute(...)` runs the full CSR -> /register-certs
 *     round trip against the fixture relay.
 *  4. Counters visible via the test-mode admin show exactly one
 *     `/register/certs` call, and the real `CertificateStore` has both
 *     the server + client certificates persisted.
 *  5. Teardown cleanly stops Koin and the relay subprocess.
 *
 * Everything else — notification audit DB, session handler, tunnel state
 * machine, integration registry — is constructed lazily by Koin and NOT
 * exercised here. The fuller scenarios land in #251 / #252 / #253.
 *
 * Skips (via JUnit `Assume`) when the relay binary hasn't been built,
 * matching the rest of the integration tier.
 */
@RunWith(RobolectricTestRunner::class)
class HarnessSmokeTest {

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
    fun `harness boots and provisions one device cert end-to-end`() = runBlocking {
        // --- Sanity: fixture is up, Koin is live. ---
        assertEquals(
            "fixture should have seen zero registerCerts calls before the flow runs",
            0,
            harness.fixture.registerCertsCalls()
        )

        val certStore: CertificateStore = harness.koin.get()
        val onboardingFlow: OnboardingFlow = harness.koin.get()
        val certProvisioningFlow: CertProvisioningFlow = harness.koin.get()

        // --- Onboard the device: registers a subdomain with the relay. ---
        // `disableFirebaseVerification = true` at fixture startup means any
        // non-empty string is accepted as a Firebase ID token.
        val onboardResult = onboardingFlow.execute(
            firebaseToken = "smoke-firebase-token",
            fcmToken = "smoke-fcm-token"
        )
        assertTrue(
            "onboarding must succeed, got: $onboardResult",
            onboardResult is OnboardingResult.Success
        )
        val subdomain = (onboardResult as OnboardingResult.Success).subdomain
        assertEquals(
            "subdomain must round-trip through CertificateStore",
            subdomain,
            certStore.getSubdomain()
        )

        // --- Provision certs: CSR + /register/certs round trip. ---
        val certResult = certProvisioningFlow.execute(firebaseToken = "smoke-firebase-token")
        assertTrue(
            "cert provisioning must succeed, got: $certResult",
            certResult is CertProvisioningResult.Success
        )

        // --- Counters: exactly one /register/certs call. ---
        assertEquals(
            "fixture should report exactly one /register/certs call",
            1,
            harness.fixture.registerCertsCalls()
        )

        // --- Real CertificateStore now holds both the server + client certs. ---
        assertNotNull(
            "server certificate must be persisted by CertProvisioningFlow",
            certStore.getCertificate()
        )
        assertNotNull(
            "client certificate must be persisted by CertProvisioningFlow",
            certStore.getClientCertificate()
        )
    }
}
