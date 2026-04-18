package com.rousecontext.notifications.audit

import com.rousecontext.mcp.core.McpRequestEvent
import com.rousecontext.mcp.core.ToolCallEvent
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [RoomAuditListener].
 *
 * Verifies the listener persists tool call events to the DAO and forwards
 * them to the [PerCallObserver] hook that drives per-tool-call notifications.
 * Inserts run synchronously from the caller's perspective (see issue #244).
 */
class RoomAuditListenerTest {

    @Test
    fun `onToolCall persists entry and invokes observer`() = runBlocking {
        val dao = FakeAuditDao()
        val observer = RecordingObserver()
        val listener = RoomAuditListener(
            dao = dao,
            fieldEncryptor = null,
            perCallObserver = observer
        )

        val event = makeEvent(provider = "health", toolName = "get_steps")
        listener.onToolCall(event)

        assertEquals("Entry should be inserted", 1, dao.entries.size)
        val entry = dao.entries.first()
        assertEquals("health", entry.provider)
        assertEquals("get_steps", entry.toolName)

        assertNotNull("Observer should be invoked with the event", observer.lastEvent)
        assertEquals("health", observer.lastEvent?.providerId)
        assertEquals("get_steps", observer.lastEvent?.toolName)
    }

    @Test
    fun `onToolCall serializes result as valid JSON with content array`() = runBlocking {
        val dao = FakeAuditDao()
        val listener = RoomAuditListener(dao = dao)

        val event = ToolCallEvent(
            sessionId = "session-1",
            providerId = "health",
            timestamp = 1L,
            toolName = "get_steps",
            arguments = emptyMap(),
            result = CallToolResult(content = listOf(TextContent("hello world"))),
            durationMs = 5L
        )
        listener.onToolCall(event)

        assertEquals(1, dao.entries.size)
        val rawJson = dao.entries.first().resultJson
        assertNotNull("resultJson should not be null", rawJson)

        // Must parse as a JSON object (not raw toString).
        val parsed = Json.parseToJsonElement(rawJson!!).jsonObject
        val contentArray = parsed["content"]?.jsonArray
        assertNotNull("Serialized result must expose a content array", contentArray)
        assertEquals(1, contentArray!!.size)
        val firstItem = contentArray[0].jsonObject
        assertEquals("text", firstItem["type"]?.jsonPrimitive?.content)
        assertEquals("hello world", firstItem["text"]?.jsonPrimitive?.content)

        // isError should be preserved (default false).
        val isError = parsed["isError"]?.jsonPrimitive?.content?.toBoolean() ?: false
        assertFalse("Default isError should be false", isError)
    }

    @Test
    fun `onToolCall invokes observer for every event`() = runBlocking {
        val dao = FakeAuditDao()
        val observer = RecordingObserver()
        val listener = RoomAuditListener(
            dao = dao,
            fieldEncryptor = null,
            perCallObserver = observer
        )

        listener.onToolCall(makeEvent(provider = "health", toolName = "get_steps"))
        listener.onToolCall(makeEvent(provider = "health", toolName = "get_hr"))
        listener.onToolCall(makeEvent(provider = "usage", toolName = "get_app_usage"))

        assertEquals(3, dao.entries.size)
        assertEquals(3, observer.calls.size)
        assertEquals(
            listOf("get_steps", "get_hr", "get_app_usage"),
            observer.calls.map { it.toolName }
        )
    }

    @Test
    fun `onRequest persists to mcp request dao`() = runBlocking {
        val dao = FakeAuditDao()
        val requestDao = FakeMcpRequestDao()
        val listener = RoomAuditListener(
            dao = dao,
            fieldEncryptor = null,
            mcpRequestDao = requestDao
        )

        val event = McpRequestEvent(
            sessionId = "session-1",
            providerId = "health",
            timestamp = 1234L,
            method = "tools/list",
            params = buildJsonObject { put("foo", JsonPrimitive("bar")) },
            resultBytes = 42,
            durationMs = 7L
        )
        listener.onRequest(event)

        assertEquals("Request entry should be inserted", 1, requestDao.entries.size)
        val entry = requestDao.entries.first()
        assertEquals("health", entry.provider)
        assertEquals("session-1", entry.sessionId)
        assertEquals("tools/list", entry.method)
        assertEquals(1234L, entry.timestampMillis)
        assertEquals(7L, entry.durationMillis)
        assertEquals(42, entry.resultBytes)
        assertNotNull(entry.paramsJson)
        assertTrue("params should be JSON", entry.paramsJson!!.contains("bar"))

        // Tool-call DAO stays empty - onRequest is separate from onToolCall
        assertEquals(0, dao.entries.size)
    }

    @Test
    fun `onRequest is a no-op when mcp request dao is absent`() = runBlocking {
        val dao = FakeAuditDao()
        val listener = RoomAuditListener(dao = dao)

        val event = McpRequestEvent(
            sessionId = "s",
            providerId = "health",
            timestamp = 1L,
            method = "initialize",
            params = null,
            resultBytes = null,
            durationMs = 0L
        )
        // Does not throw
        listener.onRequest(event)

        assertEquals(0, dao.entries.size)
    }

    @Test
    fun `observer is optional — listener works without one`() = runBlocking {
        val dao = FakeAuditDao()
        val listener = RoomAuditListener(dao = dao)

        listener.onToolCall(makeEvent(provider = "health", toolName = "get_steps"))

        assertEquals(1, dao.entries.size)
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

    private class RecordingObserver : PerCallObserver {
        val calls = mutableListOf<ToolCallEvent>()
        var lastEvent: ToolCallEvent? = null

        override suspend fun onToolCallRecorded(event: ToolCallEvent) {
            calls.add(event)
            lastEvent = event
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

    private class FakeMcpRequestDao : McpRequestDao {
        val entries = mutableListOf<McpRequestEntry>()
        private var nextId = 1L

        override suspend fun insert(entry: McpRequestEntry): Long {
            val id = nextId++
            entries.add(entry.copy(id = id))
            return id
        }

        override suspend fun getById(id: Long): McpRequestEntry? = entries.find { it.id == id }

        override suspend fun queryByDateRange(
            startMillis: Long,
            endMillis: Long,
            provider: String?
        ): List<McpRequestEntry> = entries

        override fun observeByDateRange(
            startMillis: Long,
            endMillis: Long,
            provider: String?
        ): Flow<List<McpRequestEntry>> =
            throw UnsupportedOperationException("not used in these tests")

        override suspend fun deleteOlderThan(cutoffMillis: Long): Int = 0
        override suspend fun count(): Int = entries.size
    }
}
