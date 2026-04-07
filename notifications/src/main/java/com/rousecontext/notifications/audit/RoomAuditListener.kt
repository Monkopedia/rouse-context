package com.rousecontext.notifications.audit

import com.rousecontext.mcp.core.AuditListener
import com.rousecontext.mcp.core.ToolCallEvent
import com.rousecontext.notifications.capture.FieldEncryptor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

/**
 * [AuditListener] implementation that persists tool call events to Room.
 * Sensitive fields (errorMessage) are encrypted at rest when a [FieldEncryptor]
 * is provided.
 */
class RoomAuditListener(
    private val dao: AuditDao,
    private val scope: CoroutineScope,
    private val fieldEncryptor: FieldEncryptor? = null
) : AuditListener {

    override fun onToolCall(event: ToolCallEvent) {
        scope.launch {
            val argsJson = JsonObject(event.arguments).toString()
            val resultJson = event.result.content
                .joinToString("\n") { it.toString() }
            dao.insert(
                AuditEntry(
                    sessionId = event.sessionId,
                    toolName = event.toolName,
                    provider = event.providerId,
                    timestampMillis = event.timestamp,
                    durationMillis = event.durationMs,
                    success = true,
                    errorMessage = fieldEncryptor?.encrypt(null),
                    argumentsJson = fieldEncryptor?.encrypt(argsJson) ?: argsJson,
                    resultJson = fieldEncryptor?.encrypt(resultJson) ?: resultJson
                )
            )
        }
    }
}
