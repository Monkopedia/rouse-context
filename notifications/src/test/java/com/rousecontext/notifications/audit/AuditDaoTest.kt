package com.rousecontext.notifications.audit

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AuditDaoTest {

    private lateinit var database: AuditDatabase
    private lateinit var dao: AuditDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AuditDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.auditDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `insert and query by sessionId`() = runBlocking {
        val entry = createEntry(sessionId = "session-1", toolName = "get_steps")
        dao.insert(entry)
        dao.insert(createEntry(sessionId = "session-2", toolName = "get_sleep"))

        val results = dao.queryBySession("session-1")
        assertEquals(1, results.size)
        assertEquals("get_steps", results[0].toolName)
    }

    @Test
    fun `query by sessionId returns results ordered by timestamp`() = runBlocking {
        dao.insert(createEntry(sessionId = "s1", timestampMillis = 3000L, toolName = "third"))
        dao.insert(createEntry(sessionId = "s1", timestampMillis = 1000L, toolName = "first"))
        dao.insert(createEntry(sessionId = "s1", timestampMillis = 2000L, toolName = "second"))

        val results = dao.queryBySession("s1")
        assertEquals(3, results.size)
        assertEquals("first", results[0].toolName)
        assertEquals("second", results[1].toolName)
        assertEquals("third", results[2].toolName)
    }

    @Test
    fun `query by date range`() = runBlocking {
        dao.insert(createEntry(timestampMillis = 1000L, toolName = "before"))
        dao.insert(createEntry(timestampMillis = 5000L, toolName = "in-range"))
        dao.insert(createEntry(timestampMillis = 10000L, toolName = "after"))

        val results = dao.queryByDateRange(startMillis = 3000L, endMillis = 7000L)
        assertEquals(1, results.size)
        assertEquals("in-range", results[0].toolName)
    }

    @Test
    fun `query by date range with provider filter`() = runBlocking {
        dao.insert(
            createEntry(timestampMillis = 5000L, provider = "health", toolName = "get_steps")
        )
        dao.insert(
            createEntry(timestampMillis = 5000L, provider = "files", toolName = "read_file")
        )

        val allResults = dao.queryByDateRange(
            startMillis = 1000L,
            endMillis = 10000L
        )
        assertEquals(2, allResults.size)

        val healthOnly = dao.queryByDateRange(
            startMillis = 1000L,
            endMillis = 10000L,
            provider = "health"
        )
        assertEquals(1, healthOnly.size)
        assertEquals("get_steps", healthOnly[0].toolName)
    }

    @Test
    fun `retention pruning deletes old entries`() = runBlocking {
        val thirtyDaysMs = 30L * 24 * 60 * 60 * 1000
        val now = System.currentTimeMillis()
        val old = now - thirtyDaysMs - 1000 // 30 days + 1 second ago
        val recent = now - 1000 // 1 second ago

        dao.insert(createEntry(timestampMillis = old, toolName = "old"))
        dao.insert(createEntry(timestampMillis = recent, toolName = "recent"))

        val deleted = dao.deleteOlderThan(now - thirtyDaysMs)
        assertEquals(1, deleted)

        val remaining = dao.count()
        assertEquals(1, remaining)
    }

    @Test
    fun `empty state returns empty list and zero count`() = runBlocking {
        val bySession = dao.queryBySession("nonexistent")
        assertTrue(bySession.isEmpty())

        val byDate = dao.queryByDateRange(0L, Long.MAX_VALUE)
        assertTrue(byDate.isEmpty())

        val count = dao.count()
        assertEquals(0, count)
    }

    private fun createEntry(
        sessionId: String = "test-session",
        toolName: String = "test_tool",
        provider: String = "test-provider",
        timestampMillis: Long = System.currentTimeMillis(),
        durationMillis: Long = 100L,
        success: Boolean = true,
        errorMessage: String? = null
    ) = AuditEntry(
        sessionId = sessionId,
        toolName = toolName,
        provider = provider,
        timestampMillis = timestampMillis,
        durationMillis = durationMillis,
        success = success,
        errorMessage = errorMessage
    )
}
