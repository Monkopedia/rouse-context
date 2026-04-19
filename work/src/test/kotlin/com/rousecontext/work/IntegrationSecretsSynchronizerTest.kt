package com.rousecontext.work

import com.rousecontext.api.IntegrationStateStore
import com.rousecontext.api.McpIntegration
import com.rousecontext.mcp.core.McpServerProvider
import com.rousecontext.tunnel.CertificateStore
import com.rousecontext.tunnel.RelayApiClient
import com.rousecontext.tunnel.RelayApiResult
import com.rousecontext.tunnel.UpdateSecretsResponse
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Unit tests for [IntegrationSecretsSynchronizer].
 *
 * Drives the synchronizer with a fake [IntegrationStateStore] and a mocked
 * [RelayApiClient], asserts:
 *  - cold-start emission does NOT push (onboarding VM owns the first push).
 *  - flipping a single integration off triggers a rotate-secret push with
 *    the reduced set.
 *  - rapid consecutive flips are debounced into a single push.
 *  - duplicate sets (no net change) do not re-push.
 *  - transient failures are retried with exponential backoff.
 *  - success persists relay-issued secrets into [CertificateStore].
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class IntegrationSecretsSynchronizerTest {

    private lateinit var scope: CoroutineScope
    private lateinit var fakeStateStore: FakeIntegrationStateStore
    private lateinit var relayApiClient: RelayApiClient
    private lateinit var certStore: CertificateStore

    private val health = fakeIntegration("health")
    private val usage = fakeIntegration("usage")

    @Before
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        fakeStateStore = FakeIntegrationStateStore()
        relayApiClient = mockk()
        certStore = mockk {
            coEvery { getSubdomain() } returns "cool-penguin"
            // Default: relay is out of sync with the enabled set so the
            // synchronizer always pushes. Tests that need the "already
            // synced" branch stub getIntegrationSecrets explicitly.
            coEvery { getIntegrationSecrets() } returns null
            coEvery { storeIntegrationSecrets(any()) } just Runs
        }
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `cold-start emission does not push`() = runBlocking {
        // Both integrations persisted as enabled before synchronizer starts.
        fakeStateStore.setInitial("health", true)
        fakeStateStore.setInitial("usage", true)

        coEvery { relayApiClient.updateSecrets(any(), any()) } returns
            RelayApiResult.Success(UpdateSecretsResponse(success = true, secrets = emptyMap()))

        val sync = newSynchronizer(integrations = listOf(health, usage), debounceMs = 10L)
        sync.start()

        // Give debounce + flow plumbing time to settle.
        waitForStableCount(sync, expected = 0, windowMs = 150L)

        coVerify(exactly = 0) { relayApiClient.updateSecrets(any(), any()) }
    }

    @Test
    fun `disable flip pushes reduced set`() = runBlocking {
        fakeStateStore.setInitial("health", true)
        fakeStateStore.setInitial("usage", true)

        val capturedIds = slot<List<String>>()
        coEvery { relayApiClient.updateSecrets(any(), capture(capturedIds)) } returns
            RelayApiResult.Success(
                UpdateSecretsResponse(success = true, secrets = mapOf("health" to "brave-health"))
            )

        val sync = newSynchronizer(integrations = listOf(health, usage), debounceMs = 10L)
        sync.start()

        // User disables usage.
        fakeStateStore.setUserEnabled("usage", false)

        sync.successfulPushCount.filter { it >= 1 }.waitFirst()

        assertTrue(capturedIds.isCaptured)
        assertEquals(listOf("health"), capturedIds.captured)
        coVerify(exactly = 1) {
            certStore.storeIntegrationSecrets(mapOf("health" to "brave-health"))
        }
    }

    @Test
    fun `rapid flips are debounced into a single push`() = runBlocking {
        fakeStateStore.setInitial("health", true)
        fakeStateStore.setInitial("usage", true)

        coEvery { relayApiClient.updateSecrets(any(), any()) } returns
            RelayApiResult.Success(UpdateSecretsResponse(success = true, secrets = emptyMap()))

        val sync = newSynchronizer(integrations = listOf(health, usage), debounceMs = 100L)
        sync.start()

        // Three flips well within the debounce window.
        fakeStateStore.setUserEnabled("usage", false)
        fakeStateStore.setUserEnabled("usage", true)
        fakeStateStore.setUserEnabled("health", false)

        sync.successfulPushCount.filter { it >= 1 }.waitFirst()
        // After the first push lands, assert no more arrive over the next window.
        waitForStableCount(sync, expected = 1, windowMs = 400L)

        coVerify(exactly = 1) { relayApiClient.updateSecrets(any(), any()) }
    }

    @Test
    fun `skips push when certStore already holds secrets for enabled set`() = runBlocking {
        // Baseline: both integrations enabled in the store AND the cert
        // store already has secrets for both — simulating the state after
        // the onboarding VM's push already landed. The synchronizer must
        // notice the relay is in sync and skip the duplicate push.
        fakeStateStore.setInitial("health", true)
        fakeStateStore.setInitial("usage", true)

        coEvery { certStore.getIntegrationSecrets() } returns mapOf(
            "health" to "brave-health",
            "usage" to "clever-usage"
        )
        coEvery { relayApiClient.updateSecrets(any(), any()) } returns
            RelayApiResult.Success(UpdateSecretsResponse(success = true, secrets = emptyMap()))

        val sync = newSynchronizer(integrations = listOf(health, usage), debounceMs = 10L)
        sync.start()

        // Force a new combine emission with the same set so the skip-check
        // fires (drop(1) swallows the cold-start emission).
        fakeStateStore.setUserEnabled("health", false)
        fakeStateStore.setUserEnabled("health", true)

        waitForStableCount(sync, expected = 0, windowMs = 300L)

        coVerify(exactly = 0) { relayApiClient.updateSecrets(any(), any()) }
    }

    @Test
    fun `duplicate set emissions do not re-push`() = runBlocking {
        fakeStateStore.setInitial("health", true)
        fakeStateStore.setInitial("usage", true)

        coEvery { relayApiClient.updateSecrets(any(), any()) } returns
            RelayApiResult.Success(UpdateSecretsResponse(success = true, secrets = emptyMap()))

        val sync = newSynchronizer(integrations = listOf(health, usage), debounceMs = 10L)
        sync.start()

        // Disable usage -> push. Then "flip" usage to the same value -> no push.
        fakeStateStore.setUserEnabled("usage", false)
        sync.successfulPushCount.filter { it >= 1 }.waitFirst()

        // Setting it to the same value again must not trigger another push.
        fakeStateStore.setUserEnabled("usage", false)
        waitForStableCount(sync, expected = 1, windowMs = 200L)

        coVerify(exactly = 1) { relayApiClient.updateSecrets(any(), any()) }
    }

    @Test
    fun `transient failure retries three times with backoff`() = runBlocking {
        // Dedicated Default-dispatcher scope: Unconfined + runBlocking does
        // not reliably resume `delay()` continuations across the retry
        // backoff windows (the delay goes onto DefaultExecutor's timer
        // thread while runBlocking's event loop races ahead). Default's
        // thread pool gives us a real scheduler so `delay(backoffMs)`
        // actually schedules the next attempt.
        val retryScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            fakeStateStore.setInitial("health", true)
            fakeStateStore.setInitial("usage", true)

            val callCount = java.util.concurrent.atomic.AtomicInteger(0)
            val allAttemptsDone = kotlinx.coroutines.CompletableDeferred<Unit>()
            coEvery { relayApiClient.updateSecrets(any(), any()) } coAnswers {
                val n = callCount.incrementAndGet()
                if (n >= 3) allAttemptsDone.complete(Unit)
                RelayApiResult.NetworkError(RuntimeException("boom"))
            }

            val sync = IntegrationSecretsSynchronizer(
                integrations = listOf(health, usage),
                stateStore = fakeStateStore,
                relayApiClient = relayApiClient,
                certStore = certStore,
                appScope = retryScope,
                debounceMillis = 10L,
                initialBackoffMs = 5L,
                backoffFactor = 1L
            )
            sync.start()

            // Give the combine() subscriber a chance to register on the
            // Default thread pool before we flip — otherwise the flip may
            // race the first collect() and be treated as the initial cold
            // emission (which we skip via drop(1)).
            kotlinx.coroutines.delay(50L)
            fakeStateStore.setUserEnabled("usage", false)

            withTimeout(3_000L) { allAttemptsDone.await() }
            // Let the final delay + exit run to completion so the loop
            // observes all three attempts before we assert.
            kotlinx.coroutines.delay(100L)

            assertEquals(3, callCount.get())
            coVerify(exactly = 3) { relayApiClient.updateSecrets(any(), any()) }
            assertEquals(0, sync.successfulPushCount.value)
        } finally {
            retryScope.cancel()
        }
    }

    @Test
    fun `success persists relay-issued secrets`() = runBlocking {
        fakeStateStore.setInitial("health", true)
        fakeStateStore.setInitial("usage", true)

        val serverSecrets = mapOf("health" to "brave-health")
        coEvery { relayApiClient.updateSecrets(any(), any()) } returns
            RelayApiResult.Success(UpdateSecretsResponse(success = true, secrets = serverSecrets))

        val storedSlot = slot<Map<String, String>>()
        coEvery { certStore.storeIntegrationSecrets(capture(storedSlot)) } just Runs

        val sync = newSynchronizer(integrations = listOf(health, usage), debounceMs = 10L)
        sync.start()

        fakeStateStore.setUserEnabled("usage", false)
        sync.successfulPushCount.filter { it >= 1 }.waitFirst()

        assertTrue(storedSlot.isCaptured)
        assertEquals(serverSecrets, storedSlot.captured)
    }

    // ---- helpers ----

    private fun newSynchronizer(
        integrations: List<McpIntegration>,
        debounceMs: Long = 10L,
        initialBackoffMs: Long = 1L,
        backoffFactor: Long = 1L
    ) = IntegrationSecretsSynchronizer(
        integrations = integrations,
        stateStore = fakeStateStore,
        relayApiClient = relayApiClient,
        certStore = certStore,
        appScope = scope,
        debounceMillis = debounceMs,
        initialBackoffMs = initialBackoffMs,
        backoffFactor = backoffFactor
    )

    private suspend fun <T> Flow<T>.waitFirst(timeoutMs: Long = 2_000L): T =
        withTimeout(timeoutMs) { first() }

    /**
     * Sleeps [windowMs], then asserts the successful push count equals
     * [expected]. Used to rule out additional pushes arriving after a
     * known-good signal.
     */
    private suspend fun waitForStableCount(
        sync: IntegrationSecretsSynchronizer,
        expected: Int,
        windowMs: Long
    ) {
        kotlinx.coroutines.delay(windowMs)
        assertEquals(
            "successful push count should remain at $expected after ${windowMs}ms",
            expected,
            sync.successfulPushCount.value
        )
    }
}

/**
 * In-memory [IntegrationStateStore] with per-ID [MutableStateFlow]s so tests
 * can drive enable/disable flips synchronously. Backed by [asStateFlow] so
 * the synchronizer's combine() sees each flip as a distinct emission.
 */
private class FakeIntegrationStateStore : IntegrationStateStore {

    private val flows: MutableMap<String, MutableStateFlow<Boolean>> = mutableMapOf()
    private val everEnabled: MutableMap<String, MutableStateFlow<Boolean>> = mutableMapOf()

    fun setInitial(id: String, enabled: Boolean) {
        flow(id).value = enabled
        if (enabled) everFlow(id).value = true
    }

    override suspend fun isUserEnabled(integrationId: String): Boolean = flow(integrationId).value

    override suspend fun setUserEnabled(integrationId: String, enabled: Boolean) {
        flow(integrationId).value = enabled
        if (enabled) everFlow(integrationId).value = true
    }

    override fun observeUserEnabled(integrationId: String): Flow<Boolean> =
        flow(integrationId).asStateFlow()

    override suspend fun wasEverEnabled(integrationId: String): Boolean =
        everFlow(integrationId).value

    override fun observeEverEnabled(integrationId: String): Flow<Boolean> =
        everFlow(integrationId).asStateFlow()

    override fun observeChanges(): Flow<Unit> = flow("__any__").asStateFlow().let { f ->
        kotlinx.coroutines.flow.flow {
            f.collect { emit(Unit) }
        }
    }

    private fun flow(id: String): MutableStateFlow<Boolean> =
        flows.getOrPut(id) { MutableStateFlow(false) }

    private fun everFlow(id: String): MutableStateFlow<Boolean> =
        everEnabled.getOrPut(id) { MutableStateFlow(false) }
}

private fun fakeIntegration(integrationId: String): McpIntegration = object : McpIntegration {
    override val id: String = integrationId
    override val displayName: String = integrationId
    override val description: String = ""
    override val path: String = "/$integrationId"
    override val provider: McpServerProvider = mockk(relaxed = true)
    override suspend fun isAvailable(): Boolean = true
    override val onboardingRoute: String = "setup"
    override val settingsRoute: String = "settings"
}
