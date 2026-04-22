package com.rousecontext.notifications

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Unit tests for [NotificationIdCounter].
 *
 * Verifies that the counter is persisted across instances backed by the same
 * DataStore file, so cold-start process recreation does not reset the sequence
 * and cause notification id collisions (see issue #331).
 */
class NotificationIdCounterTest {

    @get:Rule
    val tmpDir = TemporaryFolder()

    private val scopes = mutableListOf<CoroutineScope>()

    @After
    fun tearDown() = runBlocking {
        scopes.forEach { cancelAndJoin(it) }
        scopes.clear()
    }

    private suspend fun cancelAndJoin(scope: CoroutineScope) {
        val job: Job = scope.coroutineContext.job
        scope.cancel()
        job.join()
    }

    @Test
    fun `fresh counter returns 0 on first call`() = runBlocking {
        val counter = NotificationIdCounter(makeStore())
        assertEquals(0, counter.next())
    }

    @Test
    fun `successive calls return monotonic values`() = runBlocking {
        val counter = NotificationIdCounter(makeStore())
        assertEquals(0, counter.next())
        assertEquals(1, counter.next())
        assertEquals(2, counter.next())
        assertEquals(3, counter.next())
    }

    @Test
    fun `a second instance backed by the same file continues the sequence`() = runBlocking {
        val firstScope = newScope()
        val first = NotificationIdCounter(storeIn(firstScope))
        assertEquals(0, first.next())
        assertEquals(1, first.next())
        assertEquals(2, first.next())
        // Simulate process death: DataStore requires only one active instance
        // per file (enforced by a static `activeFiles` set in FileStorage),
        // so we must release the first before opening a second pointed at the
        // same path. Cancellation is asynchronous — the connection's
        // invokeOnCompletion handler removes the file from `activeFiles`, so
        // we must join the scope's job before opening a new DataStore.
        cancelAndJoin(firstScope)

        val secondScope = newScope()
        val second = NotificationIdCounter(storeIn(secondScope))
        assertEquals(3, second.next())
        assertEquals(4, second.next())
    }

    @Test
    fun `counter wraps back to 0 at WRAP_AT boundary`() = runBlocking {
        // Seed the store at just below the wrap boundary so we don't need to
        // call next() 10k times.
        val store = makeStore()
        val counter = NotificationIdCounter(store)
        counter.seedForTest(NotificationIdCounter.WRAP_AT - 2)

        assertEquals(NotificationIdCounter.WRAP_AT - 2, counter.next())
        assertEquals(NotificationIdCounter.WRAP_AT - 1, counter.next())
        assertEquals(0, counter.next())
        assertEquals(1, counter.next())
    }

    @Test
    fun `distinct counter files do not share state`() = runBlocking {
        val storeA = makeStore(name = "a.preferences_pb")
        val storeB = makeStore(name = "b.preferences_pb")
        val a = NotificationIdCounter(storeA)
        val b = NotificationIdCounter(storeB)

        assertEquals(0, a.next())
        assertEquals(1, a.next())
        // b is independent.
        assertEquals(0, b.next())
        assertNotEquals(a.next(), b.next())
    }

    private fun newScope(): CoroutineScope {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scopes += scope
        return scope
    }

    private fun makeStore(name: String = "counter.preferences_pb"): DataStore<Preferences> =
        storeIn(newScope(), name)

    private fun storeIn(
        scope: CoroutineScope,
        name: String = "counter.preferences_pb"
    ): DataStore<Preferences> = PreferenceDataStoreFactory.create(scope = scope) {
        File(tmpDir.root, name)
    }
}
