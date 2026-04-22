package com.rousecontext.notifications

import android.app.Notification
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
 * Unit tests for [SessionSummaryNotifier].
 *
 * Verifies session-end summary notification behavior for each [PostSessionMode]
 * and the locked-design copy from issue #347:
 *
 *  - One notification per distinct `clientLabel` in the session.
 *  - Title: `"${clientLabel} used ${count} ${tool|tools}"`.
 *  - Body: up to 3 unique humanized tool names joined via [joinToolNames].
 *  - Tap deep-links to the audit screen with `start`/`end` millis extras.
 *  - `Manage` action routes to the integration manage page when all calls in
 *    the client's partition are from one integration; otherwise home.
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
    fun `Summary posts one notification per client with pluralized title`() = runBlocking {
        settings.mode = PostSessionMode.SUMMARY
        val states = MutableSharedFlow<TunnelState>(replay = 16, extraBufferCapacity = 16)
        val posted = CompletableDeferred<Unit>()
        dao.onQueryCreatedAfter = { posted.complete(Unit) }

        val job = launch { notifier.observe(states) }

        states.emit(TunnelState.CONNECTING)
        states.emit(TunnelState.CONNECTED)
        states.emit(TunnelState.ACTIVE)
        awaitLatestId()

        // Two clients: Claude (3 calls, 2 unique tools) and Cursor (1 call).
        dao.insert(entry(provider = "health", toolName = "get_steps", clientLabel = "Claude"))
        dao.insert(
            entry(provider = "health", toolName = "get_steps", clientLabel = "Claude")
        )
        dao.insert(
            entry(provider = "health", toolName = "get_sleep", clientLabel = "Claude")
        )
        dao.insert(
            entry(provider = "usage", toolName = "get_app_usage", clientLabel = "Cursor")
        )

        states.emit(TunnelState.CONNECTED)
        withTimeout(TIMEOUT_MS) { posted.await() }

        val shadow = Shadows.shadowOf(manager)
        val all = shadow.allNotifications
        assertEquals("One notification per distinct client label", 2, all.size)

        val titles = all.mapNotNull {
            it.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        }.toSet()
        assertTrue(
            "Claude (3 calls) should render as 'Claude used 3 tools'. Titles: $titles",
            titles.contains("Claude used 3 tools")
        )
        assertTrue(
            "Cursor (1 call) should render as 'Cursor used 1 tool'. Titles: $titles",
            titles.contains("Cursor used 1 tool")
        )

        // Claude body: 2 unique tools sorted by call count desc — Get Steps (2)
        // then Get Sleep (1) — joined with "and".
        val claude = all.first {
            it.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ==
                "Claude used 3 tools"
        }
        val claudeBody =
            claude.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        assertEquals(
            "Two unique tools join with 'and', no Oxford comma",
            "Get Steps and Get Sleep",
            claudeBody
        )

        val cursor = all.first {
            it.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ==
                "Cursor used 1 tool"
        }
        val cursorBody = cursor.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        assertEquals("Single tool body is the tool name", "Get App Usage", cursorBody)

        job.cancel()
        coroutineContext.cancelChildren()
    }

    @Test
    fun `Three unique tools body uses Oxford comma`() = runBlocking {
        settings.mode = PostSessionMode.SUMMARY
        val states = MutableSharedFlow<TunnelState>(replay = 16, extraBufferCapacity = 16)
        val posted = CompletableDeferred<Unit>()
        dao.onQueryCreatedAfter = { posted.complete(Unit) }

        val job = launch { notifier.observe(states) }

        states.emit(TunnelState.ACTIVE)
        awaitLatestId()

        dao.insert(entry(provider = "health", toolName = "get_steps", clientLabel = "Claude"))
        dao.insert(entry(provider = "health", toolName = "get_sleep", clientLabel = "Claude"))
        dao.insert(entry(provider = "health", toolName = "get_heart_rate", clientLabel = "Claude"))

        states.emit(TunnelState.CONNECTED)
        withTimeout(TIMEOUT_MS) { posted.await() }

        val notification =
            Shadows.shadowOf(manager).getNotification(
                SessionSummaryNotifier.idForClient("Claude")
            )
        assertNotNull(notification)
        val body = notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        // Counts are all equal; ordering falls back to alphabetical on raw
        // tool name: get_heart_rate, get_sleep, get_steps.
        assertEquals(
            "Three unique tools: humanized, sorted by count desc then name asc, Oxford comma",
            "Get Heart Rate, Get Sleep, and Get Steps",
            body
        )

        job.cancel()
        coroutineContext.cancelChildren()
    }

    @Test
    fun `Body truncates after 3 unique tools with 'and N more'`() = runBlocking {
        settings.mode = PostSessionMode.SUMMARY
        val states = MutableSharedFlow<TunnelState>(replay = 16, extraBufferCapacity = 16)
        val posted = CompletableDeferred<Unit>()
        dao.onQueryCreatedAfter = { posted.complete(Unit) }

        val job = launch { notifier.observe(states) }

        states.emit(TunnelState.ACTIVE)
        awaitLatestId()

        // 6 unique tools so body is "a, b, c, and 3 more". Ordering uses
        // call-count desc; use counts 6,5,4,3,2,1 so ties never apply.
        val plan = listOf(
            "get_steps" to 6,
            "get_sleep" to 5,
            "get_heart_rate" to 4,
            "get_summary" to 3,
            "get_hrv" to 2,
            "get_workouts" to 1
        )
        plan.forEach { (name, count) ->
            repeat(count) {
                dao.insert(
                    entry(provider = "health", toolName = name, clientLabel = "Claude")
                )
            }
        }

        states.emit(TunnelState.CONNECTED)
        withTimeout(TIMEOUT_MS) { posted.await() }

        val notification =
            Shadows.shadowOf(manager).getNotification(
                SessionSummaryNotifier.idForClient("Claude")
            )
        assertNotNull(notification)
        val body = notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        assertEquals(
            "Top 3 humanized tool names joined + 'and N more'",
            "Get Steps, Get Sleep, Get Heart Rate, and 3 more",
            body
        )

        job.cancel()
        coroutineContext.cancelChildren()
    }

    @Test
    fun `Manage action routes to integration manage when single integration`() = runBlocking {
        settings.mode = PostSessionMode.SUMMARY
        val states = MutableSharedFlow<TunnelState>(replay = 16, extraBufferCapacity = 16)
        val posted = CompletableDeferred<Unit>()
        dao.onQueryCreatedAfter = { posted.complete(Unit) }

        val job = launch { notifier.observe(states) }

        states.emit(TunnelState.ACTIVE)
        awaitLatestId()
        dao.insert(entry(provider = "health", toolName = "get_steps", clientLabel = "Claude"))
        dao.insert(entry(provider = "health", toolName = "get_sleep", clientLabel = "Claude"))
        states.emit(TunnelState.CONNECTED)
        withTimeout(TIMEOUT_MS) { posted.await() }

        val notification =
            Shadows.shadowOf(manager).getNotification(
                SessionSummaryNotifier.idForClient("Claude")
            )
        assertNotNull(notification)
        val actions = notification.actions
        assertNotNull("Manage action should exist", actions)
        assertEquals(1, actions.size)
        val action = actions.first()
        assertEquals("Manage", action.title.toString())
        val shadowPending = Shadows.shadowOf(action.actionIntent)
        val intent = shadowPending.savedIntent
        assertEquals(
            SessionSummaryNotifier.ACTION_OPEN_INTEGRATION_MANAGE,
            intent.action
        )
        assertEquals(
            "health",
            intent.getStringExtra(SessionSummaryNotifier.EXTRA_INTEGRATION_ID)
        )

        job.cancel()
        coroutineContext.cancelChildren()
    }

    @Test
    fun `Manage action routes to home when calls span multiple integrations`() = runBlocking {
        settings.mode = PostSessionMode.SUMMARY
        val states = MutableSharedFlow<TunnelState>(replay = 16, extraBufferCapacity = 16)
        val posted = CompletableDeferred<Unit>()
        dao.onQueryCreatedAfter = { posted.complete(Unit) }

        val job = launch { notifier.observe(states) }

        states.emit(TunnelState.ACTIVE)
        awaitLatestId()
        dao.insert(entry(provider = "health", toolName = "get_steps", clientLabel = "Claude"))
        dao.insert(entry(provider = "usage", toolName = "get_app_usage", clientLabel = "Claude"))
        states.emit(TunnelState.CONNECTED)
        withTimeout(TIMEOUT_MS) { posted.await() }

        val notification =
            Shadows.shadowOf(manager).getNotification(
                SessionSummaryNotifier.idForClient("Claude")
            )
        assertNotNull(notification)
        val action = notification.actions.first()
        val intent = Shadows.shadowOf(action.actionIntent).savedIntent
        assertEquals(
            "Mixed integrations should route Manage to home",
            SessionSummaryNotifier.ACTION_OPEN_HOME,
            intent.action
        )
        assertFalse(
            "Mixed-integration Manage intent should NOT carry an integration id",
            intent.hasExtra(SessionSummaryNotifier.EXTRA_INTEGRATION_ID)
        )

        job.cancel()
        coroutineContext.cancelChildren()
    }

    @Test
    fun `Tap PendingIntent carries session time-window extras`() = runBlocking {
        settings.mode = PostSessionMode.SUMMARY
        val states = MutableSharedFlow<TunnelState>(replay = 16, extraBufferCapacity = 16)
        val posted = CompletableDeferred<Unit>()
        dao.onQueryCreatedAfter = { posted.complete(Unit) }

        val job = launch { notifier.observe(states) }

        states.emit(TunnelState.ACTIVE)
        awaitLatestId()
        dao.insert(entry(provider = "health", toolName = "get_steps", clientLabel = "Claude"))
        states.emit(TunnelState.CONNECTED)
        withTimeout(TIMEOUT_MS) { posted.await() }

        val notification =
            Shadows.shadowOf(manager).getNotification(
                SessionSummaryNotifier.idForClient("Claude")
            )
        assertNotNull(notification)
        val tap = Shadows.shadowOf(notification.contentIntent).savedIntent
        assertEquals(
            SessionSummaryNotifier.ACTION_OPEN_AUDIT_HISTORY,
            tap.action
        )
        assertTrue(tap.hasExtra(SessionSummaryNotifier.EXTRA_START_MILLIS))
        assertTrue(tap.hasExtra(SessionSummaryNotifier.EXTRA_END_MILLIS))

        job.cancel()
        coroutineContext.cancelChildren()
    }

    @Test
    fun `Different clients produce distinct stable notification ids`() {
        val a = SessionSummaryNotifier.idForClient("Claude")
        val b = SessionSummaryNotifier.idForClient("Cursor")
        assertTrue(
            "Different labels should (almost always) produce different ids",
            a != b
        )
        assertEquals(
            "Id is stable across calls for the same label",
            a,
            SessionSummaryNotifier.idForClient("Claude")
        )
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
        assertTrue(
            "Suppress mode should not post any notifications",
            shadow.allNotifications.isEmpty()
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
        assertTrue(
            "EachUsage mode should not post any session-end summaries",
            shadow.allNotifications.isEmpty()
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
        assertTrue(
            "No notifications should post when there were no tool calls",
            shadow.allNotifications.isEmpty()
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
        dao.insert(entry(provider = "health", toolName = "get_steps", clientLabel = "Claude"))
        dao.insert(entry(provider = "health", toolName = "get_sleep", clientLabel = "Claude"))

        val job = launch { notifier.observe(states) }

        states.emit(TunnelState.ACTIVE)
        awaitLatestId()

        // Only new calls during the session
        dao.insert(
            entry(provider = "notifications", toolName = "dismiss", clientLabel = "Cursor")
        )

        states.emit(TunnelState.CONNECTED)
        withTimeout(TIMEOUT_MS) { queried.await() }

        val shadow = Shadows.shadowOf(manager)
        val notifications = shadow.allNotifications
        assertEquals(
            "Only the current session's Cursor notification should post",
            1,
            notifications.size
        )
        val title = notifications.first()
            .extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        assertEquals("Cursor used 1 tool", title)

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
        states.emit(TunnelState.ACTIVE)
        awaitLatestId()

        val shadow = Shadows.shadowOf(manager)
        assertTrue(
            "No notifications should be posted when we skip the drain transition",
            shadow.allNotifications.isEmpty()
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
     * Regression test for issue #324 — unchanged from pre-#347 but updated
     * to use the new per-client notification id. See the original comment
     * in git history for the full scenario; the assertion now checks the
     * per-client bucket rather than the legacy NOTIFICATION_ID constant.
     */
    @Test
    fun `Summary posts on later drain when first drain of cycle has no entries`() = runBlocking {
        settings.mode = PostSessionMode.SUMMARY
        val states = MutableSharedFlow<TunnelState>(replay = 16, extraBufferCapacity = 16)
        val postedSignal = CompletableDeferred<Unit>()
        dao.onQueryCreatedAfter = {
            if (dao.queryCreatedAfterCount >= 2 && !postedSignal.isCompleted) {
                postedSignal.complete(Unit)
            }
        }

        val job = launch { notifier.observe(states) }

        states.emit(TunnelState.ACTIVE)
        awaitLatestId()
        states.emit(TunnelState.CONNECTED)

        kotlinx.coroutines.yield()

        states.emit(TunnelState.ACTIVE)
        awaitLatestId()
        dao.insert(entry(provider = "health", toolName = "get_steps", clientLabel = "Claude"))

        states.emit(TunnelState.CONNECTED)
        withTimeout(TIMEOUT_MS) { postedSignal.await() }

        val notification = Shadows.shadowOf(manager)
            .getNotification(SessionSummaryNotifier.idForClient("Claude"))
        assertNotNull(
            "Summary should post on the second drain when the first drain had no entries",
            notification
        )
        val title = notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        assertEquals("Claude used 1 tool", title)

        job.cancel()
        coroutineContext.cancelChildren()
    }

    @Test
    fun `Summary posts once per connection cycle even with repeated drains`() = runBlocking {
        settings.mode = PostSessionMode.SUMMARY
        val states = MutableSharedFlow<TunnelState>(replay = 16, extraBufferCapacity = 16)
        val firstPosted = CompletableDeferred<Unit>()
        dao.onQueryCreatedAfter = { firstPosted.complete(Unit) }

        val job = launch { notifier.observe(states) }

        states.emit(TunnelState.ACTIVE)
        awaitLatestId()
        dao.insert(entry(provider = "health", toolName = "get_steps", clientLabel = "Claude"))

        states.emit(TunnelState.CONNECTED)
        withTimeout(TIMEOUT_MS) { firstPosted.await() }

        assertNotNull(
            Shadows.shadowOf(manager)
                .getNotification(SessionSummaryNotifier.idForClient("Claude"))
        )

        states.emit(TunnelState.ACTIVE)
        dao.insert(entry(provider = "health", toolName = "get_heart_rate", clientLabel = "Claude"))
        states.emit(TunnelState.CONNECTED)

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
        awaitLatestId()

        listener.onToolCall(
            ToolCallEvent(
                sessionId = "session-1",
                providerId = "health",
                timestamp = System.currentTimeMillis(),
                toolName = "get_steps",
                arguments = emptyMap(),
                result = CallToolResult(content = listOf(TextContent("ok"))),
                durationMs = 5L,
                clientLabel = "Claude"
            )
        )

        states.emit(TunnelState.CONNECTED)
        withTimeout(TIMEOUT_MS) { posted.await() }

        val notification = Shadows.shadowOf(manager)
            .getNotification(SessionSummaryNotifier.idForClient("Claude"))
        assertNotNull(
            "Summary notification should be posted when the insert completes before drain",
            notification
        )
        val title = notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        assertEquals("Claude used 1 tool", title)

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

        states.emit(TunnelState.ACTIVE)
        awaitLatestId()
        dao.insert(entry(provider = "health", toolName = "get_steps", clientLabel = "Claude"))
        states.emit(TunnelState.CONNECTED)
        withTimeout(TIMEOUT_MS) { firstPosted.await() }

        states.emit(TunnelState.DISCONNECTED)

        states.emit(TunnelState.ACTIVE)
        awaitLatestId()
        dao.insert(entry(provider = "usage", toolName = "get_app_usage", clientLabel = "Claude"))
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
     * Waits until the poster's `latestId()` read has occurred.
     */
    private suspend fun awaitLatestId() {
        withTimeout(TIMEOUT_MS) { dao.latestIdCalled.await() }
        dao.resetLatestIdSignal()
    }

    private fun entry(
        provider: String,
        toolName: String,
        sessionId: String = "session-1",
        clientLabel: String? = "Claude"
    ) = AuditEntry(
        sessionId = sessionId,
        toolName = toolName,
        provider = provider,
        timestampMillis = System.currentTimeMillis(),
        durationMillis = 5L,
        success = true,
        clientLabel = clientLabel
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

    @Suppress("unused")
    private fun assertNullish() {
        assertNull(null)
    }

    companion object {
        private const val TIMEOUT_MS = 5_000L
    }
}
