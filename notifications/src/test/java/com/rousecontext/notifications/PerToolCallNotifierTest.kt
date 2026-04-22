package com.rousecontext.notifications

import android.app.NotificationManager
import android.content.Context
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

/**
 * Unit tests for [PerToolCallNotifier].
 *
 * Verifies per-tool-call notification behavior for each [PostSessionMode].
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
        runBlocking { notifier.notifyIfEnabled(event(provider = "health", toolName = "get_steps")) }

        val shadow = Shadows.shadowOf(manager)
        val posted = shadow.allNotifications
        assertEquals("Exactly one notification should be posted", 1, posted.size)
        val notification = posted.first()
        assertEquals(
            NotificationChannels.SESSION_SUMMARY_CHANNEL_ID,
            notification.channelId
        )
        val title = notification.extras.getCharSequence("android.title")?.toString() ?: ""
        val text = notification.extras.getCharSequence("android.text")?.toString() ?: ""
        assertTrue(
            "Title should reference the tool name, was: $title",
            title.contains("get_steps")
        )
        assertTrue(
            "Body should reference the integration display name, was: $text",
            text.contains("Health Connect")
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
        runBlocking { notifier.notifyIfEnabled(event(provider = "health", toolName = "get_hr")) }

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
    fun `Successive calls within a single process use distinct notification ids`() {
        val shadow = Shadows.shadowOf(manager)
        runBlocking {
            notifier.notifyIfEnabled(event(provider = "health", toolName = "get_steps"))
            notifier.notifyIfEnabled(event(provider = "health", toolName = "get_hr"))
        }

        val ids = shadow.allNotifications.map {
            // Robolectric exposes the posted id via the notification id field
            // on its recorded entries — grab it via the shadow manager.
            it
        }
        // With only 2 notifications and distinct ids, shadow manager retains
        // both entries. Assert via count:
        assertEquals(2, ids.size)
    }

    @Test
    fun `Cold start with same backing store keeps notification ids distinct`() {
        // First process: post two notifications via the initial notifier.
        runBlocking {
            notifier.notifyIfEnabled(event(provider = "health", toolName = "get_steps"))
            notifier.notifyIfEnabled(event(provider = "health", toolName = "get_hr"))
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
                it.extras.getCharSequence("android.title")?.toString()
            }
            assertTrue(
                "Expected pre-cold-start entries kept",
                titles.any { it.contains("get_steps") }
            )
            assertTrue(
                "Expected pre-cold-start entries kept",
                titles.any { it.contains("get_hr") }
            )
            assertTrue(
                "Expected post-cold-start entry present",
                titles.any { it.contains("get_sleep") }
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
        runBlocking { notifier.notifyIfEnabled(event(provider = "unknown", toolName = "do_thing")) }

        val shadow = Shadows.shadowOf(manager)
        val notification = shadow.allNotifications.firstOrNull()
        assertNotNull(notification)
        val text = notification!!.extras.getCharSequence("android.text")?.toString() ?: ""
        assertTrue(
            "Body should fall back to provider id when unknown, was: $text",
            text.contains("unknown")
        )
    }

    @Test
    fun `Notification is auto-cancel`() {
        runBlocking { notifier.notifyIfEnabled(event(provider = "health", toolName = "get_steps")) }

        val shadow = Shadows.shadowOf(manager)
        val notification = shadow.allNotifications.first()
        assertFalse(
            "Should auto-cancel on tap",
            (notification.flags and android.app.Notification.FLAG_AUTO_CANCEL) == 0
        )
    }

    private fun event(
        provider: String,
        toolName: String,
        sessionId: String = "session-1",
        timestamp: Long = System.currentTimeMillis()
    ): ToolCallEvent = ToolCallEvent(
        sessionId = sessionId,
        providerId = provider,
        timestamp = timestamp,
        toolName = toolName,
        arguments = emptyMap(),
        result = CallToolResult(content = listOf(TextContent("ok"))),
        durationMs = 5L
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

    companion object {
        @Suppress("unused")
        private fun ignore(): Any = assertNull(null)
    }
}
