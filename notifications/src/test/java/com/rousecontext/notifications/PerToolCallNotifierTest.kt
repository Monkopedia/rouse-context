package com.rousecontext.notifications

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Parcel
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.test.core.app.ApplicationProvider
import com.rousecontext.api.NotificationSettings
import com.rousecontext.api.NotificationSettingsProvider
import com.rousecontext.api.PostSessionMode
import com.rousecontext.mcp.core.ToolCallEvent
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.shadows.ShadowPendingIntent

/**
 * Unit tests for [PerToolCallNotifier].
 *
 * Verifies per-tool-call notification behavior for each [PostSessionMode] and
 * the locked-design copy from issue #347:
 *
 *  - Title: `"${clientLabel} used ${humanize(toolName)}"`.
 *  - Body: integration display name only (no timestamp; the shade renders it
 *    natively via [Notification.when]).
 *  - Tap deep-links to the audit-history screen with a scroll-to-call extra.
 *  - Action button `Manage` deep-links to the integration manage page.
 */
@RunWith(RobolectricTestRunner::class)
class PerToolCallNotifierTest {

    private lateinit var context: Context
    private lateinit var manager: NotificationManager
    private lateinit var settings: FakeNotificationSettingsProvider
    private lateinit var notifier: PerToolCallNotifier
    private lateinit var scopeJob: Job
    private lateinit var scope: CoroutineScope
    private lateinit var counterStore: DataStore<Preferences>

    @get:Rule
    val tmpDir = TemporaryFolder()

    class DummyActivity : android.app.Activity()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        manager = context.getSystemService(NotificationManager::class.java)
        NotificationChannels.createAll(context)
        settings = FakeNotificationSettingsProvider(PostSessionMode.EACH_USAGE)
        scopeJob = SupervisorJob()
        scope = CoroutineScope(Dispatchers.IO + scopeJob)
        counterStore = PreferenceDataStoreFactory.create(scope = scope) {
            File(tmpDir.root, "counter.preferences_pb")
        }
        notifier = PerToolCallNotifier(
            context = context,
            settingsProvider = settings,
            integrationDisplayNames = mapOf(
                "health" to "Health Connect",
                "usage" to "App Usage"
            ),
            activityClass = DummyActivity::class.java,
            idCounter = NotificationIdCounter(counterStore)
        )
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `EACH_USAGE mode posts a notification for a tool call`() {
        runBlocking {
            notifier.notifyIfEnabled(
                event(provider = "health", toolName = "get_steps", clientLabel = "Claude")
            )
        }

        val shadow = Shadows.shadowOf(manager)
        val posted = shadow.allNotifications
        assertEquals("Exactly one notification should be posted", 1, posted.size)
        val notification = posted.first()
        assertEquals(
            NotificationChannels.SESSION_SUMMARY_CHANNEL_ID,
            notification.channelId
        )
        val title = notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val text = notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        assertEquals(
            "Title should be '\${client} used \${humanized tool}'",
            "Claude used Get Steps",
            title
        )
        assertEquals(
            "Body should be integration display name only (no timestamp suffix)",
            "Health Connect",
            text
        )
    }

    @Test
    fun `Unknown client renders as 'Unknown (#N) used Get Steps'`() {
        runBlocking {
            notifier.notifyIfEnabled(
                event(provider = "health", toolName = "get_steps", clientLabel = "Unknown (#1)")
            )
        }

        val shadow = Shadows.shadowOf(manager)
        val notification = shadow.allNotifications.first()
        val title = notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        assertEquals("Unknown (#1) used Get Steps", title)
    }

    @Test
    fun `setWhen uses the event timestamp, not the post time`() {
        val eventTs = 1_700_000_000_000L
        runBlocking {
            notifier.notifyIfEnabled(
                event(
                    provider = "health",
                    toolName = "get_steps",
                    clientLabel = "Claude",
                    timestamp = eventTs
                )
            )
        }

        val shadow = Shadows.shadowOf(manager)
        val notification = shadow.allNotifications.first()
        assertEquals(
            "setWhen should use the event timestamp so the shade renders the call time",
            eventTs,
            notification.`when`
        )
    }

    @Test
    fun `Tap PendingIntent carries scrollToCallId extra`() {
        runBlocking {
            notifier.notifyIfEnabled(
                event(provider = "health", toolName = "get_steps", clientLabel = "Claude")
            )
        }

        val shadow = Shadows.shadowOf(manager)
        val notification = shadow.allNotifications.first()
        val contentIntent = notification.contentIntent
        assertNotNull("Tap PendingIntent should be set", contentIntent)
        val shadowPending = Shadows.shadowOf(contentIntent)
        val wrappedIntent = shadowPending.savedIntent
        assertEquals(
            "Tap action should deep-link to the audit screen",
            PerToolCallNotifier.ACTION_OPEN_AUDIT_HISTORY,
            wrappedIntent.action
        )
        assertTrue(
            "Tap intent should carry a scrollToCallId extra",
            wrappedIntent.hasExtra(PerToolCallNotifier.EXTRA_SCROLL_TO_CALL_ID)
        )
    }

    @Test
    fun `Manage action deep-links to the integration manage page`() {
        runBlocking {
            notifier.notifyIfEnabled(
                event(provider = "usage", toolName = "get_app_usage", clientLabel = "Claude")
            )
        }

        val shadow = Shadows.shadowOf(manager)
        val notification = shadow.allNotifications.first()
        val actions = notification.actions
        assertNotNull("Manage action should be present", actions)
        assertEquals("Exactly one action button (Manage)", 1, actions.size)
        val action = actions.first()
        assertEquals("Manage", action.title.toString())
        val shadowPending: ShadowPendingIntent = Shadows.shadowOf(action.actionIntent)
        val intent = shadowPending.savedIntent
        assertEquals(
            "Manage action should deep-link to the integration manage screen",
            PerToolCallNotifier.ACTION_OPEN_INTEGRATION_MANAGE,
            intent.action
        )
        assertEquals(
            "Manage intent should carry the tool's integration id",
            "usage",
            intent.getStringExtra(PerToolCallNotifier.EXTRA_INTEGRATION_ID)
        )
    }

    @Test
    fun `SUMMARY mode posts nothing`() {
        settings.mode = PostSessionMode.SUMMARY

        runBlocking { notifier.notifyIfEnabled(event(provider = "health", toolName = "get_steps")) }

        val shadow = Shadows.shadowOf(manager)
        assertTrue(
            "Summary mode should not post per-call notifications",
            shadow.allNotifications.isEmpty()
        )
    }

    @Test
    fun `SUPPRESS mode posts nothing`() {
        settings.mode = PostSessionMode.SUPPRESS

        runBlocking { notifier.notifyIfEnabled(event(provider = "health", toolName = "get_steps")) }

        val shadow = Shadows.shadowOf(manager)
        assertTrue(
            "Suppress mode should not post per-call notifications",
            shadow.allNotifications.isEmpty()
        )
    }

    @Test
    fun `Multiple calls post distinct ungrouped notifications`() {
        runBlocking { notifier.notifyIfEnabled(event(provider = "health", toolName = "get_steps")) }
        runBlocking {
            notifier.notifyIfEnabled(event(provider = "usage", toolName = "get_app_usage"))
        }
        runBlocking {
            notifier.notifyIfEnabled(event(provider = "health", toolName = "get_heart_rate"))
        }

        val shadow = Shadows.shadowOf(manager)
        assertEquals(
            "Each call should produce its own notification",
            3,
            shadow.allNotifications.size
        )
        // Per issue #331: no group key — Android collapses grouped children when
        // there's no explicit summary notification, so dropping the group makes
        // each per-call notification stand alone in the shade.
        shadow.allNotifications.forEach { n ->
            assertNull("Per-call notification should not set a group", n.group)
        }
    }

    @Test
    fun `Cold start with same backing store keeps notification ids distinct`() {
        // First process: post two notifications via the initial notifier.
        runBlocking {
            notifier.notifyIfEnabled(event(provider = "health", toolName = "get_steps"))
            notifier.notifyIfEnabled(event(provider = "health", toolName = "get_heart_rate"))
        }

        // Simulate cold start: release the first DataStore (only one active
        // instance per file is permitted, enforced by a static `activeFiles`
        // set in FileStorage), then open a fresh one from the same backing
        // file as a new process would. Cancellation is asynchronous — the
        // connection's invokeOnCompletion handler removes the file from
        // `activeFiles`, so we must join the scope's job before opening a
        // new DataStore pointed at the same path.
        runBlocking {
            scope.cancel()
            scopeJob.join()
        }
        val coldStartScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        try {
            val reopenedStore = PreferenceDataStoreFactory.create(scope = coldStartScope) {
                File(tmpDir.root, "counter.preferences_pb")
            }
            val reopenedNotifier = PerToolCallNotifier(
                context = context,
                settingsProvider = settings,
                integrationDisplayNames = mapOf("health" to "Health Connect"),
                activityClass = DummyActivity::class.java,
                idCounter = NotificationIdCounter(reopenedStore)
            )

            runBlocking {
                reopenedNotifier.notifyIfEnabled(
                    event(provider = "health", toolName = "get_sleep")
                )
            }

            val shadow = Shadows.shadowOf(manager)
            assertEquals(
                "Post-cold-start notification must not overwrite a previous id",
                3,
                shadow.allNotifications.size
            )
            val titles = shadow.allNotifications.mapNotNull {
                it.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
            }
            assertTrue(
                "Expected pre-cold-start entries kept",
                titles.any { it.contains("Get Steps") }
            )
            assertTrue(
                "Expected pre-cold-start entries kept",
                titles.any { it.contains("Get Heart Rate") }
            )
            assertTrue(
                "Expected post-cold-start entry present",
                titles.any { it.contains("Get Sleep") }
            )
        } finally {
            runBlocking {
                coldStartScope.cancel()
                coldStartScope.coroutineContext.job.join()
            }
        }
    }

    @Test
    fun `Notification has no group key`() {
        runBlocking { notifier.notifyIfEnabled(event(provider = "health", toolName = "get_steps")) }

        val shadow = Shadows.shadowOf(manager)
        val notification = shadow.allNotifications.first()
        assertNull("Per-call notification should not set a group", notification.group)
    }

    @Test
    fun `Falls back to provider id when display name is unknown`() {
        runBlocking { notifier.notifyIfEnabled(event(provider = "other", toolName = "do_thing")) }

        val shadow = Shadows.shadowOf(manager)
        val notification = shadow.allNotifications.firstOrNull()
        assertNotNull(notification)
        val text = notification!!.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        assertEquals(
            "Body should fall back to raw provider id when display name is unknown",
            "other",
            text
        )
    }

    @Test
    fun `Notification is auto-cancel`() {
        runBlocking { notifier.notifyIfEnabled(event(provider = "health", toolName = "get_steps")) }

        val shadow = Shadows.shadowOf(manager)
        val notification = shadow.allNotifications.first()
        assertFalse(
            "Should auto-cancel on tap",
            (notification.flags and Notification.FLAG_AUTO_CANCEL) == 0
        )
    }

    @Suppress("unused") // referenced via test parcel inspection if needed
    private fun dump(intent: Intent): String {
        val p = Parcel.obtain()
        intent.writeToParcel(p, 0)
        val out = "action=${intent.action} keys=${intent.extras?.keySet()?.joinToString()}"
        p.recycle()
        return "sdk=${Build.VERSION.SDK_INT} $out"
    }

    private fun event(
        provider: String,
        toolName: String,
        sessionId: String = "session-1",
        timestamp: Long = System.currentTimeMillis(),
        clientLabel: String = "Claude"
    ): ToolCallEvent = ToolCallEvent(
        sessionId = sessionId,
        providerId = provider,
        timestamp = timestamp,
        toolName = toolName,
        arguments = emptyMap(),
        result = CallToolResult(content = listOf(TextContent("ok"))),
        durationMs = 5L,
        clientLabel = clientLabel
    )

    private class FakeNotificationSettingsProvider(initial: PostSessionMode) :
        NotificationSettingsProvider {
        var mode: PostSessionMode = initial
        private fun currentSettings(): NotificationSettings = NotificationSettings(
            postSessionMode = mode,
            notificationPermissionGranted = true
        )

        override suspend fun settings(): NotificationSettings = currentSettings()

        override fun observeSettings(): kotlinx.coroutines.flow.Flow<NotificationSettings> =
            kotlinx.coroutines.flow.flowOf(currentSettings())

        override suspend fun setPostSessionMode(mode: PostSessionMode) {
            this.mode = mode
        }

        override suspend fun setShowAllMcpMessages(enabled: Boolean) = Unit
    }
}
