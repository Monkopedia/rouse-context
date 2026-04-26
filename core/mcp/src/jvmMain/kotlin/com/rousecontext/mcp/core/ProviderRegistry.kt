package com.rousecontext.mcp.core

/**
 * Live registry of enabled integration providers.
 *
 * The app implements this backed by [IntegrationStateStore]. McpSession queries it
 * per-request to resolve path prefixes to providers.
 *
 * Changes (enable/disable) are reflected immediately - no session reconstruction needed.
 *
 * Implementations may load enabled state asynchronously (e.g. from DataStore). Callers
 * that need to make a decision based on [enabledPaths] at process-startup time MUST
 * suspend on [awaitReady] (or [awaitReadyBlocking] for non-coroutine callers) before
 * reading the snapshot — otherwise the registry may report "no integrations enabled"
 * during the brief window between construction and the first state-store emission.
 * See issue #414.
 */
interface ProviderRegistry {

    /**
     * Returns the provider registered at the given path prefix, or null if no provider
     * is registered or the integration is disabled.
     *
     * @param path first path segment from the HTTP request (e.g. "health", "notifications")
     */
    fun providerForPath(path: String): McpServerProvider?

    /**
     * Returns the set of currently enabled path prefixes (e.g. ["health", "notifications"]).
     *
     * The result is a snapshot of the in-memory state. On a freshly-constructed registry,
     * this may return an empty set even when the user has enabled integrations on disk —
     * callers MUST [awaitReady] first if they need to act on the absence of integrations.
     */
    fun enabledPaths(): Set<String>

    /**
     * Suspends until the registry has loaded its initial enabled-state snapshot.
     * Subsequent calls return immediately.
     *
     * After this returns, [enabledPaths] reflects the on-disk state (which may still be
     * empty if no integrations are enabled — that is a valid loaded state).
     */
    suspend fun awaitReady()

    /**
     * Thread-blocking variant of [awaitReady] for callers that cannot suspend (e.g.
     * [com.google.firebase.messaging.FirebaseMessagingService.onMessageReceived], which
     * runs on a background worker thread that the FCM service holds a wakelock for).
     *
     * Returns `true` if the registry became ready within [timeoutMs]; `false` otherwise.
     * MUST NOT be called from the main thread.
     */
    fun awaitReadyBlocking(timeoutMs: Long): Boolean
}
