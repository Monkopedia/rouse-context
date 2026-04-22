package com.rousecontext.mcp.core

/**
 * Assigns monotonic `Unknown (#N)` labels to OAuth clients that did not
 * supply a human-readable `client_name` during Dynamic Client Registration.
 *
 * The same `clientId` always resolves to the same `N` — the mapping is
 * persistent across process restarts and never reset, so a client that
 * appears as `Unknown (#3)` in one audit row stays `Unknown (#3)` forever,
 * even after its tokens are revoked and a new client takes `#4`.
 *
 * Implementations are expected to be safe to call from multiple
 * coroutines concurrently. See issue #345.
 */
interface UnknownClientLabeler {

    /**
     * Returns a stable `Unknown (#N)` label for [clientId]. If [clientId]
     * has never been labeled before, atomically allocates the next number
     * (starting at 1), persists the mapping, and returns the new label.
     * Subsequent calls with the same [clientId] return the identical
     * string.
     */
    suspend fun labelFor(clientId: String): String
}
