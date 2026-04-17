package com.rousecontext.app.token

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.rousecontext.app.TestApplication
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for [RoomTokenStore], in particular the refresh-token family/reuse
 * detection behavior required by OAuth 2.1 §4.14.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = TestApplication::class)
class RoomTokenStoreTest {

    private lateinit var db: TokenDatabase
    private lateinit var store: RoomTokenStore

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, TokenDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        store = RoomTokenStore(db.tokenDao())
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `sequential legitimate refreshes rotate forward`() {
        val pair1 = store.createTokenPair("health", "client-1")
        val pair2 = store.refreshToken("health", pair1.refreshToken)
        assertNotNull(pair2)
        val pair3 = store.refreshToken("health", pair2!!.refreshToken)
        assertNotNull(pair3)

        // Latest access token validates; earlier ones do not.
        assertTrue(store.validateToken("health", pair3!!.accessToken))
        assertFalse(store.validateToken("health", pair1.accessToken))
        assertFalse(store.validateToken("health", pair2.accessToken))
    }

    @Test
    fun `reused rotated refresh token revokes entire family`() {
        val pair1 = store.createTokenPair("health", "client-1")
        val pair2 = store.refreshToken("health", pair1.refreshToken)
        assertNotNull(pair2)
        val pair3 = store.refreshToken("health", pair2!!.refreshToken)
        assertNotNull(pair3)

        // Attacker replays a previously-rotated refresh token.
        val replay = store.refreshToken("health", pair1.refreshToken)
        assertNull(replay)

        // Entire family is revoked.
        assertFalse(store.validateToken("health", pair3!!.accessToken))
        assertNull(store.refreshToken("health", pair3.refreshToken))
        assertFalse(store.hasTokens("health"))
    }

    @Test
    fun `reused middle refresh token revokes entire family`() {
        val pair1 = store.createTokenPair("health", "client-1")
        val pair2 = store.refreshToken("health", pair1.refreshToken)
        assertNotNull(pair2)
        val pair3 = store.refreshToken("health", pair2!!.refreshToken)
        assertNotNull(pair3)

        val replay = store.refreshToken("health", pair2.refreshToken)
        assertNull(replay)

        assertFalse(store.validateToken("health", pair3!!.accessToken))
        assertNull(store.refreshToken("health", pair3.refreshToken))
    }

    @Test
    fun `reuse detection only revokes the compromised family`() {
        val aliceA = store.createTokenPair("health", "alice")
        val bobA = store.createTokenPair("health", "bob")

        val aliceB = store.refreshToken("health", aliceA.refreshToken)
        assertNotNull(aliceB)

        // Alice's old refresh token is replayed — her family is revoked.
        assertNull(store.refreshToken("health", aliceA.refreshToken))
        assertFalse(store.validateToken("health", aliceB!!.accessToken))

        // Bob is unaffected.
        assertTrue(store.validateToken("health", bobA.accessToken))
        val bobB = store.refreshToken("health", bobA.refreshToken)
        assertNotNull(bobB)
        assertTrue(store.validateToken("health", bobB!!.accessToken))
    }

    @Test
    fun `unknown refresh token returns null`() {
        assertNull(store.refreshToken("health", "nonexistent-token"))
    }

    @Test
    fun `refresh with wrong integration returns null and does not revoke family`() {
        val pair = store.createTokenPair("health", "client-1")

        val result = store.refreshToken("notifications", pair.refreshToken)
        assertNull(result)

        // Original access token still valid — wrong-integration attempts are not reuse.
        assertTrue(store.validateToken("health", pair.accessToken))
    }
}
