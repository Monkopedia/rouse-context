package com.rousecontext.app.integration

import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import com.rousecontext.api.NotificationSettingsProvider
import com.rousecontext.api.PostSessionMode
import com.rousecontext.app.integration.harness.AppIntegrationTestHarness
import com.rousecontext.app.integration.harness.TestEchoMcpIntegration
import com.rousecontext.mcp.core.McpSession
import com.rousecontext.mcp.core.TokenStore
import com.rousecontext.notifications.PerToolCallNotifier
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * Batch B scenario 3 (issue #252): `PostSessionMode.EACH_USAGE`.
 *
 * With per-call notifications enabled:
 *   - [com.rousecontext.notifications.audit.RoomAuditListener] fires its
 *     [com.rousecontext.notifications.audit.PerCallObserver] hook on each
 *     insert; the observer is [PerToolCallNotifier] in production and
 *     posts a notification per tool call.
 *   - [SessionSummaryNotifier] sees the EACH_USAGE mode in its
 *     `postForMode` switch and short-circuits, so no summary is posted
 *     at drain.
 *
 * Audit rows are still recorded (per-call mode only changes user-facing
 * notification shape, not storage).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class PerCallNotifierModeTest {

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
    fun `each-usage mode posts per-call notification and skips session summary`() = runBlocking {
        harness.provisionDevice()
        harness.enableIntegrations(listOf(TestEchoMcpIntegration.ID))

        // Ensure PerToolCallNotifier is resolved so RoomAuditListener's
        // perCallObserver slot is populated at insert time.
        harness.koin.get<PerToolCallNotifier>()

        val settings: NotificationSettingsProvider = harness.koin.get()
        settings.setPostSessionMode(PostSessionMode.EACH_USAGE)

        val mcpSession: McpSession = harness.koin.get()
        mcpSession.resolvePort()
        val tokenStore: TokenStore = harness.koin.get()
        val tokenPair = tokenStore.createTokenPair(
            integrationId = TestEchoMcpIntegration.ID,
            clientId = "per-call-test",
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
            driver.callTool("echo", """{"message":"per-call"}""")
        }
        tunnelState.markDrained()
        delay(OBSERVER_SETTLE_MS)

        val auditDao: AuditDao = harness.koin.get()
        assertEquals(
            "audit row still recorded in EACH_USAGE mode",
            1,
            auditDao.count()
        )

        // Both notifiers share the same channel (SESSION_SUMMARY) but use
        // disjoint id ranges: summary = 6000, per-call = 7000+. Shadow the
        // NotificationManager to inspect posted ids.
        val nm = ApplicationProvider.getApplicationContext<android.app.Application>()
            .getSystemService(NotificationManager::class.java)
        val posted = shadowOf(nm).activeNotifications
        val summaryId = SessionSummaryNotifier.NOTIFICATION_ID
        val perCallBase = PerToolCallNotifier.BASE_ID

        assertTrue(
            "per-call notification must be posted; got ids=${posted.map { it.id }}",
            posted.any { it.id >= perCallBase }
        )
        assertFalse(
            "session summary must NOT be posted in EACH_USAGE mode; " +
                "got ids=${posted.map { it.id }}",
            posted.any { it.id == summaryId }
        )
    }

    private companion object {
        private const val OBSERVER_SETTLE_MS = 300L
    }
}
