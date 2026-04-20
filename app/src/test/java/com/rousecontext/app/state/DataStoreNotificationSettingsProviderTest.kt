package com.rousecontext.app.state

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import com.rousecontext.api.PostSessionMode
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DataStoreNotificationSettingsProviderTest {

    private lateinit var context: Context
    private lateinit var provider: DataStoreNotificationSettingsProvider

    @Before
    fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        provider = DataStoreNotificationSettingsProvider(context)
        // Normalize baseline — DataStore instance is process-wide and
        // values can leak from prior tests.
        provider.setPostSessionMode(PostSessionMode.SUMMARY)
        provider.setShowAllMcpMessages(false)
    }

    @Test
    fun `baseline settings reflect explicit defaults`() = runTest {
        val settings = provider.settings()
        assertEquals(PostSessionMode.SUMMARY, settings.postSessionMode)
        assertFalse(settings.showAllMcpMessages)
        // Robolectric has notifications enabled globally but no runtime
        // POST_NOTIFICATIONS permission granted on API 33+.
        assertFalse(settings.notificationPermissionGranted)
    }

    @Test
    fun `setPostSessionMode persists each enum value`() = runTest {
        provider.setPostSessionMode(PostSessionMode.EACH_USAGE)
        assertEquals(PostSessionMode.EACH_USAGE, provider.settings().postSessionMode)

        provider.setPostSessionMode(PostSessionMode.SUPPRESS)
        assertEquals(PostSessionMode.SUPPRESS, provider.settings().postSessionMode)

        provider.setPostSessionMode(PostSessionMode.SUMMARY)
        assertEquals(PostSessionMode.SUMMARY, provider.settings().postSessionMode)
    }

    @Test
    fun `setShowAllMcpMessages persists value`() = runTest {
        provider.setShowAllMcpMessages(true)
        assertTrue(provider.settings().showAllMcpMessages)

        provider.setShowAllMcpMessages(false)
        assertFalse(provider.settings().showAllMcpMessages)
    }

    @Test
    fun `observeSettings emits initial and every change`() = runTest {
        provider.observeSettings().test {
            val first = awaitItem()
            assertEquals(PostSessionMode.SUMMARY, first.postSessionMode)

            provider.setPostSessionMode(PostSessionMode.SUPPRESS)
            val second = awaitItem()
            assertEquals(PostSessionMode.SUPPRESS, second.postSessionMode)

            provider.setShowAllMcpMessages(true)
            val third = awaitItem()
            assertTrue(third.showAllMcpMessages)
            assertEquals(PostSessionMode.SUPPRESS, third.postSessionMode)

            cancelAndIgnoreRemainingEvents()
        }
    }
}
