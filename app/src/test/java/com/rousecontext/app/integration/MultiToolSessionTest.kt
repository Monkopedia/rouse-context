package com.rousecontext.app.integration

import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import com.rousecontext.api.NotificationSettingsProvider
import com.rousecontext.api.PostSessionMode
import com.rousecontext.app.integration.harness.AppIntegrationTestHarness
import com.rousecontext.app.integration.harness.TestEchoMcpIntegration
import com.rousecontext.app.testing.MainDispatcherRule
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
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * Batch B scenario 5 (issue #252): multiple tool calls in one session.
 *
 * Drives three `echo` calls back-to-back on the same HTTP connection
 * (so the same `mcp-session-id`) and asserts the summary notification
 * reports the correct count. Guards the "session summary aggregates per
 * provider" behaviour when the AI client fires multiple tools before
 * draining.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class MultiToolSessionTest {

    private val harness = AppIntegrationTestHarness(
        integrationsFactory = { listOf(TestEchoMcpIntegration()) }
    )
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var observerJob: Job? = null

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(Dispatchers.Unconfined)

    @Before
    fun setUp() {
        harness.start()
    }

    @After
    fun tearDown() {
        // Drain the observer to quiescence before the rule resets Main:
        // a fire-and-forget cancel() could leave a coroutine resuming
        // onto Main after resetMain, racing a later test's setMain (#376).
        runBlocking { observerJob?.cancelAndJoin() }
        scope.cancel()
        harness.stop()
    }

    @Test
    fun `three tool calls in one session produce one summary with count 3`() = runBlocking {
        harness.provisionDevice()
        harness.enableIntegrations(listOf(TestEchoMcpIntegration.ID))
        harness.koin.get<NotificationSettingsProvider>()
            .setPostSessionMode(PostSessionMode.SUMMARY)

        val mcpSession: McpSession = harness.koin.get()
        mcpSession.resolvePort()
        val tokenStore: TokenStore = harness.koin.get()
        val tokenPair = tokenStore.createTokenPair(
            integrationId = TestEchoMcpIntegration.ID,
            clientId = "multi-test",
            clientName = null
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
            driver.callTool("echo", """{"message":"one"}""", id = 2)
            driver.callTool("echo", """{"message":"two"}""", id = 3)
            driver.callTool("echo", """{"message":"three"}""", id = 4)
        }
        tunnelState.markDrained()
        delay(OBSERVER_SETTLE_MS)

        val auditDao: AuditDao = harness.koin.get()
        assertEquals("exactly three audit rows recorded", 3, auditDao.count())

        val nm = ApplicationProvider.getApplicationContext<android.app.Application>()
            .getSystemService(NotificationManager::class.java)
        val posted = shadowOf(nm).activeNotifications
        // #347: summary notifications are per-client. clientName=null →
        // clientLabel = clientId ("multi-test").
        val expectedId = SessionSummaryNotifier.idForClient("multi-test")
        val summary = posted.firstOrNull { it.id == expectedId }
        assertTrue(
            "session summary must be posted; got ids=${posted.map {
                it.id
            }} (expected $expectedId)",
            summary != null
        )
        // Post-#347 title: "${clientLabel} used 3 tools". Assert the count
        // makes it into the title — the per-provider breakdown is no longer
        // part of the summary copy.
        val title =
            summary!!.notification.extras.getCharSequence("android.title")?.toString() ?: ""
        assertTrue(
            "summary title should include the tool-call count; got '$title'",
            title.contains("3 tools")
        )
    }

    private companion object {
        private const val OBSERVER_SETTLE_MS = 300L
    }
}
