package com.rousecontext.work

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Tracks whether the current wake cycle has been *substantive* — i.e. whether
 * any MCP `tools/call` has been issued since the last reset.
 *
 * This is the signal [IdleTimeoutManager] uses to pick between the long
 * post-session "Idle timeout" and the short "Quick disconnect" timeout when it
 * arms the idle timer on entering CONNECTED. A wake that only performs discovery
 * (`initialize`, `ping`, `tools/list`, `resources/read`, `prompts/get`) or never
 * opens a stream at all is *not* substantive and should be torn down quickly so
 * it does not burn the Android 15 dataSync foreground-service budget (6h/24h).
 *
 * Lifecycle:
 *  - The session layer calls [recordToolCall] whenever a `tools/call` completes
 *    (wired through the [com.rousecontext.mcp.core.AuditListener]).
 *  - [IdleTimeoutManager] reads [isSubstantive] when arming the idle timer and
 *    calls [reset] when the wake cycle ends (DISCONNECTED).
 *
 * A single instance is shared (process-singleton) between the session layer and
 * the idle-timeout manager, so it is thread-safe via [AtomicBoolean].
 */
class SessionActivityTracker {

    private val substantive = AtomicBoolean(false)

    /** Promotes the current wake cycle to substantive. Idempotent. */
    fun recordToolCall() {
        substantive.set(true)
    }

    /** True if a `tools/call` has been recorded since the last [reset]. */
    fun isSubstantive(): Boolean = substantive.get()

    /** Clears the substantive flag for the next wake cycle. */
    fun reset() {
        substantive.set(false)
    }
}
