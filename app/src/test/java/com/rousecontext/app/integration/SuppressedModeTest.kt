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
import com.rousecontext.notifications.PerToolCallNotifier
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
 * Batch B scenario 4 (issue #252): `PostSessionMode.SUPPRESS`.
 *
 * Tool call completes, audit row is recorded, but neither the summary
 * nor the per-call notifier should post anything. Guards the user
 * preference "don't nag me" survives the full device-side pipeline.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SuppressedModeTest {

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
    fun `suppressed mode records audit but posts no notifications`() = runBlocking {
        harness.provisionDevice()
        harness.enableIntegrations(listOf(TestEchoMcpIntegration.ID))
        harness.koin.get<PerToolCallNotifier>() // eager-init perCallObserver
        harness.koin.get<NotificationSettingsProvider>()
            .setPostSessionMode(PostSessionMode.SUPPRESS)

        val mcpSession: McpSession = harness.koin.get()
        mcpSession.resolvePort()
        val tokenStore: TokenStore = harness.koin.get()
        val tokenPair = tokenStore.createTokenPair(
            integrationId = TestEchoMcpIntegration.ID,
            clientId = "suppressed-test",
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
            driver.callTool("echo", """{"message":"suppressed"}""")
        }
        tunnelState.markDrained()
        delay(OBSERVER_SETTLE_MS)

        val auditDao: AuditDao = harness.koin.get()
        assertEquals("audit row still recorded in SUPPRESS mode", 1, auditDao.count())

        val nm = ApplicationProvider.getApplicationContext<android.app.Application>()
            .getSystemService(NotificationManager::class.java)
        val posted = shadowOf(nm).activeNotifications
        // #347: summary id is per-client; clientName=null → clientLabel = clientId.
        val summaryId = SessionSummaryNotifier.idForClient("suppressed-test")
        val perCallBase = PerToolCallNotifier.BASE_ID
        val offending = posted.filter {
            it.id == summaryId || it.id >= perCallBase
        }
        val offendingIds = offending.map { it.id }
        assertTrue(
            "no session-summary or per-call notifications in SUPPRESS mode; got ids=$offendingIds",
            offending.isEmpty()
        )
    }

    private companion object {
        private const val OBSERVER_SETTLE_MS = 300L
    }
}
