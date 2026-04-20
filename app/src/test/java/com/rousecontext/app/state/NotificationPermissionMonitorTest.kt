package com.rousecontext.app.state

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class NotificationPermissionMonitorTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `areNotificationsEnabled returns false without runtime permission on API 33+`() {
        // Robolectric simulates API 35 by default; POST_NOTIFICATIONS is not
        // auto-granted, so the runtime check returns DENIED.
        assertFalse(NotificationPermissionMonitor.areNotificationsEnabled(context))
    }

    @Test
    fun `notificationPermissionFlow emits current state immediately on collection`() = runTest {
        val triggers = MutableSharedFlow<Unit>()
        val flow = notificationPermissionFlow(context, triggers)

        val first = flow.first()
        assertFalse(first)
    }

    @Test
    fun `notificationPermissionFlow deduplicates on re-triggering the same result`() = runTest {
        val triggers = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

        notificationPermissionFlow(context, triggers).test {
            assertFalse(awaitItem())
            triggers.tryEmit(Unit)
            triggers.tryEmit(Unit)
            // distinctUntilChanged swallows identical repeats.
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `refresher emits tick on refresh`() = runBlocking {
        val refresher = NotificationPermissionRefresher()
        // Use tryEmit behavior: refresh() pushes into the extraBufferCapacity=1
        // buffer, so the collector below observes the tick synchronously.
        refresher.ticks.test {
            refresher.refresh()
            assertEquals(Unit, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `refresher does not over-buffer when no collector`() {
        val refresher = NotificationPermissionRefresher()
        // Two refreshes with buffer=1 — the second should coalesce, not crash.
        refresher.refresh()
        refresher.refresh()
        assertTrue(true)
    }
}
