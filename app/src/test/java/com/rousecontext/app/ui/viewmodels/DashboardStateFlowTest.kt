package com.rousecontext.app.ui.viewmodels

import app.cash.turbine.test
import com.rousecontext.api.IntegrationStateStore
import com.rousecontext.api.McpIntegration
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

/**
 * Verifies that [MainDashboardViewModel] reactively reflects integration
 * state changes: when an integration is enabled or disabled, the state
 * updates without requiring a manual refresh call.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DashboardStateFlowTest {

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
    fun `enabling integration reactively adds it to dashboard`() = runTest(testDispatcher) {
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
            integrations = listOf(fakeIntegration("health", "/health")),
            stateStore = stateStore,
            tokenStore = tokenStore,
            auditDao = auditDao,
            certStore = mockk {
                coEvery { getSubdomain() } returns "test-sub"
                coEvery { getIntegrationSecrets() } returns null
            }
        )

        vm.state.test {
            val initial = awaitItem()
            assertTrue("Initial state should have no integrations", initial.integrations.isEmpty())

            // Enable the integration and signal a change
            enabled = true
            stateChanges.emit(Unit)

            val updated = awaitItem()
            assertEquals(1, updated.integrations.size)
            assertEquals("health", updated.integrations[0].id)
            assertEquals(IntegrationStatus.ACTIVE, updated.integrations[0].status)
        }
    }

    @Test
    fun `disabling integration reactively removes it from dashboard`() = runTest(testDispatcher) {
        val stateChanges = MutableSharedFlow<Unit>()
        var enabled = true
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
            integrations = listOf(fakeIntegration("health", "/health")),
            stateStore = stateStore,
            tokenStore = tokenStore,
            auditDao = auditDao,
            certStore = mockk {
                coEvery { getSubdomain() } returns "test-sub"
                coEvery { getIntegrationSecrets() } returns null
            }
        )

        vm.state.test {
            // Skip initial empty emission from stateIn
            awaitItem()
            // The combine should fire with the onStart Unit emission
            val active = awaitItem()
            assertEquals(1, active.integrations.size)
            assertEquals(IntegrationStatus.ACTIVE, active.integrations[0].status)

            // Disable the integration and signal
            enabled = false
            stateChanges.emit(Unit)

            val updated = awaitItem()
            assertTrue(
                "Integration should be removed after disabling",
                updated.integrations.isEmpty()
            )
        }
    }

    @Test
    fun `multiple integrations update independently`() = runTest(testDispatcher) {
        val stateChanges = MutableSharedFlow<Unit>()
        var healthEnabled = false
        var notifEnabled = false
        val stateStore = mockk<IntegrationStateStore> {
            every { isUserEnabled("health") } answers { healthEnabled }
            every { wasEverEnabled("health") } answers { healthEnabled }
            every { isUserEnabled("notifications") } answers { notifEnabled }
            every { wasEverEnabled("notifications") } answers { notifEnabled }
            every { observeChanges() } returns stateChanges
        }
        val tokenStore = mockk<TokenStore> {
            every { hasTokens("health") } answers { healthEnabled }
            every { hasTokens("notifications") } answers { notifEnabled }
        }
        val auditDao = mockk<AuditDao> {
            every { observeRecent(any(), any(), any()) } returns flowOf(emptyList())
        }

        val vm = MainDashboardViewModel(
            integrations = listOf(
                fakeIntegration("health", "/health"),
                fakeIntegration("notifications", "/notifications")
            ),
            stateStore = stateStore,
            tokenStore = tokenStore,
            auditDao = auditDao,
            certStore = mockk {
                coEvery { getSubdomain() } returns "test-sub"
                coEvery { getIntegrationSecrets() } returns null
            }
        )

        vm.state.test {
            val initial = awaitItem()
            assertTrue(initial.integrations.isEmpty())

            // Enable health only
            healthEnabled = true
            stateChanges.emit(Unit)
            val afterHealth = awaitItem()
            assertEquals(1, afterHealth.integrations.size)
            assertEquals("health", afterHealth.integrations[0].id)

            // Enable notifications too
            notifEnabled = true
            stateChanges.emit(Unit)
            val afterBoth = awaitItem()
            assertEquals(2, afterBoth.integrations.size)
        }
    }

    @Test
    fun `audit entries update reactively via flow`() = runTest(testDispatcher) {
        val auditFlow = MutableSharedFlow<List<AuditEntry>>(replay = 1)
        val stateStore = mockk<IntegrationStateStore> {
            every { isUserEnabled(any()) } returns false
            every { wasEverEnabled(any()) } returns false
            every { observeChanges() } returns flowOf(Unit)
        }
        val tokenStore = mockk<TokenStore> {
            every { hasTokens(any()) } returns false
        }
        val auditDao = mockk<AuditDao> {
            every { observeRecent(any(), any(), any()) } returns auditFlow
        }

        val entry = AuditEntry(
            id = 1,
            sessionId = "s1",
            toolName = "get_steps",
            provider = "health",
            timestampMillis = System.currentTimeMillis(),
            durationMillis = 100,
            success = true
        )

        // Seed the replay buffer so combine can fire immediately
        auditFlow.emit(listOf(entry))

        val vm = MainDashboardViewModel(
            integrations = emptyList(),
            stateStore = stateStore,
            tokenStore = tokenStore,
            auditDao = auditDao,
            certStore = mockk {
                coEvery { getSubdomain() } returns "test-sub"
                coEvery { getIntegrationSecrets() } returns null
            }
        )

        vm.state.test {
            awaitItem() // initial stateIn value
            val state = awaitItem()
            assertEquals(1, state.recentActivity.size)
            assertEquals("get_steps", state.recentActivity[0].toolName)

            // Emit more entries
            auditFlow.emit(
                listOf(
                    entry,
                    entry.copy(id = 2, toolName = "get_sleep")
                )
            )
            val updated = awaitItem()
            assertEquals(2, updated.recentActivity.size)
        }
    }

    private fun fakeIntegration(id: String, path: String): McpIntegration =
        object : McpIntegration {
            override val id = id
            override val displayName = id.replaceFirstChar { it.uppercase() }
            override val description = "$id integration"
            override val path = path
            override val provider = mockk<McpServerProvider>()
            override suspend fun isAvailable() = true
            override val onboardingRoute = "setup"
            override val settingsRoute = "settings"
        }
}
