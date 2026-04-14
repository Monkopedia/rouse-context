package com.rousecontext.notifications

import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.rousecontext.api.NotificationSettings
import com.rousecontext.api.NotificationSettingsProvider
import com.rousecontext.api.PostSessionMode
import com.rousecontext.notifications.audit.AuditDao
import com.rousecontext.notifications.audit.AuditEntry
import com.rousecontext.tunnel.TunnelState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows

/**
 * Unit tests for [SessionSummaryPoster].
 *
 * Verifies session-end summary notification behavior for each [PostSessionMode].
 */
@RunWith(RobolectricTestRunner::class)
class SessionSummaryPosterTest {

    private lateinit var context: Context
    private lateinit var manager: NotificationManager
    private lateinit var dao: FakeAuditDao
    private lateinit var settings: FakeNotificationSettingsProvider
    private lateinit var poster: SessionSummaryPoster

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        manager = context.getSystemService(NotificationManager::class.java)
        NotificationChannels.createAll(context)
        dao = FakeAuditDao()
        settings = FakeNotificationSettingsProvider(PostSessionMode.SUMMARY)
        poster = SessionSummaryPoster(
            context = context,
            auditDao = dao,
            settingsProvider = settings,
            activityClass = DummyActivity::class.java
        )
    }

    @Test
    fun `Summary mode posts one notification on session end with tool call counts`() = runBlocking {
        settings.mode = PostSessionMode.SUMMARY
        val states = MutableSharedFlow<TunnelState>(replay = 16, extraBufferCapacity = 16)
        val posted = CompletableDeferred<Unit>()
        dao.onQueryCreatedAfter = { posted.complete(Unit) }

        val job = launch { poster.observe(states) }

        states.emit(TunnelState.CONNECTING)
        states.emit(TunnelState.CONNECTED)
        states.emit(TunnelState.ACTIVE)

        // Let the poster capture its cursor before we insert entries.
        awaitLatestId()

        dao.insert(entry(provider = "health", toolName = "get_steps"))
        dao.insert(entry(provider = "health", toolName = "get_hr"))
        dao.insert(entry(provider = "usage", toolName = "get_app_usage"))

        // Post fires on stream drain (ACTIVE -> CONNECTED), NOT on DISCONNECTED.
        // See fix #100 — DISCONNECTED races with service teardown.
        states.emit(TunnelState.CONNECTED)

        withTimeout(TIMEOUT_MS) { posted.await() }

        val shadow = Shadows.shadowOf(manager)
        val notification = shadow.getNotification(SessionSummaryPoster.NOTIFICATION_ID)
        assertNotNull("Summary notification should be posted", notification)
        assertEquals(
            NotificationChannels.SESSION_SUMMARY_CHANNEL_ID,
            notification.channelId
        )
        val text = notification.extras.getCharSequence("android.text")?.toString() ?: ""
        val title = notification.extras.getCharSequence("android.title")?.toString() ?: ""
        val all = "$title $text"
        assertTrue("Expected 3 tool calls in text, was: $all", all.contains("3"))
        assertTrue("Expected health count in text, was: $all", all.contains("health"))
        assertTrue("Expected usage count in text, was: $all", all.contains("usage"))

        job.cancel()
        coroutineContext.cancelChildren()
    }

    @Test
    fun `Suppress mode posts no notification on session end`() = runBlocking {
        settings.mode = PostSessionMode.SUPPRESS
        val states = MutableSharedFlow<TunnelState>(replay = 16, extraBufferCapacity = 16)
        val queried = CompletableDeferred<Unit>()
        dao.onQueryCreatedAfter = { queried.complete(Unit) }

        val job = launch { poster.observe(states) }

        states.emit(TunnelState.ACTIVE)
        awaitLatestId()
        dao.insert(entry(provider = "health", toolName = "get_steps"))
        states.emit(TunnelState.CONNECTED)

        withTimeout(TIMEOUT_MS) { queried.await() }

        val shadow = Shadows.shadowOf(manager)
        assertNull(
            "Suppress mode should not post a notification",
            shadow.getNotification(SessionSummaryPoster.NOTIFICATION_ID)
        )

        job.cancel()
        coroutineContext.cancelChildren()
    }

    @Test
    fun `EachUsage mode does not post session-end summary`() = runBlocking {
        settings.mode = PostSessionMode.EACH_USAGE
        val states = MutableSharedFlow<TunnelState>(replay = 16, extraBufferCapacity = 16)
        val queried = CompletableDeferred<Unit>()
        dao.onQueryCreatedAfter = { queried.complete(Unit) }

        val job = launch { poster.observe(states) }

        states.emit(TunnelState.ACTIVE)
        awaitLatestId()
        dao.insert(entry(provider = "health", toolName = "get_steps"))
        states.emit(TunnelState.CONNECTED)

        withTimeout(TIMEOUT_MS) { queried.await() }

        val shadow = Shadows.shadowOf(manager)
        assertNull(
            "EachUsage mode should not post a session-end summary",
            shadow.getNotification(SessionSummaryPoster.NOTIFICATION_ID)
        )

        job.cancel()
        coroutineContext.cancelChildren()
    }

    @Test
    fun `Summary mode posts nothing when no tool calls occurred`() = runBlocking {
        settings.mode = PostSessionMode.SUMMARY
        val states = MutableSharedFlow<TunnelState>(replay = 16, extraBufferCapacity = 16)
        val queried = CompletableDeferred<Unit>()
        dao.onQueryCreatedAfter = { queried.complete(Unit) }

        val job = launch { poster.observe(states) }

        states.emit(TunnelState.ACTIVE)
        awaitLatestId()
        states.emit(TunnelState.CONNECTED)

        withTimeout(TIMEOUT_MS) { queried.await() }

        val shadow = Shadows.shadowOf(manager)
        assertNull(
            "No notification should be posted when there were no tool calls",
            shadow.getNotification(SessionSummaryPoster.NOTIFICATION_ID)
        )

        job.cancel()
        coroutineContext.cancelChildren()
    }

    @Test
    fun `Summary mode only counts entries from the current session`() = runBlocking {
        settings.mode = PostSessionMode.SUMMARY
        val states = MutableSharedFlow<TunnelState>(replay = 16, extraBufferCapacity = 16)
        val queried = CompletableDeferred<Unit>()
        dao.onQueryCreatedAfter = { queried.complete(Unit) }

        // Pre-existing entries from prior sessions
        dao.insert(entry(provider = "health", toolName = "old1"))
        dao.insert(entry(provider = "health", toolName = "old2"))

        val job = launch { poster.observe(states) }

        states.emit(TunnelState.ACTIVE)
        awaitLatestId()

        // Only new calls during the session
        dao.insert(entry(provider = "notifications", toolName = "read_latest"))

        states.emit(TunnelState.CONNECTED)

        withTimeout(TIMEOUT_MS) { queried.await() }

        val shadow = Shadows.shadowOf(manager)
        val notification = shadow.getNotification(SessionSummaryPoster.NOTIFICATION_ID)
        assertNotNull(notification)
        val text = notification.extras.getCharSequence("android.text")?.toString() ?: ""
        val title = notification.extras.getCharSequence("android.title")?.toString() ?: ""
        val all = "$title $text"
        assertTrue("Should count 1 tool call (current session only), was: $all", all.contains("1"))
        assertTrue(
            "Should mention notifications provider, was: $all",
            all.contains("notifications")
        )
        assertTrue(
            "Should not reference 'health' provider (was a prior session), was: $all",
            !all.contains("health")
        )

        job.cancel()
        coroutineContext.cancelChildren()
    }

    @Test
    fun `No post when tunnel goes ACTIVE to DISCONNECTED without stream drain`() = runBlocking {
        settings.mode = PostSessionMode.SUMMARY
        val states = MutableSharedFlow<TunnelState>(replay = 16, extraBufferCapacity = 16)

        val job = launch { poster.observe(states) }

        states.emit(TunnelState.ACTIVE)
        awaitLatestId()
        dao.insert(entry(provider = "health", toolName = "get_steps"))

        // No CONNECTED drain — tunnel dies straight from ACTIVE.
        states.emit(TunnelState.DISCONNECTED)

        // Flush: start a second session and wait for its latestId read.
        // That proves the collector has processed the DISCONNECTED emission
        // (and re-armed its cursor).
        states.emit(TunnelState.ACTIVE)
        awaitLatestId()

        val shadow = Shadows.shadowOf(manager)
        assertNull(
            "No notification should be posted when we skip the drain transition",
            shadow.getNotification(SessionSummaryPoster.NOTIFICATION_ID)
        )
        assertEquals(
            "queryCreatedAfter should never be called when there is no drain",
            0,
            dao.queryCreatedAfterCount
        )

        job.cancel()
        coroutineContext.cancelChildren()
    }

    @Test
    fun `Summary mode posts once per connection cycle even with repeated drains`() = runBlocking {
        settings.mode = PostSessionMode.SUMMARY
        val states = MutableSharedFlow<TunnelState>(replay = 16, extraBufferCapacity = 16)
        val firstPosted = CompletableDeferred<Unit>()
        dao.onQueryCreatedAfter = { firstPosted.complete(Unit) }

        val job = launch { poster.observe(states) }

        states.emit(TunnelState.ACTIVE)
        awaitLatestId()
        dao.insert(entry(provider = "health", toolName = "get_steps"))

        // First drain — fires the post.
        states.emit(TunnelState.CONNECTED)
        withTimeout(TIMEOUT_MS) { firstPosted.await() }

        val shadow = Shadows.shadowOf(manager)
        assertNotNull(shadow.getNotification(SessionSummaryPoster.NOTIFICATION_ID))

        // Client reopens streams and drains again within the same connection
        // cycle. Design choice (fix #100): cursor resets only on the first drain;
        // subsequent drains in the same connection cycle do NOT re-post. This
        // avoids notification spam when clients churn streams.
        states.emit(TunnelState.ACTIVE)
        dao.insert(entry(provider = "health", toolName = "get_hr"))
        states.emit(TunnelState.CONNECTED)

        // Flush: emit a DISCONNECTED -> ACTIVE cycle and wait for the new cursor
        // capture. If the poster had re-fired mid-cycle, the count would be > 1.
        states.emit(TunnelState.DISCONNECTED)
        states.emit(TunnelState.ACTIVE)
        awaitLatestId()

        assertEquals(
            "Should post exactly once within a single connection cycle",
            1,
            dao.queryCreatedAfterCount
        )

        job.cancel()
        coroutineContext.cancelChildren()
    }

    @Test
    fun `Summary mode posts again on a new connection cycle`() = runBlocking {
        settings.mode = PostSessionMode.SUMMARY
        val states = MutableSharedFlow<TunnelState>(replay = 16, extraBufferCapacity = 16)
        val firstPosted = CompletableDeferred<Unit>()
        val secondPosted = CompletableDeferred<Unit>()
        dao.onQueryCreatedAfter = {
            if (!firstPosted.isCompleted) {
                firstPosted.complete(Unit)
            } else if (!secondPosted.isCompleted) {
                secondPosted.complete(Unit)
            }
        }

        val job = launch { poster.observe(states) }

        // Cycle 1
        states.emit(TunnelState.ACTIVE)
        awaitLatestId()
        dao.insert(entry(provider = "health", toolName = "get_steps"))
        states.emit(TunnelState.CONNECTED)
        withTimeout(TIMEOUT_MS) { firstPosted.await() }

        // Full tunnel disconnect + reconnect.
        states.emit(TunnelState.DISCONNECTED)

        // Cycle 2
        states.emit(TunnelState.ACTIVE)
        awaitLatestId()
        dao.insert(entry(provider = "usage", toolName = "get_app_usage"))
        states.emit(TunnelState.CONNECTED)
        withTimeout(TIMEOUT_MS) { secondPosted.await() }

        assertEquals(
            "Should post once per connection cycle",
            2,
            dao.queryCreatedAfterCount
        )

        job.cancel()
        coroutineContext.cancelChildren()
    }

    /**
     * Waits until the poster's `latestId()` read has occurred. This is how we
     * know the cursor has been captured and we can safely insert new entries
     * that should count toward this session.
     */
    private suspend fun awaitLatestId() {
        withTimeout(TIMEOUT_MS) { dao.latestIdCalled.await() }
        dao.resetLatestIdSignal()
    }

    private fun entry(provider: String, toolName: String, sessionId: String = "session-1") =
        AuditEntry(
            sessionId = sessionId,
            toolName = toolName,
            provider = provider,
            timestampMillis = System.currentTimeMillis(),
            durationMillis = 5L,
            success = true
        )

    class DummyActivity : android.app.Activity()

    private class FakeAuditDao : AuditDao {
        private val entries = mutableListOf<AuditEntry>()
        private var nextId = 1L

        @Volatile
        var latestIdCalled: CompletableDeferred<Unit> = CompletableDeferred()

        @Volatile
        var onQueryCreatedAfter: (() -> Unit)? = null

        fun resetLatestIdSignal() {
            latestIdCalled = CompletableDeferred()
        }

        override suspend fun insert(entry: AuditEntry): Long {
            val id = nextId++
            entries.add(entry.copy(id = id))
            return id
        }

        override suspend fun getById(id: Long): AuditEntry? = entries.find { it.id == id }

        override suspend fun queryBySession(sessionId: String): List<AuditEntry> =
            entries.filter { it.sessionId == sessionId }.sortedBy { it.timestampMillis }

        override suspend fun queryByDateRange(
            startMillis: Long,
            endMillis: Long,
            provider: String?
        ): List<AuditEntry> = entries.filter {
            it.timestampMillis in startMillis..endMillis &&
                (provider == null || it.provider == provider)
        }.sortedByDescending { it.timestampMillis }

        override fun observeRecent(startMillis: Long, endMillis: Long, limit: Int) =
            throw UnsupportedOperationException("not used in these tests")

        override fun observeByDateRange(startMillis: Long, endMillis: Long, provider: String?) =
            throw UnsupportedOperationException("not used in these tests")

        override suspend fun deleteOlderThan(cutoffMillis: Long): Int {
            val removed = entries.count { it.timestampMillis < cutoffMillis }
            entries.removeAll { it.timestampMillis < cutoffMillis }
            return removed
        }

        override suspend fun count(): Int = entries.size

        override suspend fun latestId(): Long? {
            val value = entries.maxByOrNull { it.id }?.id
            latestIdCalled.complete(Unit)
            return value
        }

        @Volatile
        var queryCreatedAfterCount: Int = 0

        override suspend fun queryCreatedAfter(sinceId: Long): List<AuditEntry> {
            val result = entries.filter { it.id > sinceId }.sortedBy { it.id }
            queryCreatedAfterCount += 1
            onQueryCreatedAfter?.invoke()
            return result
        }
    }

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
        private const val TIMEOUT_MS = 5_000L
    }
}
