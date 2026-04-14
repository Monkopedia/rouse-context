package com.rousecontext.notifications

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows

/**
 * Tests for [LaunchRequestNotifier]: when the Outreach provider cannot directly
 * launch an activity (Android 14+ BAL restriction without SYSTEM_ALERT_WINDOW),
 * it posts a notification the user can tap to fire the activity.
 */
@RunWith(RobolectricTestRunner::class)
class LaunchRequestNotifierTest {

    private lateinit var context: Context
    private lateinit var manager: NotificationManager
    private lateinit var notifier: LaunchRequestNotifier

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        manager = context.getSystemService(NotificationManager::class.java)
        NotificationChannels.createAll(context)
        notifier = LaunchRequestNotifier(context)
    }

    @Test
    fun `postLaunchApp creates notification with AI client title and resolved app name`() {
        val pkg = context.packageName
        val expectedLabel = context.packageManager
            .getApplicationLabel(context.applicationInfo).toString()

        val launchIntent = Intent(Intent.ACTION_MAIN).setPackage(pkg)
        val id = notifier.postLaunchApp(launchIntent, pkg)

        val shadow = Shadows.shadowOf(manager)
        val notification = shadow.getNotification(id)
        assertNotNull("Launch-app notification should be posted", notification)
        assertEquals(
            NotificationChannels.OUTREACH_LAUNCH_CHANNEL_ID,
            notification.channelId
        )
        val title = notification.extras.getCharSequence("android.title")?.toString() ?: ""
        assertTrue(
            "Title should mention AI client and the app name (got: $title)",
            title.contains("AI client") && title.contains(expectedLabel)
        )
    }

    @Test
    fun `postLaunchApp falls back to package name if app label cannot be resolved`() {
        val launchIntent = Intent(Intent.ACTION_MAIN).setPackage("com.unknown.missing")
        val id = notifier.postLaunchApp(launchIntent, "com.unknown.missing")

        val shadow = Shadows.shadowOf(manager)
        val notification = shadow.getNotification(id)
        assertNotNull(notification)
        val title = notification.extras.getCharSequence("android.title")?.toString() ?: ""
        assertTrue(
            "Fallback title should include package name",
            title.contains("com.unknown.missing")
        )
    }

    @Test
    fun `postOpenLink creates webpage notification with URL in body`() {
        val url = "https://example.com/very/long/path/that/gets/displayed"
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        val id = notifier.postOpenLink(intent, url)

        val shadow = Shadows.shadowOf(manager)
        val notification = shadow.getNotification(id)
        assertNotNull("Open-link notification should be posted", notification)
        assertEquals(
            NotificationChannels.OUTREACH_LAUNCH_CHANNEL_ID,
            notification.channelId
        )
        val title = notification.extras.getCharSequence("android.title")?.toString() ?: ""
        val body = notification.extras.getCharSequence("android.text")?.toString() ?: ""
        assertTrue(
            "Title should mention AI client + webpage",
            title.contains("AI client") && title.contains("webpage")
        )
        assertTrue("Body should contain the URL", body.contains("example.com"))
    }

    @Test
    fun `each post uses a distinct notification id`() {
        val id1 = notifier.postOpenLink(
            Intent(Intent.ACTION_VIEW, Uri.parse("https://one.example")),
            "https://one.example"
        )
        val id2 = notifier.postOpenLink(
            Intent(Intent.ACTION_VIEW, Uri.parse("https://two.example")),
            "https://two.example"
        )
        assertNotEquals(id1, id2)
        val shadow = Shadows.shadowOf(manager)
        assertNotNull(shadow.getNotification(id1))
        assertNotNull(shadow.getNotification(id2))
    }

    @Test
    fun `posted notification is auto-cancel and has content intent`() {
        val id = notifier.postOpenLink(
            Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com")),
            "https://example.com"
        )
        val shadow = Shadows.shadowOf(manager)
        val notification = shadow.getNotification(id)
        assertNotNull(notification)
        // AUTO_CANCEL flag is set
        assertTrue(
            "Notification should be auto-cancel",
            (notification.flags and android.app.Notification.FLAG_AUTO_CANCEL) != 0
        )
        assertNotNull(
            "Tapping notification should fire the launch PendingIntent",
            notification.contentIntent
        )
    }
}
