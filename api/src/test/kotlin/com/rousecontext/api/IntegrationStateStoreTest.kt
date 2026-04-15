package com.rousecontext.api

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class IntegrationStateStoreTest {

    private lateinit var store: FakeIntegrationStateStore

    @Before
    fun setUp() {
        store = FakeIntegrationStateStore()
    }

    @Test
    fun `default state is disabled`() = runBlocking {
        assertFalse(store.isUserEnabled("health"))
    }

    @Test
    fun `enable persists`() = runBlocking {
        store.setUserEnabled("health", true)
        assertTrue(store.isUserEnabled("health"))
    }

    @Test
    fun `disable after enable persists`() = runBlocking {
        store.setUserEnabled("health", true)
        store.setUserEnabled("health", false)
        assertFalse(store.isUserEnabled("health"))
    }

    @Test
    fun `different integrations are independent`() = runBlocking {
        store.setUserEnabled("health", true)
        store.setUserEnabled("notifications", false)

        assertTrue(store.isUserEnabled("health"))
        assertFalse(store.isUserEnabled("notifications"))
    }

    @Test
    fun `observe emits current value`() = runBlocking {
        store.setUserEnabled("health", true)
        val value = store.observeUserEnabled("health").first()
        assertTrue(value)
    }

    @Test
    fun `observe emits changes`() = runBlocking {
        val flow = store.observeUserEnabled("health")

        // Default is false
        assertEquals(false, flow.first())

        // Enable
        store.setUserEnabled("health", true)
        assertEquals(true, flow.first())

        // Disable again
        store.setUserEnabled("health", false)
        assertEquals(false, flow.first())
    }

    @Test
    fun `wasEverEnabled is false by default`() = runBlocking {
        assertFalse(store.wasEverEnabled("health"))
    }

    @Test
    fun `wasEverEnabled becomes true after enabling`() = runBlocking {
        store.setUserEnabled("health", true)
        assertTrue(store.wasEverEnabled("health"))
    }

    @Test
    fun `wasEverEnabled stays true after disabling`() = runBlocking {
        store.setUserEnabled("health", true)
        store.setUserEnabled("health", false)
        assertTrue(store.wasEverEnabled("health"))
    }
}

/**
 * In-memory implementation of [IntegrationStateStore] for testing.
 */
private class FakeIntegrationStateStore : IntegrationStateStore {
    private val states = mutableMapOf<String, MutableStateFlow<Boolean>>()
    private val everEnabledFlows = mutableMapOf<String, MutableStateFlow<Boolean>>()

    private fun flowFor(integrationId: String): MutableStateFlow<Boolean> =
        states.getOrPut(integrationId) { MutableStateFlow(false) }

    private fun everFlowFor(integrationId: String): MutableStateFlow<Boolean> =
        everEnabledFlows.getOrPut(integrationId) { MutableStateFlow(false) }

    override suspend fun isUserEnabled(integrationId: String): Boolean =
        flowFor(integrationId).value

    override suspend fun setUserEnabled(integrationId: String, enabled: Boolean) {
        if (enabled) {
            everFlowFor(integrationId).value = true
        }
        flowFor(integrationId).value = enabled
        changeSignal.value++
    }

    override fun observeUserEnabled(integrationId: String) = flowFor(integrationId)

    override suspend fun wasEverEnabled(integrationId: String): Boolean =
        everFlowFor(integrationId).value

    override fun observeEverEnabled(integrationId: String): Flow<Boolean> =
        everFlowFor(integrationId)

    private val changeSignal = MutableStateFlow(0)

    override fun observeChanges(): Flow<Unit> = changeSignal.map { }
}
