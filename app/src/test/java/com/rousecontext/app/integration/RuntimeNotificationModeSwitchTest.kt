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
import kotlinx.coroutines.yield
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
 * Issue #280 (part of #270): runtime [PostSessionMode] changes apply to
 * the very next MCP session without an app restart.
 *
 * The production providers for the mode check — [SessionSummaryNotifier]
 * and [PerToolCallNotifier] — both call
 * [NotificationSettingsProvider.settings] on every tool call / drain
 * transition. [com.rousecontext.app.state.DataStoreNotificationSettingsProvider]
 * reads DataStore each time, so there is no long-lived cached value; but
 * if a future refactor ever introduced one (e.g. StateFlow snapshot
 * captured at app start), this test catches the regression.
 *
 * ## Scenario
 *
 * 1. Start in [PostSessionMode.SUMMARY]. Provision + enable the echo
 *    integration. Drive ACTIVE → callTool → CONNECTED. Assert: summary
 *    posted on id = [SessionSummaryNotifier.idForClient] for this
 *    session's clientLabel.
 * 2. Without restarting the harness, flip provider to
 *    [PostSessionMode.EACH_USAGE]. Full cycle DISCONNECTED → ACTIVE →
 *    callTool → CONNECTED. Assert: a NEW per-call notification (id >=
 *    [PerToolCallNotifier.BASE_ID]) posts and NO new summary posts for
 *    this cycle.
 * 3. Flip to [PostSessionMode.SUPPRESS]. Full cycle again. Assert: NO
 *    new per-call or summary notifications post for this cycle.
 *
 * Between phases we clear [NotificationManager] posted notifications and
 * reset [SessionSummaryNotifier]'s internal per-cycle "posted" guard by
 * driving the tunnel through [com.rousecontext.tunnel.TunnelState.DISCONNECTED].
 * Audit rows accumulate across phases (mode is purely a notification
 * surface concern).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class RuntimeNotificationModeSwitchTest {

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
    @Suppress("LongMethod")
    fun `mode change between sessions applies immediately`() = runBlocking {
        harness.provisionDevice()
        harness.enableIntegrations(listOf(TestEchoMcpIntegration.ID))
        // Eager-init PerToolCallNotifier so RoomAuditListener's perCallObserver
        // slot is populated before the first tool call (matches the mode
        // tests in batch B).
        harness.koin.get<PerToolCallNotifier>()

        val settings: NotificationSettingsProvider = harness.koin.get()
        val mcpSession: McpSession = harness.koin.get()
        mcpSession.resolvePort()
        val tokenStore: TokenStore = harness.koin.get()
        val tokenPair = tokenStore.createTokenPair(
            integrationId = TestEchoMcpIntegration.ID,
            clientId = "runtime-mode-switch",
            clientName = null
        )
        val auditDao: AuditDao = harness.koin.get()

        val summaryNotifier: SessionSummaryNotifier = harness.koin.get()
        val tunnelState = TestTunnelState()
        observerJob = scope.launch {
            summaryNotifier.observe(tunnelState.tunnelStateFlow)
        }

        val nm = ApplicationProvider.getApplicationContext<android.app.Application>()
            .getSystemService(NotificationManager::class.java)
        val shadowNm = shadowOf(nm)

        // ---------------- Phase 1: SUMMARY ----------------
        settings.setPostSessionMode(PostSessionMode.SUMMARY)
        awaitModePropagated(settings, PostSessionMode.SUMMARY)

        runOneToolCallCycle(
            tunnelState = tunnelState,
            mcpSession = mcpSession,
            accessToken = tokenPair.accessToken,
            clientSuffix = "summary",
            argMessage = "phase1-summary"
        )
        delay(OBSERVER_SETTLE_MS)

        // #347: summary notifications are now per-client, keyed by the
        // clientLabel hash. clientName=null resolves to the clientId,
        // so this session's summary posts at idForClient("runtime-mode-switch").
        val summaryId = SessionSummaryNotifier.idForClient("runtime-mode-switch")
        val phase1Ids = shadowNm.activeNotifications.map { it.id }
        assertTrue(
            "phase 1 (SUMMARY) must post session summary; " +
                "got ids=$phase1Ids (expected $summaryId)",
            phase1Ids.contains(summaryId)
        )
        assertFalse(
            "phase 1 (SUMMARY) must NOT post any per-call notifications; got ids=$phase1Ids",
            phase1Ids.any { it >= PerToolCallNotifier.BASE_ID }
        )
        assertEquals("phase 1 audit count", 1, auditDao.count())
        // Reset NM + summary-cycle guard for phase 2.
        nm.cancelAll()
        tunnelState.markDisconnected()
        delay(OBSERVER_SETTLE_MS)

        // ---------------- Phase 2: EACH_USAGE ----------------
        settings.setPostSessionMode(PostSessionMode.EACH_USAGE)
        awaitModePropagated(settings, PostSessionMode.EACH_USAGE)

        runOneToolCallCycle(
            tunnelState = tunnelState,
            mcpSession = mcpSession,
            accessToken = tokenPair.accessToken,
            clientSuffix = "each-usage",
            argMessage = "phase2-each-usage"
        )
        delay(OBSERVER_SETTLE_MS)

        val phase2Ids = shadowNm.activeNotifications.map { it.id }
        assertTrue(
            "phase 2 (EACH_USAGE) must post a per-call notification; got ids=$phase2Ids",
            phase2Ids.any { it >= PerToolCallNotifier.BASE_ID }
        )
        assertFalse(
            "phase 2 (EACH_USAGE) must NOT post a session summary; got ids=$phase2Ids",
            phase2Ids.contains(summaryId)
        )
        assertEquals("phase 2 audit count", 2, auditDao.count())
        nm.cancelAll()
        tunnelState.markDisconnected()
        delay(OBSERVER_SETTLE_MS)

        // ---------------- Phase 3: SUPPRESS ----------------
        settings.setPostSessionMode(PostSessionMode.SUPPRESS)
        awaitModePropagated(settings, PostSessionMode.SUPPRESS)

        runOneToolCallCycle(
            tunnelState = tunnelState,
            mcpSession = mcpSession,
            accessToken = tokenPair.accessToken,
            clientSuffix = "suppress",
            argMessage = "phase3-suppress"
        )
        delay(OBSERVER_SETTLE_MS)

        val phase3Ids = shadowNm.activeNotifications.map { it.id }
        val offending = phase3Ids.filter {
            it == summaryId || it >= PerToolCallNotifier.BASE_ID
        }
        assertTrue(
            "phase 3 (SUPPRESS) must NOT post summary or per-call notifications; " +
                "got offending ids=$offending",
            offending.isEmpty()
        )
        assertEquals("phase 3 audit count", 3, auditDao.count())
    }

    private suspend fun runOneToolCallCycle(
        tunnelState: TestTunnelState,
        mcpSession: McpSession,
        accessToken: String,
        clientSuffix: String,
        argMessage: String
    ) {
        tunnelState.markActive()
        McpTestDriver(
            session = mcpSession,
            hostHeader = "synth-${TestEchoMcpIntegration.ID}.example.test",
            bearerToken = accessToken
        ).use { driver ->
            driver.initialize()
            driver.callTool(
                name = "echo",
                argumentsJson = """{"message":"$argMessage-$clientSuffix"}"""
            )
        }
        tunnelState.markDrained()
    }

    private suspend fun awaitModePropagated(
        settings: NotificationSettingsProvider,
        expected: PostSessionMode
    ) {
        // DataStore write back-propagates through the provider's DataStore.data
        // flow; a cooperative yield is enough for the write to become visible
        // to the next suspend read, but we re-read until we see the expected
        // value to guard against scheduling flakiness.
        repeat(MODE_PROPAGATION_ATTEMPTS) {
            if (settings.settings().postSessionMode == expected) return
            yield()
            delay(MODE_PROPAGATION_STEP_MS)
        }
        check(settings.settings().postSessionMode == expected) {
            "mode did not propagate to $expected"
        }
    }

    private companion object {
        private const val OBSERVER_SETTLE_MS = 300L
        private const val MODE_PROPAGATION_ATTEMPTS = 20
        private const val MODE_PROPAGATION_STEP_MS = 25L
    }
}
