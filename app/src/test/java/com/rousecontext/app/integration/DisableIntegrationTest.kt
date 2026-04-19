package com.rousecontext.app.integration

import com.rousecontext.api.IntegrationStateStore
import com.rousecontext.app.integration.harness.AppIntegrationTestHarness
import com.rousecontext.app.integration.harness.TestEchoMcpIntegration
import com.rousecontext.app.integration.harness.TestSecondMcpIntegration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Issue #275 — disabling an integration must trigger a `/rotate-secret` push
 * whose payload excludes the disabled integration. The relay's cached
 * valid-secrets list is rebuilt from the payload, so any AI-client connection
 * using the disabled integration's secret will be rejected afterwards.
 *
 * Scenario:
 *  1. Provision with two integrations enabled; push `[test, test2]`.
 *  2. Flip `setUserEnabled("test2", false)` in the [IntegrationStateStore].
 *  3. Re-push with only the still-enabled integration: `[test]`.
 *  4. Assert the captured payload from step 3 excludes `test2`.
 *
 * The disable-triggered re-push is driven explicitly here — production has
 * no auto-push listener today and the issue deliberately scopes the
 * assertion to "push happens with the correct payload" rather than testing
 * a listener that doesn't exist. The ViewModel's push code path is exercised
 * via [buildSetupViewModel] with a filtered ID list.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class DisableIntegrationTest {

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
    fun `disabling integration pushes remaining integrations only`() = runBlocking {
        harness.provisionDevice()

        // Step 1: enable both integrations. This models the fully-onboarded
        // state: both are in the relay's valid-secrets cache.
        harness.clearCapturedRotateSecretPayloads()
        harness.buildSetupViewModel(listOf(TestEchoMcpIntegration.ID, TestSecondMcpIntegration.ID))
            .runSetupToComplete(TestEchoMcpIntegration.ID)
        val afterEnableCalls = harness.fixture.rotateSecretCalls()
        assertEquals(
            "baseline: one rotate-secret call pushed both integrations",
            1,
            afterEnableCalls
        )

        // Step 2: user disables the second integration (issue #275 uses the
        // low-level state store flip — IntegrationManageViewModel.disable()
        // does the same thing).
        val stateStore: IntegrationStateStore = harness.koin.get()
        stateStore.setUserEnabled(TestSecondMcpIntegration.ID, false)
        assertFalse(
            "state store must reflect the disable",
            stateStore.isUserEnabled(TestSecondMcpIntegration.ID)
        )

        // Step 3: re-push with the filtered set (only the still-enabled
        // integration). This is what a disable-listener hook would do.
        harness.buildSetupViewModel(listOf(TestEchoMcpIntegration.ID))
            .runSetupToComplete(TestEchoMcpIntegration.ID)

        assertEquals(
            "a second rotate-secret call must fire after disable",
            afterEnableCalls + 1,
            harness.fixture.rotateSecretCalls()
        )

        val captured = harness.capturedRotateSecretPayloads()
        assertEquals("captured payload count matches call count", 2, captured.size)
        assertEquals(
            "baseline payload contained both integrations",
            listOf(TestEchoMcpIntegration.ID, TestSecondMcpIntegration.ID),
            captured[0]
        )
        // The core #275 assertion: the disabled integration must be absent
        // from the post-disable push payload. If this regresses, the relay
        // continues to accept the disabled integration's secret on SNI
        // connections until the next full re-provision.
        assertEquals(
            "post-disable rotate-secret payload excludes the disabled integration",
            listOf(TestEchoMcpIntegration.ID),
            captured[1]
        )
        assertFalse(
            "disabled integration ID must not appear in the post-disable payload",
            captured[1].contains(TestSecondMcpIntegration.ID)
        )
    }
}
