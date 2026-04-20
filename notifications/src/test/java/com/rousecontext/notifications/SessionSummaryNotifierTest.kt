package com.rousecontext.notifications

import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.rousecontext.api.NotificationSettings
import com.rousecontext.api.NotificationSettingsProvider
import com.rousecontext.api.PostSessionMode
import com.rousecontext.mcp.core.ToolCallEvent
import com.rousecontext.notifications.audit.AuditDao
import com.rousecontext.notifications.audit.AuditEntry
import com.rousecontext.notifications.audit.RoomAuditListener
import com.rousecontext.tunnel.TunnelState
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
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
 * Unit tests for [SessionSummaryNotifier].
 *
 * Verifies session-end summary notification behavior for each [PostSessionMode].
 */
@RunWith(RobolectricTestRunner::class)
class SessionSummaryNotifierTest {

    private lateinit var context: Context
    private lateinit var manager: NotificationManager
    private lateinit var dao: FakeAuditDao
    private lateinit var settings: FakeNotificationSettingsProvider
    private lateinit var notifier: SessionSummaryNotifier

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        manager = context.getSystemService(NotificationManager::class.java)
        NotificationChannels.createAll(context)
        dao = FakeAuditDao()
        settings = FakeNotificationSettingsProvider(PostSessionMode.SUMMARY)
        notifier = SessionSummaryNotifier(
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

        val job = launch { notifier.observe(states) }

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
        val notification = shadow.getNotification(SessionSummaryNotifier.NOTIFICATION_ID)
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

        val job = launch { notifier.observe(states) }

        states.emit(TunnelState.ACTIVE)
        awaitLatestId()
        dao.insert(entry(provider = "health", toolName = "get_steps"))
        states.emit(TunnelState.CONNECTED)

        withTimeout(TIMEOUT_MS) { queried.await() }

        val shadow = Shadows.shadowOf(manager)
        assertNull(
            "Suppress mode should not post a notification",
            shadow.getNotification(SessionSummaryNotifier.NOTIFICATION_ID)
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

        val job = launch { notifier.observe(states) }

        states.emit(TunnelState.ACTIVE)
        awaitLatestId()
        dao.insert(entry(provider = "health", toolName = "get_steps"))
        states.emit(TunnelState.CONNECTED)

        withTimeout(TIMEOUT_MS) { queried.await() }

        val shadow = Shadows.shadowOf(manager)
        assertNull(
            "EachUsage mode should not post a session-end summary",
            shadow.getNotification(SessionSummaryNotifier.NOTIFICATION_ID)
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

        val job = launch { notifier.observe(states) }

        states.emit(TunnelState.ACTIVE)
        awaitLatestId()
        states.emit(TunnelState.CONNECTED)

        withTimeout(TIMEOUT_MS) { queried.await() }

        val shadow = Shadows.shadowOf(manager)
        assertNull(
            "No notification should be posted when there were no tool calls",
            shadow.getNotification(SessionSummaryNotifier.NOTIFICATION_ID)
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

        val job = launch { notifier.observe(states) }

        states.emit(TunnelState.ACTIVE)
        awaitLatestId()

        // Only new calls during the session
        dao.insert(entry(provider = "notifications", toolName = "read_latest"))

        states.emit(TunnelState.CONNECTED)

        withTimeout(TIMEOUT_MS) { queried.await() }

        val shadow = Shadows.shadowOf(manager)
        val notification = shadow.getNotification(SessionSummaryNotifier.NOTIFICATION_ID)
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

        val job = launch { notifier.observe(states) }

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
            shadow.getNotification(SessionSummaryNotifier.NOTIFICATION_ID)
        )
        assertEquals(
            "queryCreatedAfter should never be called when there is no drain",
            0,
            dao.queryCreatedAfterCount
        )

        job.cancel()
        coroutineContext.cancelChildren()
    }

    /**
     * Regression test for issue #324.
     *
     * Symptom: a user had tool uses in the morning but no session-summary
     * notification fired. The post-#244 suspend-insert fix had closed the
     * audit-insert race, so rows were present; something else was dropping
     * the notification.
     *
     * Root cause: when a stream drained with zero audit entries (e.g. an
     * AI client opened a probe stream, listed tools/resources via
     * `tools/list` — which does NOT write to the audit-tool-call table —
     * and closed), [SessionSummaryNotifier] still set the per-cycle
     * `posted` gate to true. Any subsequent stream within the SAME tunnel
     * connection cycle (no full tunnel DISCONNECTED in between) would see
     * `posted = true` and skip arming its cursor, so real tool-call
     * activity in later streams never produced a summary notification.
     *
     * Fix: only burn the `posted` gate when we actually posted a
     * notification. An empty drain (no audit rows since the cursor) must
     * leave `posted = false` so the next ACTIVE can re-arm.
     *
     * Scenario modelled here: one full tunnel cycle, two ACTIVE → CONNECTED
     * drains inside it; the first drain has no entries, the second has
     * entries. Post-fix: one summary notification fires on the second
     * drain. Pre-fix: the probe drain silently burns the gate and no
     * summary ever fires.
     */
    @Test
    fun `Summary posts on later drain when first drain of cycle has no entries`() = runBlocking {
        settings.mode = PostSessionMode.SUMMARY
        val states = MutableSharedFlow<TunnelState>(replay = 16, extraBufferCapacity = 16)
        val postedSignal = CompletableDeferred<Unit>()
        dao.onQueryCreatedAfter = {
            // Second drain of the cycle is the one we expect to post.
            if (dao.queryCreatedAfterCount >= 2 && !postedSignal.isCompleted) {
                postedSignal.complete(Unit)
            }
        }

        val job = launch { notifier.observe(states) }

        // Cycle start. First stream opens...
        states.emit(TunnelState.ACTIVE)
        awaitLatestId()

        // ...and drains with no tool-call rows. This is the "probe" stream
        // (e.g. tools/list only, which doesn't insert into audit_entries).
        states.emit(TunnelState.CONNECTED)

        // Give the observer a turn to process the empty drain. We can't rely
        // on awaitLatestId() here because that's only hit on a cursor arm,
        // and the whole question of this test is whether the next ACTIVE
        // re-arms.
        kotlinx.coroutines.yield()

        // Second stream opens within the same tunnel cycle — no
        // DISCONNECTED in between. Pre-fix, `posted = true` from the empty
        // drain blocks the cursor arm here.
        states.emit(TunnelState.ACTIVE)
        awaitLatestId()
        dao.insert(entry(provider = "health", toolName = "get_steps"))

        states.emit(TunnelState.CONNECTED)
        withTimeout(TIMEOUT_MS) { postedSignal.await() }

        val shadow = Shadows.shadowOf(manager)
        val notification = shadow.getNotification(SessionSummaryNotifier.NOTIFICATION_ID)
        assertNotNull(
            "Summary should post on the second drain when the first drain " +
                "had no entries (issue #324 — empty drain must not burn the " +
                "per-cycle gate)",
            notification
        )
        val title = notification.extras.getCharSequence("android.title")?.toString() ?: ""
        val text = notification.extras.getCharSequence("android.text")?.toString() ?: ""
        val all = "$title $text"
        assertTrue("Expected 1 tool call in summary text, was: $all", all.contains("1"))
        assertTrue("Expected health provider in summary text, was: $all", all.contains("health"))

        job.cancel()
        coroutineContext.cancelChildren()
    }

    @Test
    fun `Summary mode posts once per connection cycle even with repeated drains`() = runBlocking {
        settings.mode = PostSessionMode.SUMMARY
        val states = MutableSharedFlow<TunnelState>(replay = 16, extraBufferCapacity = 16)
        val firstPosted = CompletableDeferred<Unit>()
        dao.onQueryCreatedAfter = { firstPosted.complete(Unit) }

        val job = launch { notifier.observe(states) }

        states.emit(TunnelState.ACTIVE)
        awaitLatestId()
        dao.insert(entry(provider = "health", toolName = "get_steps"))

        // First drain — fires the post.
        states.emit(TunnelState.CONNECTED)
        withTimeout(TIMEOUT_MS) { firstPosted.await() }

        val shadow = Shadows.shadowOf(manager)
        assertNotNull(shadow.getNotification(SessionSummaryNotifier.NOTIFICATION_ID))

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

    /**
     * Regression test for issue #244: the session-summary query on the
     * ACTIVE -> CONNECTED transition used to race a fire-and-forget insert
     * started inside `RoomAuditListener.onToolCall`. On fast hardware the
     * query won, found no entries, and the summary never fired.
     *
     * With the fix, `AuditListener.onToolCall` is `suspend` and the insert
     * runs on the caller's coroutine context — by the time the method
     * returns the row is committed. This test wires a real
     * [RoomAuditListener] in front of the same [FakeAuditDao] the notifier
     * observes, drives a single session through the state machine, and
     * asserts the summary notification is posted.
     */
    @Test
    fun `onToolCall insert is visible to summary query before state transition`() = runBlocking {
        settings.mode = PostSessionMode.SUMMARY
        val listener = RoomAuditListener(dao = dao)
        val states = MutableSharedFlow<TunnelState>(replay = 16, extraBufferCapacity = 16)
        val posted = CompletableDeferred<Unit>()
        dao.onQueryCreatedAfter = { posted.complete(Unit) }

        val job = launch { notifier.observe(states) }

        states.emit(TunnelState.CONNECTING)
        states.emit(TunnelState.CONNECTED)
        states.emit(TunnelState.ACTIVE)

        // Wait for the notifier to capture its cursor before we persist.
        awaitLatestId()

        // Persist via the real listener. Because onToolCall is now `suspend`
        // and does NOT internally launch, this call returns only after the
        // row has been inserted.
        listener.onToolCall(
            ToolCallEvent(
                sessionId = "session-1",
                providerId = "health",
                timestamp = System.currentTimeMillis(),
                toolName = "get_steps",
                arguments = emptyMap(),
                result = CallToolResult(content = listOf(TextContent("ok"))),
                durationMs = 5L
            )
        )

        // Immediately drain — this is the transition that used to race the
        // in-flight insert. With the fix, the insert is already committed.
        states.emit(TunnelState.CONNECTED)

        withTimeout(TIMEOUT_MS) { posted.await() }

        val shadow = Shadows.shadowOf(manager)
        val notification = shadow.getNotification(SessionSummaryNotifier.NOTIFICATION_ID)
        assertNotNull(
            "Summary notification should be posted when the insert completes " +
                "before the ACTIVE -> CONNECTED transition (issue #244)",
            notification
        )
        val title = notification.extras.getCharSequence("android.title")?.toString() ?: ""
        val text = notification.extras.getCharSequence("android.text")?.toString() ?: ""
        val all = "$title $text"
        assertTrue("Expected 1 tool call in summary text, was: $all", all.contains("1"))
        assertTrue("Expected health provider in summary text, was: $all", all.contains("health"))

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

        val job = launch { notifier.observe(states) }

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
        private const val TIMEOUT_MS = 5_000L
    }
}
