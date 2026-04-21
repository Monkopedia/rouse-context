package com.rousecontext.notifications.preview

import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import com.rousecontext.api.NotificationSettings
import com.rousecontext.api.NotificationSettingsProvider
import com.rousecontext.api.PostSessionMode
import com.rousecontext.mcp.core.ToolCallEvent
import com.rousecontext.notifications.AndroidSecurityCheckNotifier
import com.rousecontext.notifications.AuthRequestNotifier
import com.rousecontext.notifications.FgsLimitNotifier
import com.rousecontext.notifications.ForegroundNotifier
import com.rousecontext.notifications.LaunchRequestNotifier
import com.rousecontext.notifications.PerToolCallNotifier
import com.rousecontext.notifications.SecurityCheckNotifier
import com.rousecontext.notifications.SessionSummaryNotifier
import com.rousecontext.notifications.audit.AuditDao
import com.rousecontext.notifications.audit.AuditEntry
import com.rousecontext.tunnel.TunnelState
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.ClassRule
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Captures Roborazzi screenshots of every notification the `:notifications`
 * module emits in production. Each case drives the REAL notifier with
 * synthetic-but-realistic inputs, reads the posted [Notification] back via
 * Robolectric's shadow [NotificationManager], and renders it through
 * [NotificationCard] in light or dark theme.
 *
 * One `@Test` per (case, theme) pair because `ComposeRule.setContent` can only
 * be called once per test; building the notification in each case stays cheap
 * (Robolectric resets the shade between tests).
 *
 * Mapping (notifier -> screenshots):
 *   ForegroundNotifier          -> 01..05 foreground_*
 *   AuthRequestNotifier         -> 06 auth_request
 *   PerToolCallNotifier         -> 07 per_tool_call
 *   SessionSummaryNotifier      -> 08 session_summary
 *   LaunchRequestNotifier       -> 09 launch_app, 10 launch_open_link
 *   FgsLimitNotifier            -> 11 fgs_limit
 *   AndroidSecurityCheckNotifier -> 12 security_self_cert, 13 security_ct_log
 *
 * Run with:
 * ```
 * JAVA_HOME=/usr/lib/jvm/java-21-openjdk \
 *   ./gradlew :notifications:recordRoborazziDebug \
 *   --tests "*.NotificationScreenshotTest" -Proborazzi.test.record=true
 * ```
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33], qualifiers = "w400dp-h800dp-xxhdpi")
class NotificationScreenshotTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    private val notificationManager
        get() = context.getSystemService(NotificationManager::class.java)

    private fun shadow() = Shadows.shadowOf(notificationManager)

    /**
     * Capture the most recent posted notification. Callers post via the real
     * notifier before calling this; Robolectric resets the shade for the next
     * test case.
     */
    private fun lastPosted(): Notification = shadow().allNotifications.last()

    private fun captureLight(
        name: String,
        notifier: String,
        variantLabel: String,
        notification: Notification
    ) {
        CapturedNotifications.record(name, notifier, variantLabel, notification)
        composeRule.setContent {
            NotificationCard(notification = notification, isDark = false)
        }
        composeRule.onRoot().captureRoboImage("screenshots/${name}_light.png")
    }

    private fun captureDark(
        name: String,
        notifier: String,
        variantLabel: String,
        notification: Notification
    ) {
        CapturedNotifications.record(name, notifier, variantLabel, notification)
        composeRule.setContent {
            NotificationCard(notification = notification, isDark = true)
        }
        composeRule.onRoot().captureRoboImage("screenshots/${name}_dark.png")
    }

    // =========================================================================
    // Foreground Service (ForegroundNotifier.build — the one build()-returning
    // notifier; doesn't post itself, so we render its return value directly).
    // =========================================================================

    @Test
    fun foregroundConnectingLight() = captureLight(
        "01_foreground_connecting",
        FGS,
        "Connecting",
        foreground("Connecting")
    )

    @Test
    fun foregroundConnectingDark() = captureDark(
        "01_foreground_connecting",
        FGS,
        "Connecting",
        foreground("Connecting")
    )

    @Test
    fun foregroundConnectedLight() = captureLight(
        "02_foreground_connected",
        FGS,
        "Connected",
        foreground("Connected")
    )

    @Test
    fun foregroundConnectedDark() = captureDark(
        "02_foreground_connected",
        FGS,
        "Connected",
        foreground("Connected")
    )

    @Test
    fun foregroundActiveSessionLight() = captureLight(
        "03_foreground_active_session",
        FGS,
        "Active session",
        foreground("Connected \u2022 2 active streams")
    )

    @Test
    fun foregroundActiveSessionDark() = captureDark(
        "03_foreground_active_session",
        FGS,
        "Active session",
        foreground("Connected \u2022 2 active streams")
    )

    @Test
    fun foregroundDisconnectingLight() = captureLight(
        "04_foreground_disconnecting",
        FGS,
        "Disconnecting",
        foreground("Disconnecting")
    )

    @Test
    fun foregroundDisconnectingDark() = captureDark(
        "04_foreground_disconnecting",
        FGS,
        "Disconnecting",
        foreground("Disconnecting")
    )

    @Test
    fun foregroundDisconnectedLight() = captureLight(
        "05_foreground_disconnected",
        FGS,
        "Disconnected",
        foreground("Disconnected")
    )

    @Test
    fun foregroundDisconnectedDark() = captureDark(
        "05_foreground_disconnected",
        FGS,
        "Disconnected",
        foreground("Disconnected")
    )

    private fun foreground(status: String): Notification = ForegroundNotifier.build(context, status)

    // =========================================================================
    // AuthRequestNotifier
    // =========================================================================

    @Test
    fun authRequestLight() = captureLight(
        "06_auth_request",
        "AuthRequestNotifier",
        "New auth request",
        postAuthRequest()
    )

    @Test
    fun authRequestDark() = captureDark(
        "06_auth_request",
        "AuthRequestNotifier",
        "New auth request",
        postAuthRequest()
    )

    private fun postAuthRequest(): Notification {
        val notifier = AuthRequestNotifier(
            context = context,
            receiverClass = DummyReceiver::class.java,
            approveAction = "test.APPROVE",
            denyAction = "test.DENY",
            activityClass = DummyActivity::class.java
        )
        notifier.post(displayCode = "AB3X-9K2F", integration = "health-connect")
        return lastPosted()
    }

    // =========================================================================
    // PerToolCallNotifier (EACH_USAGE mode)
    // =========================================================================

    @Test
    fun perToolCallLight() = captureLight(
        "07_per_tool_call",
        "PerToolCallNotifier",
        "Each usage",
        postPerToolCall()
    )

    @Test
    fun perToolCallDark() = captureDark(
        "07_per_tool_call",
        "PerToolCallNotifier",
        "Each usage",
        postPerToolCall()
    )

    private fun postPerToolCall(): Notification = runBlocking {
        val notifier = PerToolCallNotifier(
            context = context,
            settingsProvider = FakeSettingsProvider(PostSessionMode.EACH_USAGE),
            integrationDisplayNames = mapOf("health-connect" to "Health Connect"),
            activityClass = DummyActivity::class.java
        )
        notifier.onToolCallRecorded(sampleToolCallEvent())
        lastPosted()
    }

    // =========================================================================
    // SessionSummaryNotifier (drive via observe(states) transitioning
    // ACTIVE -> CONNECTED, with the fake DAO returning three audit entries.)
    // =========================================================================

    @Test
    fun sessionSummaryLight() = captureLight(
        "08_session_summary",
        "SessionSummaryNotifier",
        "Session summary",
        postSessionSummary()
    )

    @Test
    fun sessionSummaryDark() = captureDark(
        "08_session_summary",
        "SessionSummaryNotifier",
        "Session summary",
        postSessionSummary()
    )

    private fun postSessionSummary(): Notification = runBlocking {
        val dao = FakeAuditDao(
            latest = 0L,
            afterCursor = listOf(
                sampleEntry(id = 1, toolName = "get_steps", provider = "health-connect"),
                sampleEntry(id = 2, toolName = "get_heart_rate", provider = "health-connect"),
                sampleEntry(id = 3, toolName = "send_notification", provider = "notifications")
            )
        )
        val notifier = SessionSummaryNotifier(
            context = context,
            auditDao = dao,
            settingsProvider = FakeSettingsProvider(PostSessionMode.SUMMARY),
            activityClass = DummyActivity::class.java
        )
        val states = MutableStateFlow(TunnelState.DISCONNECTED)
        val job = launch { notifier.observe(states) }
        states.value = TunnelState.CONNECTING
        yield()
        states.value = TunnelState.CONNECTED
        yield()
        states.value = TunnelState.ACTIVE
        yield()
        states.value = TunnelState.CONNECTED
        yield()
        yield()
        val posted = lastPosted()
        job.cancel()
        posted
    }

    // =========================================================================
    // LaunchRequestNotifier
    // =========================================================================

    @Test
    fun launchAppLight() = captureLight(
        "09_launch_app",
        "LaunchRequestNotifier",
        "Launch app",
        postLaunchApp()
    )

    @Test
    fun launchAppDark() = captureDark(
        "09_launch_app",
        "LaunchRequestNotifier",
        "Launch app",
        postLaunchApp()
    )

    private fun postLaunchApp(): Notification {
        val notifier = LaunchRequestNotifier(context)
        val intent = Intent(Intent.ACTION_MAIN).apply {
            `package` = "com.android.chrome"
        }
        notifier.postLaunchApp(
            launchIntent = intent,
            packageName = "com.android.chrome",
            clientName = "Claude"
        )
        return lastPosted()
    }

    @Test
    fun launchOpenLinkLight() = captureLight(
        "10_launch_open_link",
        "LaunchRequestNotifier",
        "Open link",
        postLaunchOpenLink()
    )

    @Test
    fun launchOpenLinkDark() = captureDark(
        "10_launch_open_link",
        "LaunchRequestNotifier",
        "Open link",
        postLaunchOpenLink()
    )

    private fun postLaunchOpenLink(): Notification {
        val notifier = LaunchRequestNotifier(context)
        val url = "https://rousecontext.com/reports/2026-04-17"
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        notifier.postOpenLink(
            viewIntent = intent,
            url = url,
            clientName = "Claude"
        )
        return lastPosted()
    }

    // =========================================================================
    // FgsLimitNotifier — the gap the issue calls out explicitly.
    // =========================================================================

    @Test
    fun fgsLimitLight() = captureLight(
        "11_fgs_limit",
        "FgsLimitNotifier",
        "6hr FGS limit",
        postFgsLimit()
    )

    @Test
    fun fgsLimitDark() = captureDark(
        "11_fgs_limit",
        "FgsLimitNotifier",
        "6hr FGS limit",
        postFgsLimit()
    )

    private fun postFgsLimit(): Notification {
        FgsLimitNotifier.postLimitReachedNotification(context)
        return lastPosted()
    }

    // =========================================================================
    // AndroidSecurityCheckNotifier (Alert variants — skip the 3x-debounce
    // warning path; Info uses identical layout with different severity.)
    // =========================================================================

    @Test
    fun securitySelfCertAlertLight() = captureLight(
        "12_security_self_cert",
        "AndroidSecurityCheckNotifier",
        "Self-cert alert",
        postSelfCertAlert()
    )

    @Test
    fun securitySelfCertAlertDark() = captureDark(
        "12_security_self_cert",
        "AndroidSecurityCheckNotifier",
        "Self-cert alert",
        postSelfCertAlert()
    )

    private fun postSelfCertAlert(): Notification {
        val notifier = AndroidSecurityCheckNotifier(context)
        notifier.postAlert(
            check = SecurityCheckNotifier.SecurityCheck.SELF_CERT,
            reason = "Fingerprint mismatch on stored certificate"
        )
        return lastPosted()
    }

    @Test
    fun securityCtLogAlertLight() = captureLight(
        "13_security_ct_log",
        "AndroidSecurityCheckNotifier",
        "CT log alert",
        postCtLogAlert()
    )

    @Test
    fun securityCtLogAlertDark() = captureDark(
        "13_security_ct_log",
        "AndroidSecurityCheckNotifier",
        "CT log alert",
        postCtLogAlert()
    )

    private fun postCtLogAlert(): Notification {
        val notifier = AndroidSecurityCheckNotifier(context)
        notifier.postAlert(
            check = SecurityCheckNotifier.SecurityCheck.CT_LOG,
            reason = "Unauthorized certificate issued for device subdomain"
        )
        return lastPosted()
    }

    // =========================================================================
    // Fakes & helpers
    // =========================================================================

    private class FakeSettingsProvider(private val mode: PostSessionMode) :
        NotificationSettingsProvider {
        private val current = NotificationSettings(
            postSessionMode = mode,
            notificationPermissionGranted = true,
            showAllMcpMessages = false
        )

        override suspend fun settings(): NotificationSettings = current
        override fun observeSettings(): Flow<NotificationSettings> = flowOf(current)
        override suspend fun setPostSessionMode(mode: PostSessionMode) = Unit
        override suspend fun setShowAllMcpMessages(enabled: Boolean) = Unit
    }

    private class FakeAuditDao(
        private val latest: Long,
        private val afterCursor: List<AuditEntry>
    ) : AuditDao {
        override suspend fun insert(entry: AuditEntry): Long = 0L
        override suspend fun getById(id: Long): AuditEntry? = null
        override suspend fun queryBySession(sessionId: String): List<AuditEntry> = emptyList()
        override suspend fun queryByDateRange(
            startMillis: Long,
            endMillis: Long,
            provider: String?
        ): List<AuditEntry> = emptyList()

        override fun observeRecent(
            startMillis: Long,
            endMillis: Long,
            limit: Int
        ): Flow<List<AuditEntry>> = flowOf(emptyList())

        override fun observeByDateRange(
            startMillis: Long,
            endMillis: Long,
            provider: String?
        ): Flow<List<AuditEntry>> = flowOf(emptyList())

        override suspend fun deleteOlderThan(cutoffMillis: Long): Int = 0
        override suspend fun count(): Int = afterCursor.size
        override suspend fun latestId(): Long? = latest
        override suspend fun queryCreatedAfter(sinceId: Long): List<AuditEntry> = afterCursor
    }

    private fun sampleToolCallEvent(): ToolCallEvent = ToolCallEvent(
        sessionId = "session-001",
        providerId = "health-connect",
        timestamp = FIXED_TIMESTAMP_MILLIS,
        toolName = "get_steps",
        arguments = emptyMap(),
        result = CallToolResult(content = listOf(TextContent("ok"))),
        durationMs = 12L
    )

    private fun sampleEntry(id: Long, toolName: String, provider: String): AuditEntry = AuditEntry(
        id = id,
        sessionId = "session-001",
        toolName = toolName,
        provider = provider,
        timestampMillis = FIXED_TIMESTAMP_MILLIS,
        durationMillis = 12L,
        success = true
    )

    /** Placeholder receiver referenced by [AuthRequestNotifier]'s PendingIntents. */
    class DummyReceiver : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context, intent: Intent) = Unit
    }

    /** Placeholder activity referenced by notifier `contentIntent` targets. */
    class DummyActivity : android.app.Activity()

    companion object {
        // Fixed timestamp so per-tool-call "time text" is stable across runs.
        // 2026-04-17T12:34:56Z expressed in millis since epoch.
        private const val FIXED_TIMESTAMP_MILLIS = 1_776_602_096_000L

        // Short alias for the foreground-notifier variant column.
        private const val FGS = "ForegroundNotifier"

        /**
         * After all tests in this class have run, write the captured
         * metadata out as [docs/notifications/index.html]. The test working
         * directory is the `:notifications` module, so the doc path is
         * resolved relative to that.
         */
        @ClassRule
        @JvmField
        val htmlCatalogWriter: ExternalResource = object : ExternalResource() {
            override fun before() {
                CapturedNotifications.clear()
            }

            override fun after() {
                val cases = CapturedNotifications.snapshot()
                if (cases.isEmpty()) return
                val output = File("../docs/notifications/index.html")
                NotificationCatalogHtmlWriter.write(output, cases)
            }
        }
    }
}
