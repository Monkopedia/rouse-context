package com.rousecontext.app.integration

import com.rousecontext.api.IntegrationStateStore
import com.rousecontext.app.integration.harness.AppIntegrationTestHarness
import com.rousecontext.app.integration.harness.TestEchoMcpIntegration
import com.rousecontext.app.integration.harness.TestSecondMcpIntegration
import com.rousecontext.tunnel.CertificateStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Issue #276 — re-enabling a previously disabled integration must push the
 * integration back into the relay's valid-secrets cache.
 *
 * Unlike the initial-enable path, the integration already has a stored
 * secret: the relay's `/rotate-secret` handler is merge-missing, so when the
 * ID re-appears in the request it reuses the existing secret rather than
 * generating a fresh one. This test locks down that the device-side secret
 * is preserved verbatim across the disable/re-enable cycle.
 *
 * Scenario:
 *  1. Provision with two integrations configured.
 *  2. Enable both; capture the stored `test2` secret.
 *  3. Disable `test2`, push `[test]` only.
 *  4. Re-enable `test2`, push `[test, test2]`.
 *  5. Assert the final captured payload includes `test2`, the relay
 *     returned the SAME `test2` secret as the initial enable (merge-missing
 *     preserves it), and the device's local secret map matches.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ReEnableDisabledIntegrationTest {

    private val harness = AppIntegrationTestHarness(
        integrationsFactory = { listOf(TestEchoMcpIntegration(), TestSecondMcpIntegration()) }
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        harness.start()
    }

    @After
    fun tearDown() {
        harness.stop()
        Dispatchers.resetMain()
    }

    @Test
    fun `re-enabling previously disabled integration includes it in payload and reuses secret`() =
        runBlocking {
            harness.provisionDevice()

            // Step 1: enable both integrations (baseline state with two
            // integrations in the relay's valid-secrets map).
            harness.clearCapturedRotateSecretPayloads()
            harness.buildSetupViewModel(
                listOf(TestEchoMcpIntegration.ID, TestSecondMcpIntegration.ID)
            ).runSetupToComplete(TestEchoMcpIntegration.ID)

            val certStore: CertificateStore = harness.koin.get()
            val initialSecrets = certStore.getIntegrationSecrets()
            assertNotNull("baseline secrets map must be populated", initialSecrets)
            requireNotNull(initialSecrets)
            val initialSecondSecret = initialSecrets[TestSecondMcpIntegration.ID]
            assertNotNull(
                "second integration must have a secret after initial enable",
                initialSecondSecret
            )

            // Step 2: disable the second integration, push the reduced set.
            val stateStore: IntegrationStateStore = harness.koin.get()
            stateStore.setUserEnabled(TestSecondMcpIntegration.ID, false)
            harness.buildSetupViewModel(listOf(TestEchoMcpIntegration.ID))
                .runSetupToComplete(TestEchoMcpIntegration.ID)

            // Step 3: user re-enables; push with the full set again.
            stateStore.setUserEnabled(TestSecondMcpIntegration.ID, true)
            harness.buildSetupViewModel(
                listOf(TestEchoMcpIntegration.ID, TestSecondMcpIntegration.ID)
            ).runSetupToComplete(TestSecondMcpIntegration.ID)

            val captured = harness.capturedRotateSecretPayloads()
            assertEquals(
                "expected three /rotate-secret calls (enable, disable push, re-enable)",
                3,
                captured.size
            )
            assertEquals(
                "final (re-enable) rotate-secret payload must include both integrations",
                listOf(TestEchoMcpIntegration.ID, TestSecondMcpIntegration.ID),
                captured[2]
            )

            // Merge-missing: relay preserves the existing `test2` secret
            // rather than generating a fresh one on re-enable. Verifying
            // this pins down current design (issue #276 scope: "If existing
            // design reuses the prior stored secret, assert that").
            val finalSecrets = certStore.getIntegrationSecrets()
            assertNotNull("final secrets map must be populated", finalSecrets)
            requireNotNull(finalSecrets)
            assertTrue(
                "final secrets map must contain the re-enabled integration",
                finalSecrets.containsKey(TestSecondMcpIntegration.ID)
            )
            assertEquals(
                "relay's merge-missing behaviour must preserve the existing secret " +
                    "across a disable/re-enable cycle (regression guard for #276)",
                initialSecondSecret,
                finalSecrets[TestSecondMcpIntegration.ID]
            )
            assertEquals(
                "rotate-secret call count must match observed payloads",
                3,
                harness.fixture.rotateSecretCalls()
            )
        }
}
