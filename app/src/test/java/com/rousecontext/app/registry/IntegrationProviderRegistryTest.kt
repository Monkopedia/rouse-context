package com.rousecontext.app.registry

import com.rousecontext.api.IntegrationStateStore
import com.rousecontext.api.McpIntegration
import com.rousecontext.mcp.core.McpServerProvider
import io.modelcontextprotocol.kotlin.sdk.server.Server
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class IntegrationProviderRegistryTest {

    private lateinit var scope: CoroutineScope
    private lateinit var job: Job

    @Before
    fun setUp() {
        job = SupervisorJob()
        scope = CoroutineScope(Dispatchers.Unconfined + job)
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `providerForPath returns null when integration disabled`() = runBlocking {
        val health = fakeIntegration("health", "/health")
        val store = FakeStateStore()
        val registry = IntegrationProviderRegistry(listOf(health), store, scope)

        // Wait for the init collector to seed enabledIds.
        withTimeout(TIMEOUT_MS) { registry.enabledIds.first() }
        assertNull(registry.providerForPath("health"))
        assertEquals(emptySet<String>(), registry.enabledPaths())
    }

    @Test
    fun `providerForPath returns provider when enabled`() = runBlocking {
        val health = fakeIntegration("health", "/health")
        val store = FakeStateStore()
        val registry = IntegrationProviderRegistry(listOf(health), store, scope)

        store.enabledFor("health").value = true
        withTimeout(TIMEOUT_MS) {
            registry.enabledIds.first { "health" in it }
        }

        assertSame(health.provider, registry.providerForPath("health"))
        assertEquals(setOf("health"), registry.enabledPaths())
    }

    @Test
    fun `providerForPath returns null for unknown path`() = runBlocking {
        val health = fakeIntegration("health", "/health")
        val store = FakeStateStore()
        val registry = IntegrationProviderRegistry(listOf(health), store, scope)

        store.enabledFor("health").value = true
        withTimeout(TIMEOUT_MS) {
            registry.enabledIds.first { "health" in it }
        }

        assertNull(registry.providerForPath("nonexistent"))
    }

    @Test
    fun `disabling an integration removes it from enabledPaths`() = runBlocking {
        val health = fakeIntegration("health", "/health")
        val usage = fakeIntegration("usage", "/usage")
        val store = FakeStateStore()
        val registry = IntegrationProviderRegistry(listOf(health, usage), store, scope)

        store.enabledFor("health").value = true
        store.enabledFor("usage").value = true
        withTimeout(TIMEOUT_MS) {
            registry.enabledIds.first { it.size == 2 }
        }
        assertEquals(setOf("health", "usage"), registry.enabledPaths())

        store.enabledFor("usage").value = false
        withTimeout(TIMEOUT_MS) {
            registry.enabledIds.first { "usage" !in it }
        }
        assertEquals(setOf("health"), registry.enabledPaths())
        assertNull(registry.providerForPath("usage"))
    }

    @Test
    fun `empty integration list does not fail`() = runBlocking {
        val store = FakeStateStore()
        val registry = IntegrationProviderRegistry(emptyList(), store, scope)

        assertNull(registry.providerForPath("health"))
        assertEquals(emptySet<String>(), registry.enabledPaths())
        // No need to await — flow never launches and enabledIds stays empty.
        assertEquals(emptySet<String>(), registry.enabledIds.value)
    }

    @Test
    fun `awaitReady completes immediately when integrations list is empty`() = runBlocking {
        val store = FakeStateStore()
        val registry = IntegrationProviderRegistry(emptyList(), store, scope)
        // Should not hang — empty registry is trivially ready.
        withTimeout(TIMEOUT_MS) { registry.awaitReady() }
        assertTrue(
            "awaitReadyBlocking should also report ready",
            registry.awaitReadyBlocking(TIMEOUT_MS)
        )
    }

    @Test
    fun `awaitReady completes after first state-store emission with empty disk state`() =
        runBlocking {
            val health = fakeIntegration("health", "/health")
            val store = FakeStateStore()
            val registry = IntegrationProviderRegistry(listOf(health), store, scope)

            // Default FakeStateStore emits `false` immediately, which is a valid
            // first emission ("nothing enabled on disk"). awaitReady must return.
            withTimeout(TIMEOUT_MS) { registry.awaitReady() }
            assertEquals(emptySet<String>(), registry.enabledPaths())
        }

    @Test
    fun `awaitReady completes after first state-store emission with enabled state`() = runBlocking {
        val health = fakeIntegration("health", "/health")
        val store = FakeStateStore()
        store.enabledFor("health").value = true
        val registry = IntegrationProviderRegistry(listOf(health), store, scope)

        withTimeout(TIMEOUT_MS) { registry.awaitReady() }
        assertEquals(setOf("health"), registry.enabledPaths())
    }

    @Test
    fun `awaitReady is idempotent across multiple calls`() = runBlocking {
        val health = fakeIntegration("health", "/health")
        val store = FakeStateStore()
        val registry = IntegrationProviderRegistry(listOf(health), store, scope)

        withTimeout(TIMEOUT_MS) { registry.awaitReady() }
        // A second call must return immediately (already complete).
        withTimeout(TIMEOUT_MS) { registry.awaitReady() }
        // And awaitReadyBlocking with a tiny timeout must also return true.
        assertTrue(registry.awaitReadyBlocking(50))
    }

    @Test
    fun `awaitReady does not complete before the first emission lands`() = runBlocking {
        // Use a slow-loading state store: its observeUserEnabled flow only emits
        // after the test signals it. This proves awaitReady actually waits for
        // the first emission rather than completing on construction.
        val health = fakeIntegration("health", "/health")
        val gate = CompletableDeferred<Unit>()
        val slowStore = SlowFakeStateStore(gate, initialEnabled = true)
        val registry = IntegrationProviderRegistry(listOf(health), slowStore, scope)

        // awaitReadyBlocking with a short timeout must report `false` while gated.
        assertEquals(false, registry.awaitReadyBlocking(50))

        // Open the gate, then awaitReady should complete and reflect enabled state.
        gate.complete(Unit)
        withTimeout(TIMEOUT_MS) { registry.awaitReady() }
        assertEquals(setOf("health"), registry.enabledPaths())
    }

    /** State store whose [observeUserEnabled] flow emits only after [gate] completes. */
    private class SlowFakeStateStore(
        private val gate: CompletableDeferred<Unit>,
        private val initialEnabled: Boolean
    ) : IntegrationStateStore {
        override suspend fun isUserEnabled(integrationId: String): Boolean = initialEnabled
        override suspend fun setUserEnabled(integrationId: String, enabled: Boolean) = Unit
        override fun observeUserEnabled(integrationId: String): Flow<Boolean> = flow {
            gate.await()
            emit(initialEnabled)
        }
        override suspend fun wasEverEnabled(integrationId: String): Boolean = initialEnabled
        override fun observeEverEnabled(integrationId: String): Flow<Boolean> = flow {
            emit(initialEnabled)
        }
        override fun observeChanges(): Flow<Unit> = flow { emit(Unit) }
    }

    private fun fakeIntegration(integrationId: String, integrationPath: String): McpIntegration =
        object : McpIntegration {
            override val id = integrationId
            override val displayName = integrationId
            override val description = ""
            override val path = integrationPath
            override val onboardingRoute = "setup"
            override val settingsRoute = "settings"
            override val provider: McpServerProvider = object : McpServerProvider {
                override val id = integrationId
                override val displayName = integrationId
                override fun register(server: Server) = Unit
            }

            override suspend fun isAvailable(): Boolean = true
        }

    private class FakeStateStore : IntegrationStateStore {
        private val enabled = mutableMapOf<String, MutableStateFlow<Boolean>>()
        private val ever = mutableMapOf<String, MutableStateFlow<Boolean>>()

        fun enabledFor(id: String): MutableStateFlow<Boolean> =
            enabled.getOrPut(id) { MutableStateFlow(false) }

        private fun everFor(id: String): MutableStateFlow<Boolean> =
            ever.getOrPut(id) { MutableStateFlow(false) }

        override suspend fun isUserEnabled(integrationId: String): Boolean =
            enabledFor(integrationId).value

        override suspend fun setUserEnabled(integrationId: String, enabled: Boolean) {
            if (enabled) everFor(integrationId).value = true
            enabledFor(integrationId).value = enabled
        }

        override fun observeUserEnabled(integrationId: String): Flow<Boolean> =
            enabledFor(integrationId)

        override suspend fun wasEverEnabled(integrationId: String): Boolean =
            everFor(integrationId).value

        override fun observeEverEnabled(integrationId: String): Flow<Boolean> =
            everFor(integrationId)

        override fun observeChanges(): Flow<Unit> = enabledFor("__any__").map { }
    }

    companion object {
        private const val TIMEOUT_MS = 5_000L
    }
}
