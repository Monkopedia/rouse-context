package com.rousecontext.app.ui.viewmodels

import app.cash.turbine.test
import com.rousecontext.api.IntegrationStateStore
import com.rousecontext.api.McpIntegration
import com.rousecontext.app.McpUrlProvider
import com.rousecontext.app.ui.screens.IntegrationStatus
import com.rousecontext.mcp.core.McpServerProvider
import com.rousecontext.mcp.core.TokenInfo
import com.rousecontext.mcp.core.TokenStore
import com.rousecontext.notifications.audit.AuditDao
import com.rousecontext.notifications.audit.AuditEntry
import com.rousecontext.tunnel.CertificateStore
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
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
class IntegrationManageViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

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
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `authorized clients update reactively when new token is issued`() =
        runTest(testDispatcher) {
            val tokensFlow = MutableStateFlow<List<TokenInfo>>(emptyList())
            val stateStore = mockk<IntegrationStateStore> {
                every { isUserEnabled("health") } returns true
                every { wasEverEnabled("health") } returns true
                every { observeChanges() } returns flowOf(Unit)
            }
            val tokenStore = mockk<TokenStore> {
                every { hasTokens("health") } answers { tokensFlow.value.isNotEmpty() }
                every { listTokens("health") } answers { tokensFlow.value }
                every { tokensFlow("health") } returns tokensFlow
            }
            val auditDao = mockk<AuditDao> {
                every { observeByDateRange(any(), any(), any()) } returns flowOf(emptyList())
            }

            val vm = IntegrationManageViewModel(
                integrations = listOf(fakeIntegration()),
                stateStore = stateStore,
                tokenStore = tokenStore,
                auditDao = auditDao,
                urlProvider = fakeUrlProvider
            )
            vm.loadIntegration("health")

            vm.state.test {
                // Initial empty state
                awaitItem()
                // Once loaded, initially zero authorized clients, status PENDING
                val initial = awaitItem()
                assertTrue(initial.authorizedClients.isEmpty())
                assertEquals(IntegrationStatus.PENDING, initial.status)

                // Emit a new token
                tokensFlow.value = listOf(
                    TokenInfo(
                        integrationId = "health",
                        clientId = "client-1",
                        createdAt = 1_000L,
                        lastUsedAt = 1_000L,
                        label = "Claude"
                    )
                )

                val updated = awaitItem()
                assertEquals(1, updated.authorizedClients.size)
                assertEquals("Claude", updated.authorizedClients[0].name)
                assertEquals(IntegrationStatus.ACTIVE, updated.status)
            }
        }

    @Test
    fun `recent activity updates reactively when new audit entry is inserted`() =
        runTest(testDispatcher) {
            val auditFlow = MutableStateFlow<List<AuditEntry>>(emptyList())
            val stateStore = mockk<IntegrationStateStore> {
                every { isUserEnabled("health") } returns true
                every { wasEverEnabled("health") } returns true
                every { observeChanges() } returns flowOf(Unit)
            }
            val tokenStore = mockk<TokenStore> {
                every { hasTokens("health") } returns true
                every { listTokens("health") } returns emptyList()
                every { tokensFlow("health") } returns flowOf(emptyList())
            }
            val auditDao = mockk<AuditDao> {
                every { observeByDateRange(any(), any(), any()) } returns auditFlow
            }

            val vm = IntegrationManageViewModel(
                integrations = listOf(fakeIntegration()),
                stateStore = stateStore,
                tokenStore = tokenStore,
                auditDao = auditDao,
                urlProvider = fakeUrlProvider
            )
            vm.loadIntegration("health")

            vm.state.test {
                awaitItem()
                val initial = awaitItem()
                assertTrue(initial.recentActivity.isEmpty())

                auditFlow.value = listOf(
                    AuditEntry(
                        id = 1,
                        sessionId = "s1",
                        toolName = "get_steps",
                        provider = "health",
                        timestampMillis = 10_000L,
                        durationMillis = 25L,
                        success = true
                    )
                )

                val updated = awaitItem()
                assertEquals(1, updated.recentActivity.size)
                assertEquals("get_steps", updated.recentActivity[0].toolName)
                assertEquals(25L, updated.recentActivity[0].durationMs)
            }
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
