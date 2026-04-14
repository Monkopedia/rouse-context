package com.rousecontext.notifications.audit

import com.rousecontext.mcp.core.ToolCallEvent

/**
 * Hook invoked synchronously from [RoomAuditListener.onToolCall] for each
 * recorded tool call. Lets downstream consumers (e.g.
 * [com.rousecontext.notifications.PerToolCallNotifier]) react to individual
 * tool invocations without depending on the DAO or Room types.
 *
 * Kept as a first-class interface — rather than a lambda — so Koin can wire it
 * without type-erasure surprises and so implementations can carry state.
 */
fun interface PerCallObserver {
    fun onToolCallRecorded(event: ToolCallEvent)
}
