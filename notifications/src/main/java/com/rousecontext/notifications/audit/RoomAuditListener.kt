package com.rousecontext.notifications.audit

import com.rousecontext.mcp.core.AuditListener
import com.rousecontext.mcp.core.McpRequestEvent
import com.rousecontext.mcp.core.ToolCallEvent
import com.rousecontext.notifications.FieldEncryptor
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * [AuditListener] implementation that persists tool call events to Room.
 * Sensitive fields (errorMessage, tool-call arguments/results, and
 * request params) are encrypted at rest when a [FieldEncryptor] is
 * provided.
 *
 * When a [perCallObserver] is supplied, it is invoked for every tool call
 * after the DB insert has committed. This drives the per-tool-call
 * notification path
 * ([com.rousecontext.notifications.PerToolCallNotifier]) for
 * [com.rousecontext.api.PostSessionMode.EACH_USAGE].
 *
 * When [mcpRequestDao] is supplied (see issue #105), every MCP JSON-RPC
 * request is also persisted to a parallel `mcp_request_entries` table so
 * the user can opt in to a complete message log from Settings.
 *
 * ## Why suspend/inline inserts (issue #244)
 *
 * Inserts run on the caller's coroutine context (no internal `launch`).
 * Once [onToolCall] or [onRequest] returns, the row is committed. The
 * post-session summary notifier queries the audit DAO on the
 * ACTIVE -> CONNECTED stream-drain transition, which would otherwise race
 * a fire-and-forget insert and lose on fast hardware.
 */
class RoomAuditListener(
    private val dao: AuditDao,
    private val fieldEncryptor: FieldEncryptor? = null,
    private val perCallObserver: PerCallObserver? = null,
    private val mcpRequestDao: McpRequestDao? = null
) : AuditListener {

    override suspend fun onToolCall(event: ToolCallEvent) {
        val argsJson = JsonObject(event.arguments).toString()
        val resultJson = auditJson.encodeToString(CallToolResult.serializer(), event.result)
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
                resultJson = fieldEncryptor?.encrypt(resultJson) ?: resultJson,
                clientLabel = event.clientLabel
            )
        )
        perCallObserver?.onToolCallRecorded(event)
    }

    override suspend fun onRequest(event: McpRequestEvent) {
        val requestDao = mcpRequestDao ?: return
        val paramsJson = event.params?.toString()
        requestDao.insert(
            McpRequestEntry(
                sessionId = event.sessionId,
                provider = event.providerId,
                method = event.method,
                timestampMillis = event.timestamp,
                durationMillis = event.durationMs,
                resultBytes = event.resultBytes,
                paramsJson = fieldEncryptor?.encrypt(paramsJson) ?: paramsJson
            )
        )
    }

    private companion object {
        /**
         * Matches the MCP SDK wire format: emits the `isError` flag so the
         * stored JSON is self-describing, and drops nulls to keep the payload
         * compact.
         */
        private val auditJson = Json {
            encodeDefaults = true
            explicitNulls = false
        }
    }
}
