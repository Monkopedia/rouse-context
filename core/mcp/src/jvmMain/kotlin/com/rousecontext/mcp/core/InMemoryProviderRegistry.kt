package com.rousecontext.mcp.core

/**
 * In-memory implementation of [ProviderRegistry] for testing and as a reference.
 *
 * Thread-safe via synchronized blocks. The app layer will provide its own
 * implementation backed by IntegrationStateStore.
 */
class InMemoryProviderRegistry : ProviderRegistry {

    private val providers = mutableMapOf<String, McpServerProvider>()
    private val enabled = mutableSetOf<String>()

    /**
     * Registers a provider at the given path prefix.
     */
    fun register(path: String, provider: McpServerProvider) {
        synchronized(this) {
            providers[path] = provider
        }
    }

    /**
     * Enables or disables the integration at the given path.
     */
    fun setEnabled(path: String, isEnabled: Boolean) {
        synchronized(this) {
            if (isEnabled) {
                enabled.add(path)
            } else {
                enabled.remove(path)
            }
        }
    }

    override fun providerForPath(path: String): McpServerProvider? {
        synchronized(this) {
            if (path !in enabled) return null
            return providers[path]
        }
    }

    override fun enabledPaths(): Set<String> {
        synchronized(this) {
            return providers.keys.filter { it in enabled }.toSet()
        }
    }

    /** In-memory state is populated synchronously; the registry is always ready. */
    override suspend fun awaitReady() = Unit

    /** In-memory state is populated synchronously; the registry is always ready. */
    override fun awaitReadyBlocking(timeoutMs: Long): Boolean = true
}
