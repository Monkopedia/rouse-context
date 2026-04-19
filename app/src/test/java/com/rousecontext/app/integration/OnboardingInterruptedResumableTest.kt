package com.rousecontext.app.integration

import com.rousecontext.app.integration.harness.AppIntegrationTestHarness
import com.rousecontext.app.state.DeviceRegistrationStatus
import com.rousecontext.tunnel.CertProvisioningFlow
import com.rousecontext.tunnel.CertProvisioningResult
import com.rousecontext.tunnel.CertificateStore
import com.rousecontext.tunnel.OnboardingFlow
import com.rousecontext.tunnel.OnboardingResult
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Mid-flow onboarding interruption is resumable (issue #272).
 *
 * Guards the #163 behaviour: if the client crashes between `/register`
 * (subdomain assigned and persisted) and `/register/certs` (certificate
 * provisioned), the next launch MUST NOT bounce the user back to the Welcome
 * screen and re-request a brand-new subdomain. Instead, the persisted
 * subdomain is reused and cert provisioning completes on its own.
 *
 * Flow:
 *   1. [OnboardingFlow.execute] only — the relay sees `/request-subdomain`
 *      and `/register`; cert provisioning is deliberately skipped so the
 *      on-disk state is exactly the shape a crash right before
 *      `/register/certs` would leave behind.
 *   2. [AppIntegrationTestHarness.restartKoinPreservingState] stands in
 *      for the process restart. The relay keeps running (Firestore record
 *      for the new subdomain survives) and `context.filesDir` keeps the
 *      subdomain file; Koin singletons + [com.rousecontext.tunnel.DeviceKeyManager]
 *      are rebuilt fresh.
 *   3. [DeviceRegistrationStatus] under the new graph auto-marks complete
 *      because the subdomain file is already there — the production `appScope`
 *      coroutine in `appModule` does the same check on boot (#243).
 *   4. [CertProvisioningFlow.execute] runs on the fresh graph and provisions
 *      against the stored subdomain. The relay-side
 *      `find_device_by_uid → subdomain` lookup finds the round-1 record and
 *      issues the cert against the freshly-minted keypair (the relay's PoP
 *      check is gated on `record.public_key` being non-empty, which only
 *      happens after `/register/certs` has landed once — exactly the
 *      invariant that makes post-crash recovery work without a signed retry).
 *
 * Assertions nail the three pieces of the resume contract in one shot:
 *   - `/register` call count stays at exactly 1 across the restart (no
 *     re-onboarding retry storm).
 *   - `/register/certs` happens exactly once, on the second run.
 *   - The subdomain persisted by round 1 round-trips through the
 *     [CertificateStore] on round 2 unchanged.
 *   - All three cert blobs (ACME server cert, relay CA client cert, relay
 *     CA cert) land in the store on round 2 — proving the resume actually
 *     completed provisioning.
 */
@RunWith(RobolectricTestRunner::class)
class OnboardingInterruptedResumableTest {

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
    fun `resume after crash between register and register-certs reuses subdomain`() = runBlocking {
        // Round 1: onboarding only (simulated crash before /register/certs).
        val onboardedSubdomain = runOnboardingOnly()
        assertFirstRunCountsAreOneAndCertsZero()
        val beforeRestartStore: CertificateStore = harness.koin.get()
        assertEquals(
            "subdomain must be persisted before the restart",
            onboardedSubdomain,
            beforeRestartStore.getSubdomain()
        )

        // Same relay, same filesDir; only Koin + DeviceKeyManager are fresh.
        harness.restartKoinPreservingState()

        runCertProvisioningOnResumedGraph()

        // Contract assertions: subdomain unchanged, no new /register hops,
        // /register/certs exactly once, store fully populated.
        val resumedStore: CertificateStore = harness.koin.get()
        assertResumeCallCounts()
        assertEquals(
            "subdomain in the store must be identical to the one /register returned",
            onboardedSubdomain,
            resumedStore.getSubdomain()
        )
        assertStoreFullyProvisioned(resumedStore)
    }

    private suspend fun runOnboardingOnly(): String {
        val onboardingFlow: OnboardingFlow = harness.koin.get()
        val result = onboardingFlow.execute(
            firebaseToken = TEST_FIREBASE_TOKEN,
            fcmToken = TEST_FCM_TOKEN
        )
        assertTrue("first-run onboarding must succeed, got: $result", result is OnboardingResult.Success)
        return (result as OnboardingResult.Success).subdomain
    }

    private fun assertFirstRunCountsAreOneAndCertsZero() {
        assertEquals(
            "exactly one /register call in the first run",
            1,
            harness.fixture.admin!!.registerCalls()
        )
        assertEquals(
            "exactly one /request-subdomain call in the first run",
            1,
            harness.fixture.requestSubdomainCalls()
        )
        assertEquals(
            "no /register/certs call yet — the crash is simulated before that hop",
            0,
            harness.fixture.registerCertsCalls()
        )
    }

    private suspend fun runCertProvisioningOnResumedGraph() {
        // The production Koin graph launches a coroutine that flips
        // DeviceRegistrationStatus.markComplete() when the stored subdomain is
        // non-null (AppModule.kt). Under Dispatchers.Unconfined that launch
        // runs synchronously as part of `single { ... }` evaluation, but we
        // still await the flow to keep the assertion robust against a future
        // dispatcher swap.
        val registrationStatus: DeviceRegistrationStatus = harness.koin.get()
        withTimeout(REGISTRATION_STATUS_TIMEOUT_MS) { registrationStatus.awaitComplete() }

        val certProvisioningFlow: CertProvisioningFlow = harness.koin.get()
        val resumeResult = certProvisioningFlow.execute(firebaseToken = TEST_FIREBASE_TOKEN)
        assertTrue(
            "post-restart cert provisioning must succeed, got: $resumeResult",
            resumeResult is CertProvisioningResult.Success
        )
    }

    private fun assertResumeCallCounts() {
        assertEquals(
            "no second /register — the client must reuse the persisted subdomain (#163)",
            1,
            harness.fixture.admin!!.registerCalls()
        )
        assertEquals(
            "no second /request-subdomain either — that would imply a fresh onboarding",
            1,
            harness.fixture.requestSubdomainCalls()
        )
        assertEquals(
            "/register/certs must land exactly once, on the resumed run",
            1,
            harness.fixture.registerCertsCalls()
        )
    }

    private suspend fun assertStoreFullyProvisioned(store: CertificateStore) {
        assertNotNull("resumed run must persist the ACME server cert", store.getCertificate())
        assertNotNull("resumed run must persist the relay CA client cert", store.getClientCertificate())
        assertNotNull("resumed run must persist the relay CA cert", store.getRelayCaCert())
    }

    private companion object {
        // Keep the registration-status wait short — a correct graph flips the
        // flag synchronously on Dispatchers.Unconfined. A multi-second timeout
        // would only mask a regression in the eager `certStore.getSubdomain()`
        // check from AppModule.
        const val REGISTRATION_STATUS_TIMEOUT_MS = 5_000L
    }
}
