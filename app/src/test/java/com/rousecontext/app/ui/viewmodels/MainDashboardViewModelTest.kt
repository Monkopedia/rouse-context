package com.rousecontext.app.ui.viewmodels

import app.cash.turbine.test
import com.rousecontext.api.IntegrationStateStore
import com.rousecontext.api.McpIntegration
import com.rousecontext.app.ui.screens.ConnectionStatus
import com.rousecontext.app.ui.screens.IntegrationStatus
import com.rousecontext.mcp.core.McpServerProvider
import com.rousecontext.mcp.core.TokenStore
import com.rousecontext.notifications.audit.AuditDao
import com.rousecontext.notifications.audit.AuditEntry
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MainDashboardViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
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
    fun `active integration appears in dashboard`() = runTest(testDispatcher) {
        val stateStore = mockk<IntegrationStateStore> {
            every { isUserEnabled("health") } returns true
            every { wasEverEnabled("health") } returns true
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
            auditDao = auditDao
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
            every { isUserEnabled("health") } returns false
            every { wasEverEnabled("health") } returns false
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
            auditDao = auditDao
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
            every { isUserEnabled(any()) } returns false
            every { wasEverEnabled(any()) } returns false
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
            auditDao = auditDao
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
            every { isUserEnabled("health") } answers { enabled }
            every { wasEverEnabled("health") } answers { enabled }
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
            auditDao = auditDao
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

    private fun createViewModel(): MainDashboardViewModel {
        val auditDao = mockk<AuditDao> {
            coEvery { queryByDateRange(any(), any(), any()) } returns emptyList()
            every { observeRecent(any(), any(), any()) } returns flowOf(emptyList())
        }
        val stateStore = mockk<IntegrationStateStore> {
            every { isUserEnabled(any()) } returns false
            every { wasEverEnabled(any()) } returns false
            every { observeChanges() } returns flowOf(Unit)
        }
        val tokenStore = mockk<TokenStore> {
            every { hasTokens(any()) } returns false
        }
        return MainDashboardViewModel(
            integrations = emptyList(),
            stateStore = stateStore,
            tokenStore = tokenStore,
            auditDao = auditDao
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
