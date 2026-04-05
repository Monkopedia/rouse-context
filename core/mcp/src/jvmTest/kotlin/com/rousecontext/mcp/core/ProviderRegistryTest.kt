package com.rousecontext.mcp.core

import io.modelcontextprotocol.kotlin.sdk.server.Server
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ProviderRegistryTest {

    private class StubProvider(
        override val id: String,
        override val displayName: String
    ) : McpServerProvider {
        override fun register(server: Server) {
            // no-op for registry tests
        }
    }

    @Test
    fun `enabled integration found by path`() {
        val health = StubProvider("health", "Health Connect")
        val registry = InMemoryProviderRegistry()
        registry.register("health", health)
        registry.setEnabled("health", true)

        assertNotNull(registry.providerForPath("health"))
        assertEquals("health", registry.providerForPath("health")!!.id)
    }

    @Test
    fun `disabled integration returns null`() {
        val health = StubProvider("health", "Health Connect")
        val registry = InMemoryProviderRegistry()
        registry.register("health", health)
        registry.setEnabled("health", false)

        assertNull(registry.providerForPath("health"))
    }

    @Test
    fun `unregistered path returns null`() {
        val registry = InMemoryProviderRegistry()
        assertNull(registry.providerForPath("unknown"))
    }

    @Test
    fun `enable disable reflected immediately`() {
        val health = StubProvider("health", "Health Connect")
        val registry = InMemoryProviderRegistry()
        registry.register("health", health)
        registry.setEnabled("health", true)

        assertNotNull(registry.providerForPath("health"))

        registry.setEnabled("health", false)
        assertNull(registry.providerForPath("health"))

        registry.setEnabled("health", true)
        assertNotNull(registry.providerForPath("health"))
    }

    @Test
    fun `enabledPaths returns only enabled integrations`() {
        val health = StubProvider("health", "Health Connect")
        val notifications = StubProvider("notifications", "Notifications")
        val registry = InMemoryProviderRegistry()
        registry.register("health", health)
        registry.register("notifications", notifications)
        registry.setEnabled("health", true)
        registry.setEnabled("notifications", false)

        assertEquals(setOf("health"), registry.enabledPaths())

        registry.setEnabled("notifications", true)
        assertEquals(setOf("health", "notifications"), registry.enabledPaths())
    }
}
