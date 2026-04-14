package com.rousecontext.notifications.audit

import com.rousecontext.mcp.core.ToolCallEvent
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Unit tests for [RoomAuditListener].
 *
 * Verifies the listener persists tool call events to the DAO and forwards
 * them to the [PerCallObserver] hook that drives per-tool-call notifications.
 */
class RoomAuditListenerTest {

    @Test
    fun `onToolCall persists entry and invokes observer`() = runBlocking {
        val dao = FakeAuditDao()
        val observer = RecordingObserver()
        val scope = CoroutineScope(coroutineContext + Dispatchers.Unconfined)
        val listener = RoomAuditListener(
            dao = dao,
            scope = scope,
            fieldEncryptor = null,
            perCallObserver = observer
        )

        val event = makeEvent(provider = "health", toolName = "get_steps")
        listener.onToolCall(event)

        withTimeout(TIMEOUT_MS) { observer.signal.await() }

        assertEquals("Entry should be inserted", 1, dao.entries.size)
        val entry = dao.entries.first()
        assertEquals("health", entry.provider)
        assertEquals("get_steps", entry.toolName)

        assertNotNull("Observer should be invoked with the event", observer.lastEvent)
        assertEquals("health", observer.lastEvent?.providerId)
        assertEquals("get_steps", observer.lastEvent?.toolName)

        coroutineContext.cancelChildren()
    }

    @Test
    fun `onToolCall invokes observer for every event`() = runBlocking {
        val dao = FakeAuditDao()
        val observer = RecordingObserver(expectedCount = 3)
        val scope = CoroutineScope(coroutineContext + Dispatchers.Unconfined)
        val listener = RoomAuditListener(
            dao = dao,
            scope = scope,
            fieldEncryptor = null,
            perCallObserver = observer
        )

        listener.onToolCall(makeEvent(provider = "health", toolName = "get_steps"))
        listener.onToolCall(makeEvent(provider = "health", toolName = "get_hr"))
        listener.onToolCall(makeEvent(provider = "usage", toolName = "get_app_usage"))

        withTimeout(TIMEOUT_MS) { observer.signal.await() }

        assertEquals(3, dao.entries.size)
        assertEquals(3, observer.calls.size)
        assertEquals(
            listOf("get_steps", "get_hr", "get_app_usage"),
            observer.calls.map { it.toolName }
        )

        coroutineContext.cancelChildren()
    }

    @Test
    fun `observer is optional — listener works without one`() = runBlocking {
        val dao = FakeAuditDao()
        val scope = CoroutineScope(coroutineContext + Dispatchers.Unconfined)
        val listener = RoomAuditListener(dao = dao, scope = scope)

        listener.onToolCall(makeEvent(provider = "health", toolName = "get_steps"))

        // Let the scope drain.
        kotlinx.coroutines.yield()

        assertEquals(1, dao.entries.size)

        coroutineContext.cancelChildren()
    }

    private fun makeEvent(
        provider: String,
        toolName: String,
        sessionId: String = "session-1"
    ): ToolCallEvent = ToolCallEvent(
        sessionId = sessionId,
        providerId = provider,
        timestamp = System.currentTimeMillis(),
        toolName = toolName,
        arguments = emptyMap(),
        result = CallToolResult(content = listOf(TextContent("ok"))),
        durationMs = 5L
    )

    private class RecordingObserver(val expectedCount: Int = 1) : PerCallObserver {
        val calls = mutableListOf<ToolCallEvent>()
        var lastEvent: ToolCallEvent? = null
        val signal = CompletableDeferred<Unit>()

        override fun onToolCallRecorded(event: ToolCallEvent) {
            calls.add(event)
            lastEvent = event
            if (calls.size >= expectedCount && !signal.isCompleted) {
                signal.complete(Unit)
            }
        }
    }

    private class FakeAuditDao : AuditDao {
        val entries = mutableListOf<AuditEntry>()
        private var nextId = 1L

        override suspend fun insert(entry: AuditEntry): Long {
            val id = nextId++
            entries.add(entry.copy(id = id))
            return id
        }

        override suspend fun getById(id: Long): AuditEntry? = entries.find { it.id == id }

        override suspend fun queryBySession(sessionId: String): List<AuditEntry> =
            entries.filter { it.sessionId == sessionId }

        override suspend fun queryByDateRange(
            startMillis: Long,
            endMillis: Long,
            provider: String?
        ): List<AuditEntry> = entries

        override fun observeRecent(
            startMillis: Long,
            endMillis: Long,
            limit: Int
        ): Flow<List<AuditEntry>> = throw UnsupportedOperationException("not used in these tests")

        override fun observeByDateRange(
            startMillis: Long,
            endMillis: Long,
            provider: String?
        ): Flow<List<AuditEntry>> = throw UnsupportedOperationException("not used in these tests")

        override suspend fun deleteOlderThan(cutoffMillis: Long): Int = 0
        override suspend fun count(): Int = entries.size
        override suspend fun latestId(): Long? = entries.maxByOrNull { it.id }?.id
        override suspend fun queryCreatedAfter(sinceId: Long): List<AuditEntry> =
            entries.filter { it.id > sinceId }
    }

    companion object {
        private const val TIMEOUT_MS = 2_000L
    }
}
