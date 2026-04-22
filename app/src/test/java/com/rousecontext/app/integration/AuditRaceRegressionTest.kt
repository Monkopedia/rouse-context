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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * Regression guard for issue #244 (batch B scenario 2).
 *
 * Before #244, [com.rousecontext.notifications.audit.RoomAuditListener]
 * launched the DB insert on a separate scope (`scope.launch { insert }`).
 * That meant `onToolCall` could return before the row committed, so the
 * immediately-following ACTIVE → CONNECTED transition fired
 * [SessionSummaryNotifier.observe]'s DAO query against an empty table
 * and skipped the notification.
 *
 * With #244's fix the listener is `suspend`, the insert runs inline on
 * the caller's dispatcher, and the row is durable before the HTTP
 * response reaches the AI client. The assertion here is:
 *
 *   - do one tool call,
 *   - drive the stream-drain transition on the *same* turn,
 *   - then assert (within the observer's settle window) that the summary
 *     notification IS posted. If #244 regresses the row lands after the
 *     cursor query, the notification is silently skipped, and this test
 *     fails.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class AuditRaceRegressionTest {

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
    fun `summary notification fires when drain happens immediately after tool call`() =
        runBlocking {
            harness.provisionDevice()
            harness.enableIntegrations(listOf(TestEchoMcpIntegration.ID))
            harness.koin.get<com.rousecontext.api.NotificationSettingsProvider>()
                .setPostSessionMode(com.rousecontext.api.PostSessionMode.SUMMARY)

            val mcpSession: McpSession = harness.koin.get()
            mcpSession.resolvePort()
            val tokenStore: TokenStore = harness.koin.get()
            val tokenPair = tokenStore.createTokenPair(
                integrationId = TestEchoMcpIntegration.ID,
                clientId = "race-test",
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
                driver.callTool("echo", """{"message":"race"}""")
            }
            // Stream drains on the same turn the tool call returned. Pre-#244
            // the insert would race the cursor and the notification would be
            // silently dropped.
            tunnelState.markDrained()
            delay(OBSERVER_SETTLE_MS)

            val auditDao: AuditDao = harness.koin.get()
            assertEquals(
                "exactly one audit row should be recorded",
                1,
                auditDao.count()
            )

            val nm = ApplicationProvider.getApplicationContext<android.app.Application>()
                .getSystemService(NotificationManager::class.java)
            val posted = shadowOf(nm).activeNotifications
            // #347: summary notifications are now per-client. InMemoryTokenStore
            // resolves a null clientName to the clientId ("race-test"), which
            // flows through as the audit clientLabel.
            val expectedId = SessionSummaryNotifier.idForClient("race-test")
            assertTrue(
                "session summary must be posted on the same cycle the tool call returned; " +
                    "posted ids=${posted.map { it.id }} (expected $expectedId)",
                posted.any { it.id == expectedId }
            )
        }

    private companion object {
        private const val OBSERVER_SETTLE_MS = 300L
    }
}
