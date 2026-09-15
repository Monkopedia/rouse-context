package com.rousecontext.app.token

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.rousecontext.app.TestApplication
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression test for issue #760 (F-Droid security audit of v1.0.11).
 *
 * #750 made the parent rotation a compare-and-swap, which stopped two
 * concurrent redemptions from both minting a descendant. But the CAS and the
 * child insert remained two separate database operations, and a losing
 * caller's family revocation runs between them:
 *
 * ```
 * A: markRotatedIfUnrotated(parent) -> 1   (A wins)
 * B: markRotatedIfUnrotated(parent) -> 0   (B loses, reuse detected)
 * B: deleteByFamilyId(family)              <-- A's child does not exist yet
 * A: INSERT child (same family)            <-- live token in a revoked family
 * ```
 *
 * The property under test is NOT "at most one descendant" -- that one already
 * holds, and [RoomTokenStoreConcurrentRefreshTest] covers it. It is the
 * stronger OAuth 2.1 §4.14 property: **once reuse is detected, the family
 * stays revoked.**
 *
 * `at most one survivor` would be satisfied by a store that refuses every
 * refresh, so the revocation assertion below demands the family be *empty* and
 * is paired with [`normal rotation still mints a working child`], which fails
 * on any such degenerate implementation.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = TestApplication::class)
class RoomTokenStoreFamilyRevocationTest {

    @get:Rule
    val timeout: Timeout = Timeout.seconds(120)

    private lateinit var db: TokenDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, TokenDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    /**
     * Holds the CAS winner between winning the rotation and inserting its
     * child, lets the loser run family revocation, then releases the winner.
     * Afterwards the family must contain nothing at all.
     */
    @Test
    fun `family stays revoked when reuse is detected mid-rotation`() = runBlocking {
        val realDao = db.tokenDao()
        val casWon = CompletableDeferred<Unit>()
        val familyRevoked = CompletableDeferred<Unit>()
        val casCalls = AtomicInteger(0)

        val dao = object : TokenDao by realDao {
            override fun markRotatedIfUnrotated(id: Long, rotatedAt: Long): Int {
                val result = realDao.markRotatedIfUnrotated(id, rotatedAt)
                casCalls.incrementAndGet()
                if (result == 1) {
                    casWon.complete(Unit)
                    // Hold the winner here: it has consumed the parent but has
                    // not yet minted the child. This is the whole point of the
                    // reproduction.
                    //
                    // The bound is a liveness guard, not a correctness one. On
                    // the defective tree the loser revokes in microseconds and
                    // this returns at once. On a tree where the rotation is one
                    // serialized operation the loser CANNOT reach revocation
                    // yet -- it is queued behind this very rotation -- so
                    // without a bound the reproduction would deadlock itself.
                    // No assertion below depends on which branch was taken.
                    runBlocking {
                        withTimeoutOrNull(WINNER_HOLD_MS) { familyRevoked.await() }
                    }
                }
                return result
            }

            override fun deleteByFamilyId(familyId: String) {
                realDao.deleteByFamilyId(familyId)
                familyRevoked.complete(Unit)
            }
        }

        val store = RoomTokenStore(dao, RoomTransactionRunner(db))
        val parent = store.createTokenPair("health", "client-race", "Client")
        val familyId = requireNotNull(
            realDao.findByRefreshHash("health", RoomTokenStore.hashToken(parent.refreshToken))
        ).familyId

        val winner = async(Dispatchers.IO) { store.refreshToken("health", parent.refreshToken) }
        val loser = async(Dispatchers.IO) {
            // Do not even start until the winner owns the rotation.
            casWon.await()
            store.refreshToken("health", parent.refreshToken)
        }
        val winnerPair = winner.await()
        val loserPair = loser.await()

        val survivors = familyRowCount(familyId)
        println(
            "[measured] reuse mid-rotation: winner minted=${winnerPair != null}, " +
                "loser minted=${loserPair != null}, rows left in family=$survivors"
        )

        // Guards: without these the test could pass by never setting up the race.
        assertEquals("both callers must have reached the rotation CAS", 2, casCalls.get())
        assertNull("the second redemption is reuse and must mint nothing", loserPair)
        assertTrue("the loser must have run family revocation", familyRevoked.isCompleted)

        // The finding.
        assertEquals(
            "OAuth 2.1 §4.14: after reuse detection no row of the family may survive",
            0,
            survivors
        )
        if (winnerPair != null) {
            val verifier = RoomTokenStore(realDao, RoomTransactionRunner(db))
            assertFalse(
                "a token minted into a revoked family must not validate",
                verifier.validateToken("health", winnerPair.accessToken)
            )
            assertNull(
                "a token minted into a revoked family must not be refreshable",
                verifier.refreshToken("health", winnerPair.refreshToken)
            )
        }

        coroutineContext.cancelChildren()
    }

    /**
     * Positive control. "Revoke everything, always" satisfies every assertion
     * in the test above; it fails here. An ordinary rotation must still burn
     * the parent, mint a usable child, and leave that child refreshable.
     */
    @Test
    fun `normal rotation still mints a working child`() {
        val store = RoomTokenStore(db.tokenDao(), RoomTransactionRunner(db))
        val parent = store.createTokenPair("health", "client-ok", "Client")

        val child = requireNotNull(store.refreshToken("health", parent.refreshToken)) {
            "an uncontended refresh must mint a child"
        }
        assertTrue(
            "the minted child access token must validate",
            store.validateToken("health", child.accessToken)
        )
        assertFalse(
            "the parent access token must be dead after rotation",
            store.validateToken("health", parent.accessToken)
        )

        val grandchild = requireNotNull(store.refreshToken("health", child.refreshToken)) {
            "the child must itself be refreshable"
        }
        assertTrue(
            "the grandchild access token must validate",
            store.validateToken("health", grandchild.accessToken)
        )
        assertEquals(
            "exactly the newest descendant stays live",
            1,
            db.tokenDao().listByIntegration("health").size
        )
    }

    /** Counts every row of [familyId], rotated ones included. */
    private fun familyRowCount(familyId: String): Int =
        db.query("SELECT COUNT(*) FROM tokens WHERE familyId = ?", arrayOf<Any>(familyId))
            .use { cursor ->
                cursor.moveToFirst()
                cursor.getInt(0)
            }

    private companion object {
        const val WINNER_HOLD_MS = 3_000L
    }
}
