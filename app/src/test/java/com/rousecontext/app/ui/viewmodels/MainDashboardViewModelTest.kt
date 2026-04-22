package com.rousecontext.app.ui.viewmodels

import app.cash.turbine.test
import com.rousecontext.api.IntegrationStateStore
import com.rousecontext.api.McpIntegration
import com.rousecontext.app.McpUrlProvider
import com.rousecontext.app.ui.screens.CertBanner
import com.rousecontext.app.ui.screens.ConnectionStatus
import com.rousecontext.app.ui.screens.IntegrationStatus
import com.rousecontext.app.ui.screens.TerminalReason
import com.rousecontext.mcp.core.McpServerProvider
import com.rousecontext.mcp.core.TokenStore
import com.rousecontext.notifications.audit.AuditDao
import com.rousecontext.notifications.audit.AuditEntry
import com.rousecontext.tunnel.CertificateStore
import com.rousecontext.tunnel.TunnelClient
import com.rousecontext.tunnel.TunnelState
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MainDashboardViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val tunnelStateFlow = MutableStateFlow(TunnelState.DISCONNECTED)
    private val fakeTunnelClient = mockk<TunnelClient> {
        every { state } returns tunnelStateFlow
    }
    private val fakeUrlProvider = McpUrlProvider(
        mockk<CertificateStore> {
            coEvery { getSubdomain() } returns "test-device"
            coEvery { getSecretForIntegration(any()) } returns "test-secret"
        },
        "rousecontext.com"
    )

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        tunnelStateFlow.value = TunnelState.DISCONNECTED
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial state has disconnected status and empty lists`() = runTest(testDispatcher) {
        val vm = createViewModel()
        vm.state.test {
            val state = awaitItem()
            assertEquals(ConnectionStatus.DISCONNECTED, state.connectionStatus)
            assertTrue(state.integrations.isEmpty())
            assertTrue(state.recentActivity.isEmpty())
        }
    }

    @Test
    fun `initial state emits loading before first data emission`() = runTest(testDispatcher) {
        val stateStore = mockk<IntegrationStateStore> {
            coEvery { isUserEnabled(any()) } returns false
            coEvery { wasEverEnabled(any()) } returns false
            every { observeChanges() } returns flowOf(Unit)
        }
        val tokenStore = mockk<TokenStore> {
            every { hasTokens(any()) } returns false
        }
        val auditDao = mockk<AuditDao> {
            every { observeRecent(any(), any(), any()) } returns flowOf(emptyList())
        }
        val vm = MainDashboardViewModel(
            integrations = emptyList(),
            stateStore = stateStore,
            tokenStore = tokenStore,
            auditDao = auditDao,
            urlProvider = fakeUrlProvider,
            tunnelClient = fakeTunnelClient
        )
        vm.state.test {
            val loading = awaitItem()
            assertTrue(loading.isLoading)
            val loaded = awaitItem()
            assertFalse(loaded.isLoading)
        }
    }

    @Test
    fun `audit dao failure surfaces error state`() = runTest(testDispatcher) {
        val stateStore = mockk<IntegrationStateStore> {
            coEvery { isUserEnabled(any()) } returns false
            coEvery { wasEverEnabled(any()) } returns false
            every { observeChanges() } returns flowOf(Unit)
        }
        val tokenStore = mockk<TokenStore> {
            every { hasTokens(any()) } returns false
        }
        val auditDao = mockk<AuditDao> {
            every { observeRecent(any(), any(), any()) } returns flow {
                error("db down")
            }
        }

        val vm = MainDashboardViewModel(
            integrations = emptyList(),
            stateStore = stateStore,
            tokenStore = tokenStore,
            auditDao = auditDao,
            urlProvider = fakeUrlProvider,
            tunnelClient = fakeTunnelClient
        )

        vm.state.test {
            val loading = awaitItem()
            assertTrue(loading.isLoading)
            val error = awaitItem()
            assertFalse(error.isLoading)
            assertNotNull(error.errorMessage)
            assertEquals("db down", error.errorMessage)
        }
    }

    @Test
    fun `active integration appears in dashboard`() = runTest(testDispatcher) {
        val stateStore = mockk<IntegrationStateStore> {
            coEvery { isUserEnabled("health") } returns true
            coEvery { wasEverEnabled("health") } returns true
            every { observeUserEnabled(any()) } returns flowOf(true)
            every { observeChanges() } returns flowOf(Unit)
        }
        val tokenStore = mockk<TokenStore> {
            every { hasTokens("health") } returns true
            every { listTokens(any()) } returns emptyList()
        }
        val auditDao = mockk<AuditDao> {
            coEvery { queryByDateRange(any(), any(), any()) } returns emptyList()
            every { observeRecent(any(), any(), any()) } returns flowOf(emptyList())
        }

        val vm = MainDashboardViewModel(
            integrations = listOf(fakeIntegration()),
            stateStore = stateStore,
            tokenStore = tokenStore,
            auditDao = auditDao,
            urlProvider = fakeUrlProvider,
            tunnelClient = fakeTunnelClient
        )

        vm.state.test {
            awaitItem() // initial
            val state = awaitItem()
            assertEquals(1, state.integrations.size)
            assertEquals("health", state.integrations[0].id)
            assertEquals(IntegrationStatus.ACTIVE, state.integrations[0].status)
        }
    }

    @Test
    fun `unavailable integration does not appear in dashboard`() = runTest(testDispatcher) {
        val stateStore = mockk<IntegrationStateStore> {
            coEvery { isUserEnabled("health") } returns false
            coEvery { wasEverEnabled("health") } returns false
            every { observeUserEnabled(any()) } returns flowOf(false)
            every { observeChanges() } returns flowOf(Unit)
        }
        val tokenStore = mockk<TokenStore> {
            every { hasTokens("health") } returns false
            every { listTokens(any()) } returns emptyList()
        }
        val auditDao = mockk<AuditDao> {
            coEvery { queryByDateRange(any(), any(), any()) } returns emptyList()
            every { observeRecent(any(), any(), any()) } returns flowOf(emptyList())
        }

        val vm = MainDashboardViewModel(
            integrations = listOf(fakeIntegration()),
            stateStore = stateStore,
            tokenStore = tokenStore,
            auditDao = auditDao,
            urlProvider = fakeUrlProvider,
            tunnelClient = fakeTunnelClient
        )

        vm.state.test {
            // The initial stateIn value matches the combine output (both empty),
            // so StateFlow deduplicates and we get one emission.
            val state = awaitItem()
            assertTrue(state.integrations.isEmpty())
            assertTrue(state.hasMoreIntegrationsToAdd)
        }
    }

    @Test
    fun `recent audit entries appear in dashboard`() = runTest(testDispatcher) {
        val auditEntry = AuditEntry(
            id = 1,
            sessionId = "s1",
            toolName = "get_steps",
            provider = "health",
            timestampMillis = System.currentTimeMillis(),
            durationMillis = 42,
            success = true
        )
        val stateStore = mockk<IntegrationStateStore> {
            coEvery { isUserEnabled(any()) } returns false
            coEvery { wasEverEnabled(any()) } returns false
            every { observeChanges() } returns flowOf(Unit)
        }
        val tokenStore = mockk<TokenStore> {
            every { hasTokens(any()) } returns false
        }
        val auditDao = mockk<AuditDao> {
            coEvery { queryByDateRange(any(), any(), any()) } returns listOf(auditEntry)
            every { observeRecent(any(), any(), any()) } returns flowOf(listOf(auditEntry))
        }

        val vm = MainDashboardViewModel(
            integrations = emptyList(),
            stateStore = stateStore,
            tokenStore = tokenStore,
            auditDao = auditDao,
            urlProvider = fakeUrlProvider,
            tunnelClient = fakeTunnelClient
        )

        vm.state.test {
            awaitItem()
            val state = awaitItem()
            assertEquals(1, state.recentActivity.size)
            assertEquals("get_steps", state.recentActivity[0].toolName)
            assertEquals(42L, state.recentActivity[0].durationMs)
        }
    }

    @Test
    fun `dashboard updates reactively when integration state changes`() = runTest(testDispatcher) {
        val stateChanges = MutableSharedFlow<Unit>()
        var enabled = false
        val stateStore = mockk<IntegrationStateStore> {
            coEvery { isUserEnabled("health") } answers { enabled }
            coEvery { wasEverEnabled("health") } answers { enabled }
            every { observeChanges() } returns stateChanges
        }
        val tokenStore = mockk<TokenStore> {
            every { hasTokens("health") } answers { enabled }
        }
        val auditDao = mockk<AuditDao> {
            every { observeRecent(any(), any(), any()) } returns flowOf(emptyList())
        }

        val vm = MainDashboardViewModel(
            integrations = listOf(fakeIntegration()),
            stateStore = stateStore,
            tokenStore = tokenStore,
            auditDao = auditDao,
            urlProvider = fakeUrlProvider,
            tunnelClient = fakeTunnelClient
        )

        vm.state.test {
            // Initial state: no integrations visible
            val initial = awaitItem()
            assertTrue(initial.integrations.isEmpty())

            // Simulate integration state change
            enabled = true
            stateChanges.emit(Unit)
            val updated = awaitItem()
            assertEquals(1, updated.integrations.size)
            assertEquals(IntegrationStatus.ACTIVE, updated.integrations[0].status)
        }
    }

    @Test
    fun `terminal key generation failure surfaces banner`() = runTest(testDispatcher) {
        val vm = createViewModel(
            certRenewalFlow = flowOf(CertBanner.TerminalFailure(TerminalReason.KeyGenerationFailed))
        )
        vm.state.test {
            // initial (loading) then loaded — skip until we see a non-loading state with a banner.
            var state = awaitItem()
            while (state.isLoading || state.certBanner == null) {
                state = awaitItem()
            }
            val banner = state.certBanner
            assertTrue(banner is CertBanner.TerminalFailure)
            assertEquals(
                TerminalReason.KeyGenerationFailed,
                (banner as CertBanner.TerminalFailure).reason
            )
        }
    }

    @Test
    fun `terminal cn mismatch surfaces banner`() = runTest(testDispatcher) {
        val vm = createViewModel(
            certRenewalFlow = flowOf(CertBanner.TerminalFailure(TerminalReason.CnMismatch))
        )
        vm.state.test {
            var state = awaitItem()
            while (state.isLoading || state.certBanner == null) {
                state = awaitItem()
            }
            val banner = state.certBanner
            assertTrue(banner is CertBanner.TerminalFailure)
            assertEquals(
                TerminalReason.CnMismatch,
                (banner as CertBanner.TerminalFailure).reason
            )
        }
    }

    @Test
    fun `non-terminal cert outcome produces no banner`() = runTest(testDispatcher) {
        val vm = createViewModel(certRenewalFlow = flowOf(null))
        vm.state.test {
            var state = awaitItem()
            // Wait for a non-loading emission.
            while (state.isLoading) {
                state = awaitItem()
            }
            // No terminal banner surfaces; banner remains null for SUCCESS / SKIP / etc.
            assertTrue(state.certBanner == null)
        }
    }

    @Test
    fun `notification banner appears when notifications are denied`() = runTest(testDispatcher) {
        val vm = createViewModel(notificationsEnabledFlow = flowOf(false))
        vm.state.test {
            var state = awaitItem()
            while (state.isLoading || state.notificationBanner == null) {
                state = awaitItem()
            }
            assertNotNull(state.notificationBanner)
        }
    }

    @Test
    fun `notification banner absent when notifications are granted`() = runTest(testDispatcher) {
        val vm = createViewModel(notificationsEnabledFlow = flowOf(true))
        vm.state.test {
            var state = awaitItem()
            while (state.isLoading) {
                state = awaitItem()
            }
            assertNull(state.notificationBanner)
        }
    }

    @Test
    fun `notification banner appears reactively when permission is revoked`() =
        runTest(testDispatcher) {
            val notifFlow = MutableStateFlow(true)
            val vm = createViewModel(notificationsEnabledFlow = notifFlow)
            vm.state.test {
                var state = awaitItem()
                while (state.isLoading) {
                    state = awaitItem()
                }
                assertNull(state.notificationBanner)

                // User revokes notifications via system settings.
                notifFlow.value = false
                state = awaitItem()
                assertNotNull(state.notificationBanner)
            }
        }

    @Test
    fun `spurious wake banner appears when rolling 24h count exceeds threshold`() =
        runTest(testDispatcher) {
            val vm = createViewModel(
                spuriousWakesFlow = flowOf(SpuriousWakeStats(rolling24h = 15, total = 40))
            )
            vm.state.test {
                var state = awaitItem()
                while (state.isLoading || state.spuriousWakeBanner == null) {
                    state = awaitItem()
                }
                assertNotNull(state.spuriousWakeBanner)
                assertEquals(15, state.spuriousWakeBanner?.rolling24hCount)
            }
        }

    @Test
    fun `spurious wake banner absent when below threshold`() = runTest(testDispatcher) {
        val vm = createViewModel(
            spuriousWakesFlow = flowOf(SpuriousWakeStats(rolling24h = 5, total = 40))
        )
        vm.state.test {
            var state = awaitItem()
            while (state.isLoading) {
                state = awaitItem()
            }
            assertNull(state.spuriousWakeBanner)
        }
    }

    @Test
    fun `spurious wake banner absent when zero`() = runTest(testDispatcher) {
        val vm = createViewModel()
        vm.state.test {
            var state = awaitItem()
            while (state.isLoading) {
                state = awaitItem()
            }
            assertNull(state.spuriousWakeBanner)
        }
    }

    @Test
    fun `connection status reflects tunnel state`() = runTest(testDispatcher) {
        val vm = createViewModel()
        vm.state.test {
            // Initial: DISCONNECTED
            assertEquals(ConnectionStatus.DISCONNECTED, awaitItem().connectionStatus)

            // Tunnel becomes CONNECTED
            tunnelStateFlow.value = TunnelState.CONNECTED
            assertEquals(ConnectionStatus.CONNECTED, awaitItem().connectionStatus)

            // Tunnel becomes ACTIVE (has streams)
            tunnelStateFlow.value = TunnelState.ACTIVE
            val active = awaitItem()
            assertEquals(ConnectionStatus.CONNECTED, active.connectionStatus)
            assertEquals(1, active.activeSessionCount)

            // Tunnel goes back to CONNECTED (streams closed)
            tunnelStateFlow.value = TunnelState.CONNECTED
            val connected = awaitItem()
            assertEquals(ConnectionStatus.CONNECTED, connected.connectionStatus)
            assertEquals(0, connected.activeSessionCount)

            // Tunnel disconnects
            tunnelStateFlow.value = TunnelState.DISCONNECTED
            assertEquals(ConnectionStatus.DISCONNECTED, awaitItem().connectionStatus)
        }
    }

    @Test
    fun `rolling-24h window advances when the ticker re-emits`() = runTest(testDispatcher) {
        // #370 regression: previously the "Recent Activity" cutoff was
        // captured once at VM init so the window was pinned to the
        // subscription time for the entire dashboard lifetime.
        //
        // We drive the VM with a caller-supplied ticker flow. Emitting a
        // later timestamp must restart the DAO query with a later
        // lower-bound cutoff, proving the window rolls forward.
        val baseTime = 1_700_000_000_000L
        val ticker = MutableStateFlow(baseTime)
        val stateStore = mockk<IntegrationStateStore> {
            coEvery { isUserEnabled(any()) } returns false
            coEvery { wasEverEnabled(any()) } returns false
            every { observeChanges() } returns flowOf(Unit)
        }
        val tokenStore = mockk<TokenStore> {
            every { hasTokens(any()) } returns false
        }
        val startSlot = slot<Long>()
        val auditDao = mockk<AuditDao> {
            every { observeRecent(capture(startSlot), any(), any()) } returns flowOf(emptyList())
        }

        val vm = MainDashboardViewModel(
            integrations = emptyList(),
            stateStore = stateStore,
            tokenStore = tokenStore,
            auditDao = auditDao,
            urlProvider = fakeUrlProvider,
            tunnelClient = fakeTunnelClient,
            cutoffTicker = ticker
        )

        vm.state.test {
            awaitItem() // loading
            awaitItem() // first loaded with baseTime cutoff
            val firstCutoff = startSlot.captured
            assertEquals(baseTime - 24 * 60 * 60 * 1000L, firstCutoff)

            // Advance the ticker by two minutes; the DAO must be re-queried
            // with a later lower-bound cutoff.
            ticker.value = baseTime + 120_000L
            testDispatcher.scheduler.advanceUntilIdle()

            val laterCutoff = startSlot.captured
            assertTrue(
                "rolling cutoff must advance with ticker (was $firstCutoff, now $laterCutoff)",
                laterCutoff > firstCutoff
            )
            cancelAndIgnoreRemainingEvents()
        }

        verify(atLeast = 2) { auditDao.observeRecent(any(), any(), any()) }
    }

    private fun createViewModel(
        certRenewalFlow: kotlinx.coroutines.flow.Flow<CertBanner?> = flowOf(null),
        notificationsEnabledFlow: kotlinx.coroutines.flow.Flow<Boolean> = flowOf(true),
        spuriousWakesFlow: kotlinx.coroutines.flow.Flow<SpuriousWakeStats> =
            flowOf(SpuriousWakeStats.EMPTY)
    ): MainDashboardViewModel {
        val auditDao = mockk<AuditDao> {
            coEvery { queryByDateRange(any(), any(), any()) } returns emptyList()
            every { observeRecent(any(), any(), any()) } returns flowOf(emptyList())
        }
        val stateStore = mockk<IntegrationStateStore> {
            coEvery { isUserEnabled(any()) } returns false
            coEvery { wasEverEnabled(any()) } returns false
            every { observeChanges() } returns flowOf(Unit)
        }
        val tokenStore = mockk<TokenStore> {
            every { hasTokens(any()) } returns false
        }
        return MainDashboardViewModel(
            integrations = emptyList(),
            stateStore = stateStore,
            tokenStore = tokenStore,
            auditDao = auditDao,
            urlProvider = fakeUrlProvider,
            tunnelClient = fakeTunnelClient,
            certRenewalBanner = certRenewalFlow,
            notificationsEnabled = notificationsEnabledFlow,
            spuriousWakesFlow = spuriousWakesFlow
        )
    }

    private fun fakeIntegration(): McpIntegration = object : McpIntegration {
        override val id = "health"
        override val displayName = "Health Connect"
        override val description = "Health data"
        override val path = "/health"
        override val provider = mockk<McpServerProvider>()
        override suspend fun isAvailable() = true
        override val onboardingRoute = "setup"
        override val settingsRoute = "settings"
    }
}
