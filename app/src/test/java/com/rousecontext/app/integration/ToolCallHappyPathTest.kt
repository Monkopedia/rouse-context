package com.rousecontext.app.integration

import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import com.rousecontext.app.integration.harness.AppIntegrationTestHarness
import com.rousecontext.app.integration.harness.TestEchoMcpIntegration
import com.rousecontext.mcp.core.McpSession
import com.rousecontext.mcp.core.TokenStore
import com.rousecontext.notifications.SessionSummaryNotifier
import com.rousecontext.notifications.audit.AuditDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
import org.robolectric.Shadows.shadowOf

/**
 * Batch B happy-path scenario (issue #252, part 1).
 *
 * Drives an MCP tool call through the device-side half of the inbound
 * path — exactly the surface the batch B assertions care about — and
 * asserts the three seams this task tightens:
 *
 *   (a) the echo response round-trips end-to-end,
 *   (b) a [com.rousecontext.notifications.audit.AuditEntry] row is
 *       committed to the real Room DB via [RoomAuditListener], and
 *   (c) ACTIVE → CONNECTED on the tunnel-state flow posts a
 *       session-summary notification through the real
 *       [SessionSummaryNotifier].
 *
 * See [McpSessionTestHarness] for why we skip the relay + bridge + TLS
 * legs and call [McpSession]'s local HTTP server directly.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ToolCallHappyPathTest {

    private val harness = AppIntegrationTestHarness(
        integrationsFactory = { listOf(TestEchoMcpIntegration()) }
    )
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var observerJob: Job? = null

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        harness.start()
    }

    @After
    fun tearDown() {
        observerJob?.cancel()
        scope.cancel()
        harness.stop()
        Dispatchers.resetMain()
    }

    @Test
    fun `echo round-trips, audits one entry, and posts session summary`() = runBlocking {
        harness.provisionDevice()
        harness.enableIntegrations(listOf(TestEchoMcpIntegration.ID))
        // Robolectric persists DataStore across tests in the same JVM; pin the
        // mode explicitly to avoid leakage from a prior scenario.
        harness.koin.get<com.rousecontext.api.NotificationSettingsProvider>()
            .setPostSessionMode(com.rousecontext.api.PostSessionMode.SUMMARY)

        val mcpSession: McpSession = harness.koin.get()
        mcpSession.resolvePort()

        val tokenStore: TokenStore = harness.koin.get()
        val tokenPair = tokenStore.createTokenPair(
            integrationId = TestEchoMcpIntegration.ID,
            clientId = "test-client",
            clientName = "Test Client"
        )

        val summaryNotifier: SessionSummaryNotifier = harness.koin.get()
        val tunnelState = TestTunnelState()
        observerJob = scope.launch {
            summaryNotifier.observe(tunnelState.tunnelStateFlow)
        }
        tunnelState.markActive()

        McpTestDriver(
            session = mcpSession,
            hostHeader = "synth-${TestEchoMcpIntegration.ID}.example.test",
            bearerToken = tokenPair.accessToken
        ).use { driver ->
            driver.initialize()
            val body = driver.callTool(
                name = "echo",
                argumentsJson = """{"message":"hello-from-happy-path"}"""
            )
            assertTrue(
                "echo should round-trip the input message: $body",
                body.contains("hello-from-happy-path")
            )
        }

        // Audit row committed synchronously (issue #244 made the listener
        // `suspend`, so by the time `callTool` returned the row is durable).
        val auditDao: AuditDao = harness.koin.get()
        val latestId = auditDao.latestId()
        assertNotNull("latestId must be non-null after a tool call", latestId)
        val entry = auditDao.getById(latestId!!)
        assertNotNull("audit entry for latest id must be retrievable", entry)
        assertEquals("echo", entry!!.toolName)

        // Drive stream-drain → summary notification post.
        tunnelState.markDrained()
        delay(OBSERVER_SETTLE_MS)

        val nm = ApplicationProvider.getApplicationContext<android.app.Application>()
            .getSystemService(NotificationManager::class.java)
        val posted = shadowOf(nm).activeNotifications
        assertTrue(
            "session summary notification must be posted on ACTIVE→CONNECTED transition; " +
                "posted ids=${posted.map { it.id }}",
            posted.any { it.id == SessionSummaryNotifier.NOTIFICATION_ID }
        )
    }

    private companion object {
        private const val OBSERVER_SETTLE_MS = 300L
    }
}
