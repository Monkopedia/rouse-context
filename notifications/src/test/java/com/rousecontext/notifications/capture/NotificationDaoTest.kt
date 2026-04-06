package com.rousecontext.notifications.capture

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class NotificationDaoTest {

    private lateinit var database: NotificationDatabase
    private lateinit var dao: NotificationDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = NotificationDatabase.createInMemory(context)
        dao = database.notificationDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `insert and search by text`() = runBlocking {
        dao.insert(record(title = "New message from Alice", text = "Hey there!"))
        dao.insert(record(title = "Build failed", text = "CI pipeline error"))

        val results = dao.search(textQuery = "Alice")
        assertEquals(1, results.size)
        assertEquals("New message from Alice", results[0].title)
    }

    @Test
    fun `search by package filter`() = runBlocking {
        dao.insert(record(packageName = "com.slack.android", title = "Slack"))
        dao.insert(record(packageName = "com.google.gmail", title = "Gmail"))

        val results = dao.search(packageFilter = "com.slack.android")
        assertEquals(1, results.size)
        assertEquals("Slack", results[0].title)
    }

    @Test
    fun `search by time range`() = runBlocking {
        dao.insert(record(postedAt = 1000L, title = "old"))
        dao.insert(record(postedAt = 5000L, title = "in range"))
        dao.insert(record(postedAt = 10000L, title = "recent"))

        val results = dao.search(sinceMillis = 3000L, untilMillis = 7000L)
        assertEquals(1, results.size)
        assertEquals("in range", results[0].title)
    }

    @Test
    fun `search respects limit`() = runBlocking {
        repeat(10) { i ->
            dao.insert(record(title = "Notification $i"))
        }

        val results = dao.search(limit = 3)
        assertEquals(3, results.size)
    }

    @Test
    fun `mark removed updates timestamp`() = runBlocking {
        val id = dao.insert(record(packageName = "com.test", postedAt = 1000L))

        val before = dao.search()
        assertNull(before[0].removedAt)

        dao.markRemoved(id, removedAt = 2000L)

        val after = dao.search()
        assertEquals(2000L, after[0].removedAt)
    }

    @Test
    fun `find by package and time`() = runBlocking {
        dao.insert(record(packageName = "com.test", postedAt = 1000L))
        dao.insert(record(packageName = "com.other", postedAt = 1000L))

        val found = dao.findByPackageAndTime("com.test", 1000L)
        assertNotNull(found)
        assertEquals("com.test", found?.packageName)

        val notFound = dao.findByPackageAndTime("com.missing", 1000L)
        assertNull(notFound)
    }

    @Test
    fun `count by package returns descending order`() = runBlocking {
        repeat(3) { dao.insert(record(packageName = "com.frequent")) }
        dao.insert(record(packageName = "com.rare"))

        val counts = dao.countByPackage(0L, Long.MAX_VALUE)
        assertEquals(2, counts.size)
        assertEquals("com.frequent", counts[0].packageName)
        assertEquals(3, counts[0].count)
        assertEquals("com.rare", counts[1].packageName)
        assertEquals(1, counts[1].count)
    }

    @Test
    fun `count in range`() = runBlocking {
        dao.insert(record(postedAt = 1000L))
        dao.insert(record(postedAt = 5000L))
        dao.insert(record(postedAt = 10000L))

        val count = dao.countInRange(3000L, 7000L)
        assertEquals(1, count)
    }

    @Test
    fun `delete older than`() = runBlocking {
        dao.insert(record(postedAt = 1000L))
        dao.insert(record(postedAt = 5000L))
        dao.insert(record(postedAt = 10000L))

        val deleted = dao.deleteOlderThan(6000L)
        assertEquals(2, deleted)

        val remaining = dao.search()
        assertEquals(1, remaining.size)
        assertEquals(10000L, remaining[0].postedAt)
    }

    @Test
    fun `update actions taken`() = runBlocking {
        val id = dao.insert(record())
        val actionJson = """[{"label":"Reply","time":12345}]"""

        dao.updateActionsTaken(id, actionJson)

        val record = dao.search()[0]
        assertEquals(actionJson, record.actionsTaken)
    }

    @Test
    fun `empty database returns empty results`() = runBlocking {
        assertTrue(dao.search().isEmpty())
        assertEquals(0, dao.countInRange(0L, Long.MAX_VALUE))
        assertTrue(dao.countByPackage(0L, Long.MAX_VALUE).isEmpty())
    }

    private fun record(
        packageName: String = "com.test.app",
        title: String? = "Test Notification",
        text: String? = "Some text",
        postedAt: Long = System.currentTimeMillis(),
        category: String? = null,
        ongoing: Boolean = false
    ) = NotificationRecord(
        packageName = packageName,
        title = title,
        text = text,
        postedAt = postedAt,
        category = category,
        ongoing = ongoing
    )
}
