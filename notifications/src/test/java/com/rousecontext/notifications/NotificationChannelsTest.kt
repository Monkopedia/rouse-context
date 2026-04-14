package com.rousecontext.notifications

import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class NotificationChannelsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val manager = context.getSystemService(NotificationManager::class.java)

    @Test
    fun `createAll registers all channels`() {
        NotificationChannels.createAll(context)

        val channels = manager.notificationChannels
        assertEquals(7, channels.size)
    }

    @Test
    fun `createAll registers foreground channel with low importance`() {
        NotificationChannels.createAll(context)

        val channel = manager.getNotificationChannel(NotificationChannels.FOREGROUND_CHANNEL_ID)
        assertNotNull(channel)
        assertEquals(NotificationManager.IMPORTANCE_LOW, channel.importance)
        assertEquals("Foreground Service", channel.name.toString())
    }

    @Test
    fun `createAll registers session channel with default importance`() {
        NotificationChannels.createAll(context)

        val channel = manager.getNotificationChannel(NotificationChannels.SESSION_CHANNEL_ID)
        assertNotNull(channel)
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, channel.importance)
    }

    @Test
    fun `createAll registers error channel with high importance`() {
        NotificationChannels.createAll(context)

        val channel = manager.getNotificationChannel(NotificationChannels.ERROR_CHANNEL_ID)
        assertNotNull(channel)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, channel.importance)
    }

    @Test
    fun `createAll registers alert channel with high importance`() {
        NotificationChannels.createAll(context)

        val channel = manager.getNotificationChannel(NotificationChannels.ALERT_CHANNEL_ID)
        assertNotNull(channel)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, channel.importance)
    }

    @Test
    fun `createAll registers auth request channel with default importance`() {
        NotificationChannels.createAll(context)

        val channel = manager.getNotificationChannel(NotificationChannels.AUTH_REQUEST_CHANNEL_ID)
        assertNotNull(channel)
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, channel.importance)
        assertEquals("Authorization Requests", channel.name.toString())
    }

    @Test
    fun `createAll registers session summary channel with low importance`() {
        NotificationChannels.createAll(context)

        val channel = manager.getNotificationChannel(
            NotificationChannels.SESSION_SUMMARY_CHANNEL_ID
        )
        assertNotNull(channel)
        assertEquals(NotificationManager.IMPORTANCE_LOW, channel.importance)
        assertEquals("Session Summaries", channel.name.toString())
    }

    @Test
    fun `createAll registers outreach launch channel with default importance`() {
        NotificationChannels.createAll(context)

        val channel = manager.getNotificationChannel(
            NotificationChannels.OUTREACH_LAUNCH_CHANNEL_ID
        )
        assertNotNull(channel)
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, channel.importance)
        assertEquals("Outreach Launch Requests", channel.name.toString())
    }

    @Test
    fun `createAll is idempotent`() {
        NotificationChannels.createAll(context)
        NotificationChannels.createAll(context)

        val channels = manager.notificationChannels
        assertEquals(7, channels.size)
    }
}
