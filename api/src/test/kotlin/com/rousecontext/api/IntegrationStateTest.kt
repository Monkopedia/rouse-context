package com.rousecontext.api

import org.junit.Assert.assertEquals
import org.junit.Test

class IntegrationStateTest {

    @Test
    fun `userEnabled false and never enabled returns Available`() {
        val state = deriveIntegrationState(
            userEnabled = false,
            wasEverEnabled = false,
            isAvailable = true,
            hasTokens = false
        )
        assertEquals(IntegrationState.Available, state)
    }

    @Test
    fun `userEnabled false and was ever enabled returns Disabled`() {
        val state = deriveIntegrationState(
            userEnabled = false,
            wasEverEnabled = true,
            isAvailable = true,
            hasTokens = false
        )
        assertEquals(IntegrationState.Disabled, state)
    }

    @Test
    fun `userEnabled true and not available returns Unavailable`() {
        val state = deriveIntegrationState(
            userEnabled = true,
            wasEverEnabled = true,
            isAvailable = false,
            hasTokens = false
        )
        assertEquals(IntegrationState.Unavailable, state)
    }

    @Test
    fun `userEnabled true and available but no tokens returns Pending`() {
        val state = deriveIntegrationState(
            userEnabled = true,
            wasEverEnabled = true,
            isAvailable = true,
            hasTokens = false
        )
        assertEquals(IntegrationState.Pending, state)
    }

    @Test
    fun `userEnabled true and available and has tokens returns Active`() {
        val state = deriveIntegrationState(
            userEnabled = true,
            wasEverEnabled = true,
            isAvailable = true,
            hasTokens = true
        )
        assertEquals(IntegrationState.Active, state)
    }

    @Test
    fun `not available and not enabled returns Unavailable`() {
        val state = deriveIntegrationState(
            userEnabled = false,
            wasEverEnabled = false,
            isAvailable = false,
            hasTokens = false
        )
        assertEquals(IntegrationState.Unavailable, state)
    }

    @Test
    fun `disabled with tokens still returns Disabled`() {
        val state = deriveIntegrationState(
            userEnabled = false,
            wasEverEnabled = true,
            isAvailable = true,
            hasTokens = true
        )
        assertEquals(IntegrationState.Disabled, state)
    }
}
