package com.rousecontext.notifications.audit

import com.rousecontext.mcp.core.AuditListener
import com.rousecontext.mcp.core.ToolCallEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * [AuditListener] implementation that persists tool call events to Room.
 */
class RoomAuditListener(
    private val dao: AuditDao,
    private val scope: CoroutineScope
) : AuditListener {

    override fun onToolCall(event: ToolCallEvent) {
        scope.launch {
            dao.insert(
                AuditEntry(
                    sessionId = event.sessionId,
                    toolName = event.toolName,
                    provider = event.providerId,
                    timestampMillis = event.timestamp,
                    durationMillis = event.durationMs,
                    success = true,
                    errorMessage = null
                )
            )
        }
    }
}
