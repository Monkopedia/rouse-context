package com.rousecontext.app.token

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.rousecontext.app.TestApplication
import com.rousecontext.mcp.core.TokenPair
import java.util.concurrent.BrokenBarrierException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression test for the F-Droid audit finding (reported by @andrewpozdnakov7
 * on fdroiddata!42096): refresh-token rotation in [RoomTokenStore.refreshToken]
 * was read -> check -> write with no transaction, no conditional UPDATE and no
 * lock, so two concurrent redemptions of one refresh token could both mint a
 * descendant. Both then looked legitimate, the family was never revoked, and a
 * stolen refresh token survived indefinitely.
 *
 * Measured on the unfixed tree: 199 of 200 unassisted rounds minted two
 * descendants, and the deterministic interleaving minted two every time.
 *
 * Both tests assert **exactly one** descendant, not "at most one". "At most"
 * is satisfied by a store that rejects every refresh, which is not a fix; the
 * lower bound is what keeps an ordinary single refresh working.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = TestApplication::class)
class RoomTokenStoreConcurrentRefreshTest {

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
     * Unassisted race: two threads redeem the same refresh token at the same
     * time, repeated many times. Exactly one redemption per parent may win.
     */
    @Test
    fun `concurrent refresh of one token mints exactly one descendant`() {
        val store = RoomTokenStore(db.tokenDao(), RoomTransactionRunner(db))
        var doubleMints = 0
        var zeroMints = 0
        val attempts = 200
        repeat(attempts) { round ->
            val parent = store.createTokenPair("health", "client-$round", "Client")
            val barrier = CyclicBarrier(2)
            val done = CountDownLatch(2)
            val a = AtomicReference<TokenPair?>()
            val b = AtomicReference<TokenPair?>()
            val t1 = Thread {
                barrier.await()
                a.set(store.refreshToken("health", parent.refreshToken))
                done.countDown()
            }
            val t2 = Thread {
                barrier.await()
                b.set(store.refreshToken("health", parent.refreshToken))
                done.countDown()
            }
            t1.start()
            t2.start()
            done.await()
            val winners = listOfNotNull(a.get(), b.get()).size
            if (winners > 1) doubleMints++
            if (winners == 0) zeroMints++
        }
        println(
            "[measured] unassisted race over $attempts rounds: " +
                "$doubleMints minted two descendants, $zeroMints minted none"
        )
        assertEquals("rounds where both concurrent refreshes succeeded", 0, doubleMints)
        assertEquals("rounds where neither refresh succeeded", 0, zeroMints)
    }

    /**
     * Deterministic version: a DAO decorator holds each caller between the read
     * and the rotating write, which is exactly the interleaving the unassisted
     * test is trying to hit by luck. Only an atomic rotate -- a conditional
     * UPDATE, a transaction, or a lock -- can reject the second caller from
     * here.
     *
     * The barrier is asserted to have tripped. Without that check this test
     * would also pass if the two callers happened to run one after the other,
     * i.e. if it never set up the race it exists to test.
     */
    @Test
    fun `interleaved refresh of one token mints exactly one descendant`() {
        val gate = CyclicBarrier(2)
        val interleavedReads = AtomicInteger(0)
        val dao = object : TokenDao by db.tokenDao() {
            override fun findByRefreshHash(
                integrationId: String,
                refreshTokenHash: String
            ): TokenEntity? {
                val row = db.tokenDao().findByRefreshHash(integrationId, refreshTokenHash)
                // Both callers must finish their read before either writes.
                try {
                    gate.await(GATE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    interleavedReads.incrementAndGet()
                } catch (_: TimeoutException) {
                    // Asserted on below; do not hang the test.
                } catch (_: BrokenBarrierException) {
                    // Ditto.
                }
                return row
            }
        }
        val store = RoomTokenStore(dao, RoomTransactionRunner(db))
        val parent = store.createTokenPair("health", "client-x", "Client")

        val done = CountDownLatch(2)
        val a = AtomicReference<TokenPair?>()
        val b = AtomicReference<TokenPair?>()
        Thread {
            a.set(store.refreshToken("health", parent.refreshToken))
            done.countDown()
        }.start()
        Thread {
            b.set(store.refreshToken("health", parent.refreshToken))
            done.countDown()
        }.start()
        done.await()

        val minted = listOfNotNull(a.get(), b.get())
        val live = db.tokenDao().listByIntegration("health")
        println(
            "[measured] interleaved race: ${minted.size} descendants minted, " +
                "${live.size} live (non-rotated) rows remain"
        )
        assertEquals(
            "both callers must have read before either wrote, or this test proves nothing",
            2,
            interleavedReads.get()
        )
        assertEquals("descendants minted from one refresh token", 1, minted.size)
        // Issue #760: the losing caller revoked the family, so nothing of it
        // may be left behind -- not even the descendant the winner minted.
        // Asserting only on `minted.size` let a live token survive in a
        // revoked family; `RoomTokenStoreFamilyRevocationTest` reproduces that
        // deterministically.
        assertEquals("a revoked family must leave no live rows", 0, live.size)
    }

    /**
     * The lower bound, stated on its own so a regression to "reject
     * everything" cannot hide behind the race tests: an uncontended refresh
     * still rotates, still mints, and the parent is still single-use.
     */
    @Test
    fun `uncontended refresh mints one descendant and burns the parent`() {
        val store = RoomTokenStore(db.tokenDao(), RoomTransactionRunner(db))
        val parent = store.createTokenPair("health", "client-seq", "Client")

        val child = store.refreshToken("health", parent.refreshToken)
        assertTrue("an uncontended refresh must succeed", child != null)

        // Replaying the parent is reuse: it mints nothing and revokes the family.
        assertEquals(null, store.refreshToken("health", parent.refreshToken))
        assertEquals(
            "family must be revoked after a replay",
            0,
            db.tokenDao().listByIntegration("health").size
        )
    }

    private companion object {
        const val GATE_TIMEOUT_SECONDS = 30L
    }
}
