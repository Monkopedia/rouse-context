package com.rousecontext.app.integration

import com.rousecontext.api.IntegrationStateStore
import com.rousecontext.app.integration.harness.AppIntegrationTestHarness
import com.rousecontext.app.integration.harness.TestEchoMcpIntegration
import com.rousecontext.app.integration.harness.TestSecondMcpIntegration
import com.rousecontext.tunnel.CertificateStore
import com.rousecontext.work.IntegrationSecretsSynchronizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
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
 * Issue #274 — enabling a second integration after the device is already
 * provisioned MUST skip `/register/certs` (the cert is reused) and only push
 * the updated integration list via `/rotate-secret`.
 *
 * Regression guard: before the `CertProvisioningResult.AlreadyProvisioned`
 * short-circuit existed, enabling a second integration would re-issue a cert,
 * burning ACME quota and invalidating the device's existing client cert.
 *
 * Scenario:
 *  1. Provision device with two integrations configured.
 *  2. Enable the first integration via [IntegrationSetupViewModel] — pushes
 *     `[test]` to `/rotate-secret`. Cert was issued during provisioning so
 *     `/register/certs` is already at 1 from [provisionDevice].
 *  3. Enable the second integration via a fresh VM whose configured payload
 *     is `[test, test2]` — pushes both IDs.
 *  4. Assert `/register/certs` count unchanged, two rotate-secret calls
 *     observed, both secrets persisted in [CertificateStore].
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class EnableSecondIntegrationTest {

    private val harness = AppIntegrationTestHarness(
        integrationsFactory = { listOf(TestEchoMcpIntegration(), TestSecondMcpIntegration()) }
    )

    @Before
    fun setUp() {
        // IntegrationSetupViewModel coroutines dispatch on Dispatchers.Main;
        // Robolectric's main looper isn't pumped under runBlocking, so swap in
        // Unconfined like OnboardingHappyPathTest does.
        Dispatchers.setMain(Dispatchers.Unconfined)
        harness.start()
    }

    @After
    fun tearDown() {
        harness.stop()
        Dispatchers.resetMain()
    }

    @Test
    fun `enabling second integration reuses cert and pushes both secrets`() = runBlocking {
        val subdomain = harness.provisionDevice()
        val registerCertsAfterProvision = harness.fixture.registerCertsCalls()
        assertEquals(
            "baseline: /register/certs exactly once after provisioning",
            1,
            registerCertsAfterProvision
        )

        // Step 1: enable the first integration.
        harness.clearCapturedRotateSecretPayloads()
        harness.buildSetupViewModel(listOf(TestEchoMcpIntegration.ID))
            .runSetupToComplete(TestEchoMcpIntegration.ID)

        // Step 2: enable the second integration. The push payload now carries
        // both IDs — this is the production shape (AppModule binds
        // integrationIds to the full McpIntegration list).
        harness.buildSetupViewModel(listOf(TestEchoMcpIntegration.ID, TestSecondMcpIntegration.ID))
            .runSetupToComplete(TestSecondMcpIntegration.ID)

        // #274: /register/certs MUST NOT be called again when enabling further
        // integrations on an already-provisioned device.
        assertEquals(
            "/register/certs must not be called again when enabling a second integration " +
                "(AlreadyProvisioned short-circuit)",
            registerCertsAfterProvision,
            harness.fixture.registerCertsCalls()
        )

        // Exactly two rotate-secret calls: one per VM invocation.
        assertEquals(
            "expected two /rotate-secret calls (one per enable)",
            2,
            harness.fixture.rotateSecretCalls()
        )

        val captured = harness.capturedRotateSecretPayloads()
        assertEquals("captured payload count matches rotate-secret calls", 2, captured.size)
        assertEquals(
            "first rotate-secret payload carries only the first integration",
            listOf(TestEchoMcpIntegration.ID),
            captured[0]
        )
        assertEquals(
            "second rotate-secret payload carries BOTH integrations",
            listOf(TestEchoMcpIntegration.ID, TestSecondMcpIntegration.ID),
            captured[1]
        )

        // Both secrets are persisted locally (the relay response mirrors what
        // it stored, and CertificateStore writes that wholesale).
        val certStore: CertificateStore = harness.koin.get()
        val stored = certStore.getIntegrationSecrets()
        assertNotNull("integration secrets must be persisted after enable flow", stored)
        requireNotNull(stored)
        assertTrue(
            "first integration secret persisted locally",
            stored.containsKey(TestEchoMcpIntegration.ID)
        )
        assertTrue(
            "second integration secret persisted locally",
            stored.containsKey(TestSecondMcpIntegration.ID)
        )
        assertEquals(
            "subdomain round-trips unchanged through the two-enable flow",
            subdomain,
            certStore.getSubdomain()
        )
    }

    /**
     * Issue #285 (part 2) — enabling an integration by flipping only the
     * state store (no VM, no onboarding flow) MUST still push the updated
     * set to the relay via [IntegrationSecretsSynchronizer]. This is the
     * symmetric case to the disable path in [DisableIntegrationTest]:
     * `IntegrationManageViewModel.reEnable()` just calls
     * `setUserEnabled(id, true)` with no other work.
     */
    @Test
    fun `state-store-only enable flip fires auto-push`() = runBlocking {
        harness.provisionDevice()

        // DataStore-backed integration state persists across tests in the
        // same JVM (Robolectric retains filesDir between tests in a class),
        // so reset both to false first. This makes the flip below a real
        // state transition regardless of what a previous test left behind.
        val stateStore: IntegrationStateStore = harness.koin.get()
        stateStore.setUserEnabled(TestEchoMcpIntegration.ID, false)
        stateStore.setUserEnabled(TestSecondMcpIntegration.ID, false)

        // Baseline: enable echo via the VM (covers the onboarding path,
        // which also provisions certs). After this the synchronizer's
        // lastPushedSet matches `{echo}` because the cert store holds
        // exactly that secret set.
        harness.clearCapturedRotateSecretPayloads()
        stateStore.setUserEnabled(TestEchoMcpIntegration.ID, true)
        harness.buildSetupViewModel(listOf(TestEchoMcpIntegration.ID))
            .runSetupToComplete(TestEchoMcpIntegration.ID)

        val synchronizer: IntegrationSecretsSynchronizer = harness.koin.get()
        // Wait for the synchronizer to register `{echo}` as its last-pushed
        // set (either by skip-because-in-sync or by its own push). We use
        // a short relative window here — the VM push + debounce resolve
        // within a few hundred ms under the fixture.
        withTimeout(SYNC_SETTLE_TIMEOUT_MS) {
            while (synchronizer.successfulPushCount.value == 0 &&
                harness.fixture.rotateSecretCalls() == 0
            ) {
                kotlinx.coroutines.delay(25L)
            }
            kotlinx.coroutines.delay(SYNC_QUIESCE_MS)
        }
        val baselineRotateCalls = harness.fixture.rotateSecretCalls()
        val baselinePushCount = synchronizer.successfulPushCount.value

        // User enables the second integration via Settings — only path
        // taken is the state-store flip; no VM, no cert-provisioning.
        stateStore.setUserEnabled(TestSecondMcpIntegration.ID, true)

        // Wait for the synchronizer to push the new set.
        withTimeout(AUTO_PUSH_TIMEOUT_MS) {
            synchronizer.successfulPushCount.first { it > baselinePushCount }
        }

        assertEquals(
            "synchronizer auto-push lands as an additional rotate-secret call",
            baselineRotateCalls + 1,
            harness.fixture.rotateSecretCalls()
        )

        val captured = harness.capturedRotateSecretPayloads()
        assertEquals(
            "state-store-only enable auto-push payload carries both integrations",
            listOf(TestEchoMcpIntegration.ID, TestSecondMcpIntegration.ID),
            captured.last()
        )
    }

    private companion object {
        const val AUTO_PUSH_TIMEOUT_MS = 30_000L
        const val SYNC_SETTLE_TIMEOUT_MS = 5_000L
        const val SYNC_QUIESCE_MS = 1_500L
    }
}
