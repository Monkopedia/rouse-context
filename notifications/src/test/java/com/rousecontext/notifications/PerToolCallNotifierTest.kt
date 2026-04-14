package com.rousecontext.notifications

import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.rousecontext.api.NotificationSettings
import com.rousecontext.api.NotificationSettingsProvider
import com.rousecontext.api.PostSessionMode
import com.rousecontext.mcp.core.ToolCallEvent
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
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

    class DummyActivity : android.app.Activity()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        manager = context.getSystemService(NotificationManager::class.java)
        NotificationChannels.createAll(context)
        settings = FakeNotificationSettingsProvider(PostSessionMode.EACH_USAGE)
        notifier = PerToolCallNotifier(
            context = context,
            settingsProvider = settings,
            integrationDisplayNames = mapOf(
                "health" to "Health Connect",
                "usage" to "App Usage"
            ),
            activityClass = DummyActivity::class.java
        )
    }

    @Test
    fun `EACH_USAGE mode posts a notification for a tool call`() {
        notifier.notifyIfEnabled(event(provider = "health", toolName = "get_steps"))

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

        notifier.notifyIfEnabled(event(provider = "health", toolName = "get_steps"))

        val shadow = Shadows.shadowOf(manager)
        assertTrue(
            "Summary mode should not post per-call notifications",
            shadow.allNotifications.isEmpty()
        )
    }

    @Test
    fun `SUPPRESS mode posts nothing`() {
        settings.mode = PostSessionMode.SUPPRESS

        notifier.notifyIfEnabled(event(provider = "health", toolName = "get_steps"))

        val shadow = Shadows.shadowOf(manager)
        assertTrue(
            "Suppress mode should not post per-call notifications",
            shadow.allNotifications.isEmpty()
        )
    }

    @Test
    fun `Multiple calls post distinct grouped notifications`() {
        notifier.notifyIfEnabled(event(provider = "health", toolName = "get_steps"))
        notifier.notifyIfEnabled(event(provider = "usage", toolName = "get_app_usage"))
        notifier.notifyIfEnabled(event(provider = "health", toolName = "get_hr"))

        val shadow = Shadows.shadowOf(manager)
        assertEquals(
            "Each call should produce its own notification",
            3,
            shadow.allNotifications.size
        )
        shadow.allNotifications.forEach { n ->
            assertEquals(
                PerToolCallNotifier.GROUP_KEY,
                n.group
            )
        }
    }

    @Test
    fun `Falls back to provider id when display name is unknown`() {
        notifier.notifyIfEnabled(event(provider = "unknown", toolName = "do_thing"))

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
        notifier.notifyIfEnabled(event(provider = "health", toolName = "get_steps"))

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
        override val settings: NotificationSettings
            get() = NotificationSettings(
                postSessionMode = mode,
                notificationPermissionGranted = true
            )

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
