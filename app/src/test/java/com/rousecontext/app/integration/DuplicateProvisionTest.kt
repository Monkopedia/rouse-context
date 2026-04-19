package com.rousecontext.app.integration

import com.rousecontext.app.integration.harness.AppIntegrationTestHarness
import com.rousecontext.tunnel.CertProvisioningFlow
import com.rousecontext.tunnel.CertProvisioningResult
import com.rousecontext.tunnel.OnboardingFlow
import com.rousecontext.tunnel.OnboardingResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Regression guard for issue #238: concurrent callers of
 * [CertProvisioningFlow.execute] must not each race past the
 * already-provisioned check and issue a second `/register/certs`.
 *
 * Before #238 the Compose `LaunchedEffect` that drives integration setup
 * could re-run during recomposition, spawning a second `viewModelScope`
 * coroutine that duplicated the request. The relay started two concurrent
 * ACME orders for the same FQDN; GTS deduplicated the challenge and the
 * second order 400'd, leaving the device in a half-provisioned state.
 *
 * The fix is a [kotlinx.coroutines.sync.Mutex] inside the flow that
 * re-checks the already-provisioned short-circuit under the lock. This
 * test locks that behaviour in by firing two concurrent provisioning
 * requests against the real relay binary and asserting exactly one
 * `/register/certs` call lands.
 */
@RunWith(RobolectricTestRunner::class)
class DuplicateProvisionTest {

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
    fun `concurrent provisioning calls collapse to one register-certs request`() = runBlocking {
        // --- Onboard first; provisioning requires a subdomain. ---
        val onboardingFlow: OnboardingFlow = harness.koin.get()
        val onboardResult = onboardingFlow.execute(
            firebaseToken = TEST_FIREBASE_TOKEN,
            fcmToken = TEST_FCM_TOKEN
        )
        assertTrue(
            "onboarding must succeed, got: $onboardResult",
            onboardResult is OnboardingResult.Success
        )

        assertEquals(
            "no /register/certs calls should have landed yet",
            0,
            harness.fixture.registerCertsCalls()
        )

        // --- Race two concurrent provisioning calls on a real dispatcher. ---
        // Launch on Dispatchers.IO so both coroutines actually run in
        // parallel (the default single-thread test dispatcher would
        // serialise them and the mutex would never be contended).
        val certProvisioningFlow: CertProvisioningFlow = harness.koin.get()
        val results = coroutineScope {
            val a = async(Dispatchers.IO) {
                certProvisioningFlow.execute(firebaseToken = TEST_FIREBASE_TOKEN)
            }
            val b = async(Dispatchers.IO) {
                certProvisioningFlow.execute(firebaseToken = TEST_FIREBASE_TOKEN)
            }
            awaitAll(a, b)
        }

        // --- Exactly one /register/certs call visible on the relay side. ---
        assertEquals(
            "CertProvisioningFlow.mutex + already-provisioned short-circuit must " +
                "collapse concurrent callers to one /register/certs request " +
                "(regression guard for #238). Results: $results",
            1,
            harness.fixture.registerCertsCalls()
        )

        // --- One caller ran the CSR + storage path (`Success`), the other
        //     saw the already-persisted cert and short-circuited.           ---
        val successCount = results.count { it is CertProvisioningResult.Success }
        val alreadyProvisionedCount = results.count {
            it is CertProvisioningResult.AlreadyProvisioned
        }
        assertEquals(
            "Exactly one caller should have performed the real CSR round trip. " +
                "Got results: $results",
            1,
            successCount
        )
        assertEquals(
            "Exactly one caller should have short-circuited as AlreadyProvisioned. " +
                "Got results: $results",
            1,
            alreadyProvisionedCount
        )
    }
}
