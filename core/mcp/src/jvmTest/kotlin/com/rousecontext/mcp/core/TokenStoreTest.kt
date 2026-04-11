package com.rousecontext.mcp.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TokenStoreTest {

    @Test
    fun `create and validate token`() {
        val store = InMemoryTokenStore()
        val pair = store.createTokenPair("health", "client-1")

        assertTrue(store.validateToken("health", pair.accessToken))
    }

    @Test
    fun `revoked token is invalid`() {
        val store = InMemoryTokenStore()
        val pair = store.createTokenPair("health", "client-1")

        store.revokeToken("health", pair.accessToken)
        assertFalse(store.validateToken("health", pair.accessToken))
    }

    @Test
    fun `unknown token is invalid`() {
        val store = InMemoryTokenStore()
        assertFalse(store.validateToken("health", "nonexistent-token"))
    }

    @Test
    fun `cross-integration token rejected`() {
        val store = InMemoryTokenStore()
        val pair = store.createTokenPair("health", "client-1")

        // Token for health should not validate for notifications
        assertFalse(store.validateToken("notifications", pair.accessToken))
    }

    @Test
    fun `listTokens returns all active tokens for integration`() {
        val store = InMemoryTokenStore()
        store.createTokenPair("health", "client-1")
        store.createTokenPair("health", "client-2")
        store.createTokenPair("notifications", "client-3")

        val healthTokens = store.listTokens("health")
        assertEquals(2, healthTokens.size)
        assertEquals(setOf("client-1", "client-2"), healthTokens.map { it.clientId }.toSet())
    }

    @Test
    fun `listTokens excludes revoked tokens`() {
        val store = InMemoryTokenStore()
        val pair1 = store.createTokenPair("health", "client-1")
        store.createTokenPair("health", "client-2")

        store.revokeToken("health", pair1.accessToken)

        val healthTokens = store.listTokens("health")
        assertEquals(1, healthTokens.size)
        assertEquals("client-2", healthTokens.first().clientId)
    }

    @Test
    fun `hasTokens returns true when tokens exist`() {
        val store = InMemoryTokenStore()
        assertFalse(store.hasTokens("health"))

        store.createTokenPair("health", "client-1")
        assertTrue(store.hasTokens("health"))
    }

    @Test
    fun `hasTokens returns false after all tokens revoked`() {
        val store = InMemoryTokenStore()
        val pair = store.createTokenPair("health", "client-1")

        store.revokeToken("health", pair.accessToken)
        assertFalse(store.hasTokens("health"))
    }

    @Test
    fun `createTokenPair returns access token and refresh token`() {
        val store = InMemoryTokenStore()
        val pair = store.createTokenPair("health", "client-1")

        assertTrue(pair.accessToken.isNotEmpty())
        assertTrue(pair.refreshToken.isNotEmpty())
        assertEquals(ACCESS_TOKEN_EXPIRES_IN_SECONDS, pair.expiresIn)
    }

    @Test
    fun `expired access token is invalid`() {
        val clock = FakeClock()
        val store = InMemoryTokenStore(clock)
        val pair = store.createTokenPair("health", "client-1")

        assertTrue(store.validateToken("health", pair.accessToken))

        // Advance past 1-hour access token TTL
        clock.advanceMinutes(61)

        assertFalse(store.validateToken("health", pair.accessToken))
    }

    @Test
    fun `refresh token returns new token pair`() {
        val store = InMemoryTokenStore()
        val pair = store.createTokenPair("health", "client-1")

        val newPair = store.refreshToken("health", pair.refreshToken)
        assertNotNull(newPair)
        assertTrue(newPair!!.accessToken.isNotEmpty())
        assertTrue(newPair.refreshToken.isNotEmpty())
        assertTrue(store.validateToken("health", newPair.accessToken))
    }

    @Test
    fun `refresh token rotates - old refresh token invalidated`() {
        val store = InMemoryTokenStore()
        val pair = store.createTokenPair("health", "client-1")

        val newPair = store.refreshToken("health", pair.refreshToken)
        assertNotNull(newPair)

        // Old refresh token should no longer work
        val secondAttempt = store.refreshToken("health", pair.refreshToken)
        assertNull(secondAttempt)
    }

    @Test
    fun `refresh token with wrong integration returns null`() {
        val store = InMemoryTokenStore()
        val pair = store.createTokenPair("health", "client-1")

        val result = store.refreshToken("notifications", pair.refreshToken)
        assertNull(result)
    }

    @Test
    fun `expired refresh token returns null`() {
        val clock = FakeClock()
        val store = InMemoryTokenStore(clock)
        val pair = store.createTokenPair("health", "client-1")

        // Advance past 30-day refresh token TTL
        clock.advanceMinutes(31 * 24 * 60)

        val result = store.refreshToken("health", pair.refreshToken)
        assertNull(result)
    }

    @Test
    fun `refresh token revokes old access token`() {
        val store = InMemoryTokenStore()
        val pair = store.createTokenPair("health", "client-1")

        assertTrue(store.validateToken("health", pair.accessToken))

        val newPair = store.refreshToken("health", pair.refreshToken)
        assertNotNull(newPair)

        // Old access token should be revoked after refresh
        assertFalse(store.validateToken("health", pair.accessToken))
        // New access token should work
        assertTrue(store.validateToken("health", newPair!!.accessToken))
    }

    @Test
    fun `unknown refresh token returns null`() {
        val store = InMemoryTokenStore()
        val result = store.refreshToken("health", "nonexistent-token")
        assertNull(result)
    }
}
