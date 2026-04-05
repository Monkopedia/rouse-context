package com.rousecontext.notifications.audit

import com.rousecontext.mcp.core.AuditListener
import com.rousecontext.mcp.core.ToolCallEvent

/**
 * [AuditListener] implementation that persists tool call events to Room.
 */
class RoomAuditListener(private val dao: AuditDao) : AuditListener {

    override suspend fun onToolCall(event: ToolCallEvent) {
        dao.insert(
            AuditEntry(
                sessionId = event.sessionId,
                toolName = event.toolName,
                provider = event.provider,
                timestampMillis = event.timestampMillis,
                durationMillis = event.durationMillis,
                success = event.success,
                errorMessage = event.errorMessage,
            ),
        )
    }
}
