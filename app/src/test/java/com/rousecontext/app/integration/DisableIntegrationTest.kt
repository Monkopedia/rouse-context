package com.rousecontext.app.integration

import com.rousecontext.api.IntegrationStateStore
import com.rousecontext.app.integration.harness.AppIntegrationTestHarness
import com.rousecontext.app.integration.harness.TestEchoMcpIntegration
import com.rousecontext.app.integration.harness.TestSecondMcpIntegration
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
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Issue #285 (part 2) — disabling an integration in [IntegrationStateStore]
 * must auto-trigger a `/rotate-secret` push whose payload excludes the
 * disabled integration. Before part 2, the UI flipped a local DataStore
 * flag but no listener observed the flip, so the relay continued to accept
 * the disabled integration's secret indefinitely.
 *
 * The core test: flip `setUserEnabled("test2", false)` with no VM
 * involvement, then assert that an auto-push lands at the relay with
 * `[test]` only. That push is driven by [IntegrationSecretsSynchronizer],
 * wired by `createdAtStart = true` in the production Koin module.
 *
 * Regression guard: if a future refactor drops the synchronizer (or its
 * debounce/retry logic stops firing on flips), this test wedges at
 * `awaitNextPush` and times out.
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
    fun `disabling integration auto-pushes remaining integrations only`() = runBlocking {
        harness.provisionDevice()

        // Step 1: bring the device to baseline — both integrations enabled
        // in the state store AND synced to the relay. DataStore-backed
        // integration state persists across tests in the same JVM, so
        // reset first, then flip both to true before the VM runs. That
        // ensures the synchronizer's view of the enabled set matches what
        // the VM pushes, leaving exactly one `/rotate-secret` call on
        // the wire (the VM's push; the synchronizer's "already synced"
        // short-circuit absorbs its would-be duplicate).
        val stateStore: IntegrationStateStore = harness.koin.get()
        stateStore.setUserEnabled(TestEchoMcpIntegration.ID, false)
        stateStore.setUserEnabled(TestSecondMcpIntegration.ID, false)
        stateStore.setUserEnabled(TestEchoMcpIntegration.ID, true)
        stateStore.setUserEnabled(TestSecondMcpIntegration.ID, true)
        harness.clearCapturedRotateSecretPayloads()
        harness.buildSetupViewModel(listOf(TestEchoMcpIntegration.ID, TestSecondMcpIntegration.ID))
            .runSetupToComplete(TestEchoMcpIntegration.ID)
        val afterEnableCalls = harness.fixture.rotateSecretCalls()
        assertEquals(
            "baseline: exactly one rotate-secret call (VM push; synchronizer skipped as in-sync)",
            1,
            afterEnableCalls
        )

        val synchronizer: IntegrationSecretsSynchronizer = harness.koin.get()
        // Wait for the synchronizer's debounce to elapse so any auto-push
        // triggered by the baseline flips completes before we disable.
        kotlinx.coroutines.delay(SYNC_QUIESCE_MS)
        val baselinePushCount = synchronizer.successfulPushCount.value

        // Step 2: flip the low-level store. No VM involvement — this is
        // the path production's `IntegrationManageViewModel.disable()`
        // takes, just without the VM wrapper.
        stateStore.setUserEnabled(TestSecondMcpIntegration.ID, false)
        assertFalse(
            "state store must reflect the disable",
            stateStore.isUserEnabled(TestSecondMcpIntegration.ID)
        )

        // Step 3: wait for the synchronizer's auto-push to land.
        withTimeout(AUTO_PUSH_TIMEOUT_MS) {
            synchronizer.successfulPushCount.first { it > baselinePushCount }
        }

        assertEquals(
            "a second rotate-secret call must fire after the disable flip",
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
        // Core #285 assertion: the auto-push after disable must exclude
        // the disabled integration. If this regresses, the relay keeps
        // accepting the disabled integration's secret on SNI connections
        // indefinitely.
        assertEquals(
            "post-disable auto-push payload excludes the disabled integration",
            listOf(TestEchoMcpIntegration.ID),
            captured[1]
        )
        assertFalse(
            "disabled integration ID must not appear in the auto-push payload",
            captured[1].contains(TestSecondMcpIntegration.ID)
        )
    }

    private companion object {
        // Debounce is 500ms + scheduling + mTLS + relay round-trip; 30s is
        // loose enough to absorb Robolectric/OkHttp startup on a slow CI
        // box without masking a real hang.
        const val AUTO_PUSH_TIMEOUT_MS = 30_000L

        // Minimum wait for the synchronizer's debounce + certStore peek
        // to settle after a baseline flip sequence so assertions downstream
        // observe a quiescent baseline. 800ms = debounce (500ms) + margin.
        const val SYNC_QUIESCE_MS = 800L
    }
}
