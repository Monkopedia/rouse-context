package com.rousecontext.app.token

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Unit tests for [DataStoreUnknownClientLabeler].
 *
 * Verifies monotonic `Unknown (#N)` assignment keyed on `client_id`:
 * - Idempotency: same clientId always resolves to the same label.
 * - Monotonicity: sequential unknown clients get `#1, #2, #3, ...`.
 * - Persistence: labels survive process restart (same DataStore file).
 * - Revocation-immunity: by design, assignments are never reused. Even
 *   if a client's tokens are revoked, its label stays fixed and a new
 *   unknown client takes the next number.
 *
 * See issue #345 and the [NotificationIdCounterTest] pattern for
 * DataStore-backed test setup.
 */
class DataStoreUnknownClientLabelerTest {

    @get:Rule
    val tmpDir = TemporaryFolder()

    private val scopes = mutableListOf<CoroutineScope>()

    @After
    fun tearDown() {
        scopes.forEach { it.cancel() }
        scopes.clear()
    }

    @Test
    fun `fresh labeler assigns Unknown number 1 to first client`() = runBlocking {
        val labeler = DataStoreUnknownClientLabeler(makeStore())
        assertEquals("Unknown (#1)", labeler.labelFor("client-A"))
    }

    @Test
    fun `same clientId returns the same label on repeat calls`() = runBlocking {
        val labeler = DataStoreUnknownClientLabeler(makeStore())
        val first = labeler.labelFor("client-A")
        val second = labeler.labelFor("client-A")
        val third = labeler.labelFor("client-A")
        assertEquals("Unknown (#1)", first)
        assertEquals(first, second)
        assertEquals(first, third)
    }

    @Test
    fun `distinct clientIds get sequential monotonic numbers`() = runBlocking {
        val labeler = DataStoreUnknownClientLabeler(makeStore())
        assertEquals("Unknown (#1)", labeler.labelFor("client-A"))
        assertEquals("Unknown (#2)", labeler.labelFor("client-B"))
        assertEquals("Unknown (#3)", labeler.labelFor("client-C"))
    }

    @Test
    fun `reordered lookups return the originally assigned numbers`() = runBlocking {
        val labeler = DataStoreUnknownClientLabeler(makeStore())
        labeler.labelFor("client-A")
        labeler.labelFor("client-B")
        labeler.labelFor("client-C")
        // Reorder: each clientId still resolves to the label it was assigned
        // when first seen, regardless of lookup order.
        assertEquals("Unknown (#3)", labeler.labelFor("client-C"))
        assertEquals("Unknown (#1)", labeler.labelFor("client-A"))
        assertEquals("Unknown (#2)", labeler.labelFor("client-B"))
    }

    @Test
    fun `ten sequential clients get numbers 1 through 10`() = runBlocking {
        val labeler = DataStoreUnknownClientLabeler(makeStore())
        val labels = (1..10).map { labeler.labelFor("client-$it") }
        assertEquals(
            (1..10).map { "Unknown (#$it)" },
            labels
        )
    }

    @Test
    fun `second labeler backed by same file continues sequence and recalls prior assignments`() =
        runBlocking {
            val firstScope = newScope()
            val first = DataStoreUnknownClientLabeler(storeIn(firstScope))
            assertEquals("Unknown (#1)", first.labelFor("client-A"))
            assertEquals("Unknown (#2)", first.labelFor("client-B"))
            // Simulate process death: DataStore requires only one active instance
            // per file, so release the first before opening a second pointed at
            // the same path.
            firstScope.cancel()

            val secondScope = newScope()
            val second = DataStoreUnknownClientLabeler(storeIn(secondScope))
            // Previously-seen clients still resolve to their original labels.
            assertEquals("Unknown (#1)", second.labelFor("client-A"))
            assertEquals("Unknown (#2)", second.labelFor("client-B"))
            // A new client continues the sequence from the persisted counter.
            assertEquals("Unknown (#3)", second.labelFor("client-C"))
        }

    @Test
    fun `distinct stores do not share state`() = runBlocking {
        val storeA = makeStore(name = "a.preferences_pb")
        val storeB = makeStore(name = "b.preferences_pb")
        val a = DataStoreUnknownClientLabeler(storeA)
        val b = DataStoreUnknownClientLabeler(storeB)

        assertEquals("Unknown (#1)", a.labelFor("x"))
        assertEquals("Unknown (#2)", a.labelFor("y"))
        // Store B is independent.
        assertEquals("Unknown (#1)", b.labelFor("x"))
    }

    @Test
    fun `concurrent assignments for distinct clients all succeed without collision`() =
        runBlocking {
            val labeler = DataStoreUnknownClientLabeler(makeStore())
            val ids = (1..20).map { "client-$it" }
            val labels = ids.map { id ->
                async(Dispatchers.IO) { labeler.labelFor(id) }
            }.awaitAll()
            // All 20 labels are distinct and fill the range #1..#20 exactly.
            assertEquals(20, labels.toSet().size)
            assertEquals(
                (1..20).map { "Unknown (#$it)" }.toSet(),
                labels.toSet()
            )
            // And every id is stably assigned on re-query.
            val recheck = ids.map { labeler.labelFor(it) }
            assertEquals(labels, recheck)
        }

    @Test
    fun `label format matches the locked design`() = runBlocking {
        val labeler = DataStoreUnknownClientLabeler(makeStore())
        val label = labeler.labelFor("any-client")
        // Per the issue design, the format is "Unknown (#N)" exactly.
        assertTrue(
            "label=$label should match the Unknown (#N) format",
            Regex("^Unknown \\(#\\d+\\)\$").matches(label)
        )
        assertNotEquals("Unknown", label)
        assertNotEquals("unknown", label)
    }

    private fun newScope(): CoroutineScope {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scopes += scope
        return scope
    }

    private fun makeStore(name: String = "labels.preferences_pb"): DataStore<Preferences> =
        storeIn(newScope(), name)

    private fun storeIn(
        scope: CoroutineScope,
        name: String = "labels.preferences_pb"
    ): DataStore<Preferences> = PreferenceDataStoreFactory.create(scope = scope) {
        File(tmpDir.root, name)
    }
}
