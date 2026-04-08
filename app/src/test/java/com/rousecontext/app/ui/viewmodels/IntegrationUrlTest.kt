package com.rousecontext.app.ui.viewmodels

import app.cash.turbine.test
import com.rousecontext.api.IntegrationStateStore
import com.rousecontext.api.McpIntegration
import com.rousecontext.app.ui.screens.IntegrationStatus
import com.rousecontext.mcp.core.McpServerProvider
import com.rousecontext.mcp.core.TokenStore
import com.rousecontext.notifications.audit.AuditDao
import com.rousecontext.tunnel.CertificateStore
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Verifies that [IntegrationManageViewModel] generates MCP endpoint URLs
 * using the real subdomain from [CertificateStore] and per-integration secrets.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class IntegrationUrlTest {

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
    fun `URL uses real subdomain and integration secret`() = runTest(testDispatcher) {
        val certStore = mockk<CertificateStore> {
            coEvery { getSubdomain() } returns "test-sub"
            coEvery { getSecretForIntegration("health") } returns "brave-health"
        }
        val stateStore = mockk<IntegrationStateStore> {
            every { isUserEnabled("health") } returns true
            every { wasEverEnabled("health") } returns true
        }
        val tokenStore = mockk<TokenStore> {
            every { hasTokens("health") } returns true
            every { listTokens("health") } returns emptyList()
        }
        val auditDao = mockk<AuditDao> {
            coEvery { queryByDateRange(any(), any(), any()) } returns emptyList()
        }

        val vm = IntegrationManageViewModel(
            integrations = listOf(fakeIntegration("health", "/health")),
            stateStore = stateStore,
            tokenStore = tokenStore,
            auditDao = auditDao,
            certStore = certStore
        )

        vm.loadIntegration("health")

        vm.state.test {
            awaitItem() // initial
            val state = awaitItem()
            assertTrue(
                "URL should contain test-sub subdomain, was: ${state.url}",
                state.url.contains("test-sub")
            )
            assertTrue(
                "URL should contain integration secret brave-health, was: ${state.url}",
                state.url.contains("brave-health")
            )
            assertTrue(
                "URL should have secret.subdomain format, was: ${state.url}",
                state.url.contains("brave-health.test-sub.")
            )
            assertTrue(
                "URL should end with /mcp, was: ${state.url}",
                state.url.endsWith("/mcp")
            )
            assertFalse(
                "URL should not contain placeholder <device>, was: ${state.url}",
                state.url.contains("<device>")
            )
        }
    }

    @Test
    fun `URL includes correct secret for different integrations`() = runTest(testDispatcher) {
        val certStore = mockk<CertificateStore> {
            coEvery { getSubdomain() } returns "my-device"
            coEvery { getSecretForIntegration("notifications") } returns "swift-notifications"
        }
        val stateStore = mockk<IntegrationStateStore> {
            every { isUserEnabled("notifications") } returns true
            every { wasEverEnabled("notifications") } returns true
        }
        val tokenStore = mockk<TokenStore> {
            every { hasTokens("notifications") } returns true
            every { listTokens("notifications") } returns emptyList()
        }
        val auditDao = mockk<AuditDao> {
            coEvery { queryByDateRange(any(), any(), any()) } returns emptyList()
        }

        val vm = IntegrationManageViewModel(
            integrations = listOf(
                fakeIntegration("notifications", "/notifications")
            ),
            stateStore = stateStore,
            tokenStore = tokenStore,
            auditDao = auditDao,
            certStore = certStore
        )

        vm.loadIntegration("notifications")

        vm.state.test {
            awaitItem() // initial
            val state = awaitItem()
            assertEquals(IntegrationStatus.ACTIVE, state.status)
            assertTrue(
                "URL should use integration secret and subdomain, was: ${state.url}",
                state.url.startsWith("https://swift-notifications.my-device.")
            )
            assertTrue(
                "URL should end with /mcp, was: ${state.url}",
                state.url.endsWith("/mcp")
            )
        }
    }

    @Test
    fun `URL uses fallback when subdomain is null`() = runTest(testDispatcher) {
        val certStore = mockk<CertificateStore> {
            coEvery { getSubdomain() } returns null
            coEvery { getSecretForIntegration(any()) } returns null
        }
        val stateStore = mockk<IntegrationStateStore> {
            every { isUserEnabled("health") } returns true
            every { wasEverEnabled("health") } returns true
        }
        val tokenStore = mockk<TokenStore> {
            every { hasTokens("health") } returns true
            every { listTokens("health") } returns emptyList()
        }
        val auditDao = mockk<AuditDao> {
            coEvery { queryByDateRange(any(), any(), any()) } returns emptyList()
        }

        val vm = IntegrationManageViewModel(
            integrations = listOf(fakeIntegration("health", "/health")),
            stateStore = stateStore,
            tokenStore = tokenStore,
            auditDao = auditDao,
            certStore = certStore
        )

        vm.loadIntegration("health")

        vm.state.test {
            awaitItem() // initial
            val state = awaitItem()
            assertTrue(
                "URL should contain 'unknown' when subdomain is null, was: ${state.url}",
                state.url.contains("unknown")
            )
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
