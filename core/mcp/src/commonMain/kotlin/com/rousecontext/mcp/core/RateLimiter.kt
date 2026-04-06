package com.rousecontext.mcp.core

import java.util.concurrent.ConcurrentHashMap

/**
 * Simple global rate limiter using a sliding window per endpoint key.
 *
 * Not per-IP because requests arrive through the relay, so all clients share the
 * relay's IP. Instead, this provides global per-endpoint rate limiting.
 *
 * @param maxRequests Maximum number of requests allowed per window.
 * @param windowMs Duration of the sliding window in milliseconds.
 * @param clock Clock for testability.
 */
class RateLimiter(
    private val maxRequests: Int,
    private val windowMs: Long,
    private val clock: Clock = SystemClock
) {

    private data class Window(
        var startMs: Long,
        var count: Int
    )

    private val windows = ConcurrentHashMap<String, Window>()

    /**
     * Attempts to acquire a request slot for the given endpoint key.
     * Returns true if the request is allowed, false if rate limited.
     */
    fun tryAcquire(endpoint: String): Boolean {
        val now = clock.currentTimeMillis()
        val window = windows.compute(endpoint) { _, existing ->
            if (existing == null || (now - existing.startMs) >= windowMs) {
                Window(startMs = now, count = 1)
            } else {
                existing.count++
                existing
            }
        }!!
        return window.count <= maxRequests
    }
}
