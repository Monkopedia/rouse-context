package com.rousecontext.app.ui.viewmodels

import app.cash.turbine.test
import com.rousecontext.api.IntegrationStateStore
import com.rousecontext.api.McpIntegration
import com.rousecontext.app.McpUrlProvider
import com.rousecontext.app.testing.MainDispatcherRule
import com.rousecontext.app.ui.screens.IntegrationStatus
import com.rousecontext.mcp.core.InMemoryTokenStore
import com.rousecontext.mcp.core.McpServerProvider
import com.rousecontext.mcp.core.TokenInfo
import com.rousecontext.mcp.core.TokenStore
import com.rousecontext.notifications.audit.AuditDao
import com.rousecontext.notifications.audit.AuditEntry
import com.rousecontext.tunnel.CertificateStore
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class IntegrationManageViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(testDispatcher)

    private val fakeUrlProvider = McpUrlProvider(
        mockk<CertificateStore> {
            coEvery { getSubdomain() } returns "test-device"
            coEvery { getSecretForIntegration(any()) } returns "test-secret"
        },
        "rousecontext.com"
    )

    @Test
    fun `authorized clients update reactively when new token is issued`() =
        runTest(testDispatcher) {
            val tokensFlow = MutableStateFlow<List<TokenInfo>>(emptyList())
            val stateStore = mockk<IntegrationStateStore> {
                coEvery { isUserEnabled("health") } returns true
                coEvery { wasEverEnabled("health") } returns true
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
                coEvery { isUserEnabled("health") } returns true
                coEvery { wasEverEnabled("health") } returns true
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

    // --- #784: revoke must target the tapped row's clientId, never its label ---

    private fun revokeVm(tokenStore: TokenStore): IntegrationManageViewModel {
        val stateStore = mockk<IntegrationStateStore> {
            coEvery { isUserEnabled("health") } returns true
            coEvery { wasEverEnabled("health") } returns true
            every { observeChanges() } returns flowOf(Unit)
        }
        val auditDao = mockk<AuditDao> {
            every { observeByDateRange(any(), any(), any()) } returns flowOf(emptyList())
        }
        return IntegrationManageViewModel(
            integrations = listOf(fakeIntegration()),
            stateStore = stateStore,
            tokenStore = tokenStore,
            auditDao = auditDao,
            urlProvider = fakeUrlProvider
        ).also { it.loadIntegration("health") }
    }

    @Test
    fun `authorized client rows carry the token clientId`() = runTest(testDispatcher) {
        val store = InMemoryTokenStore()
        store.createTokenPair("health", "client-a", "Claude")
        store.createTokenPair("health", "client-b", "Claude")
        val vm = revokeVm(store)

        vm.state.test {
            awaitItem()
            val loaded = awaitItem()
            assertEquals(
                setOf("client-a", "client-b"),
                loaded.authorizedClients.map { it.clientId }.toSet()
            )
        }
    }

    @Test
    fun `revoking one of two same-label clients revokes only that client`() {
        // Exercise both directions so the test fails for a label match
        // regardless of which same-label row a store happens to list first.
        listOf("client-a" to "client-b", "client-b" to "client-a").forEach { (target, other) ->
            val store = InMemoryTokenStore()
            val pairs = mapOf(
                "client-a" to store.createTokenPair("health", "client-a", "Claude"),
                "client-b" to store.createTokenPair("health", "client-b", "Claude")
            )
            val vm = revokeVm(store)

            vm.revokeClient(target)

            assertEquals(
                "revoking $target",
                listOf(other),
                store.listTokens("health").map { it.clientId }
            )
            val revoked = pairs.getValue(target)
            assertFalse(store.validateToken("health", revoked.accessToken))
            assertNull(store.refreshToken("health", revoked.refreshToken))
            val kept = pairs.getValue(other)
            assertTrue(store.validateToken("health", kept.accessToken))
            assertNotNull(store.refreshToken("health", kept.refreshToken))
        }
    }

    @Test
    fun `revoking the only client removes it and invalidates its tokens`() {
        val store = InMemoryTokenStore()
        val pair = store.createTokenPair("health", "client-a", "Claude")
        val vm = revokeVm(store)

        vm.revokeClient("client-a")

        assertTrue(store.listTokens("health").isEmpty())
        assertFalse(store.validateToken("health", pair.accessToken))
        assertNull(store.refreshToken("health", pair.refreshToken))
    }

    @Test
    fun `revoking a nonexistent clientId leaves every client intact`() {
        val store = InMemoryTokenStore()
        val a = store.createTokenPair("health", "client-a", "Claude")
        val b = store.createTokenPair("health", "client-b", "Cursor")
        val vm = revokeVm(store)

        vm.revokeClient("no-such-client")
        // A display label is not a client id and must not match anything.
        vm.revokeClient("Claude")

        assertEquals(
            setOf("client-a", "client-b"),
            store.listTokens("health").map { it.clientId }.toSet()
        )
        assertTrue(store.validateToken("health", a.accessToken))
        assertTrue(store.validateToken("health", b.accessToken))
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
