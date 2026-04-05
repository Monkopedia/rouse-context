package com.rousecontext.mcp.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TokenStoreTest {

    @Test
    fun `create and validate token`() {
        val store = InMemoryTokenStore()
        val token = store.createToken("health", "client-1")

        assertTrue(store.validateToken("health", token))
    }

    @Test
    fun `revoked token is invalid`() {
        val store = InMemoryTokenStore()
        val token = store.createToken("health", "client-1")

        store.revokeToken("health", token)
        assertFalse(store.validateToken("health", token))
    }

    @Test
    fun `unknown token is invalid`() {
        val store = InMemoryTokenStore()
        assertFalse(store.validateToken("health", "nonexistent-token"))
    }

    @Test
    fun `cross-integration token rejected`() {
        val store = InMemoryTokenStore()
        val token = store.createToken("health", "client-1")

        // Token for health should not validate for notifications
        assertFalse(store.validateToken("notifications", token))
    }

    @Test
    fun `listTokens returns all active tokens for integration`() {
        val store = InMemoryTokenStore()
        store.createToken("health", "client-1")
        store.createToken("health", "client-2")
        store.createToken("notifications", "client-3")

        val healthTokens = store.listTokens("health")
        assertEquals(2, healthTokens.size)
        assertEquals(setOf("client-1", "client-2"), healthTokens.map { it.clientId }.toSet())
    }

    @Test
    fun `listTokens excludes revoked tokens`() {
        val store = InMemoryTokenStore()
        val token1 = store.createToken("health", "client-1")
        store.createToken("health", "client-2")

        store.revokeToken("health", token1)

        val healthTokens = store.listTokens("health")
        assertEquals(1, healthTokens.size)
        assertEquals("client-2", healthTokens.first().clientId)
    }

    @Test
    fun `hasTokens returns true when tokens exist`() {
        val store = InMemoryTokenStore()
        assertFalse(store.hasTokens("health"))

        store.createToken("health", "client-1")
        assertTrue(store.hasTokens("health"))
    }

    @Test
    fun `hasTokens returns false after all tokens revoked`() {
        val store = InMemoryTokenStore()
        val token = store.createToken("health", "client-1")

        store.revokeToken("health", token)
        assertFalse(store.hasTokens("health"))
    }
}
