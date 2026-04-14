package com.rousecontext.work

import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.rousecontext.notifications.NotificationChannels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class FgsLimitHandlerTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val manager = context.getSystemService(NotificationManager::class.java)

    @Test
    fun `postLimitReachedNotification posts on FGS limit channel`() {
        NotificationChannels.createAll(context)

        FgsLimitHandler.postLimitReachedNotification(context)

        val active = manager.activeNotifications
        assertEquals(1, active.size)
        val posted = active.single()
        assertEquals(
            NotificationChannels.FGS_LIMIT_CHANNEL_ID,
            posted.notification.channelId
        )
    }

    @Test
    fun `postLimitReachedNotification uses fixed id so repeated posts update same notification`() {
        NotificationChannels.createAll(context)

        FgsLimitHandler.postLimitReachedNotification(context)
        FgsLimitHandler.postLimitReachedNotification(context)
        FgsLimitHandler.postLimitReachedNotification(context)

        val active = manager.activeNotifications
        assertEquals(
            "Repeated posts should replace, not stack",
            1,
            active.size
        )
        assertEquals(FgsLimitHandler.NOTIFICATION_ID, active.single().id)
    }

    @Test
    fun `postLimitReachedNotification uses expected copy`() {
        NotificationChannels.createAll(context)

        FgsLimitHandler.postLimitReachedNotification(context)

        val active = manager.activeNotifications.single()
        val text = active.notification.extras.getCharSequence(
            android.app.Notification.EXTRA_TEXT
        )?.toString().orEmpty()
        assertTrue(
            "Notification text should mention the 6-hour limit: <$text>",
            text.contains("6-hour")
        )
        assertTrue(
            "Notification text should explain wake requests aren't served: <$text>",
            text.contains("Wake requests won't be served")
        )
    }

    @Test
    fun `channel is registered before posting`() {
        // createAll is required — but the handler should not crash if called
        // before channels exist. Calling createAll separately is the integration
        // contract; document that here.
        NotificationChannels.createAll(context)

        val channel = manager.getNotificationChannel(
            NotificationChannels.FGS_LIMIT_CHANNEL_ID
        )
        assertNotNull(channel)
    }
}
