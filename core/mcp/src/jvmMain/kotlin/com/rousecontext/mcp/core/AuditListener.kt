package com.rousecontext.mcp.core

import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import kotlinx.serialization.json.JsonElement

/**
 * Callback for auditing MCP tool invocations and token grants.
 * Implemented by :app to persist a per-tool-call history log.
 */
interface AuditListener {
    /**
     * Called for every `tools/call` completion. Suspending so implementations
     * can persist the event before returning — the post-session summary
     * notifier queries the audit DAO on stream drain and races any
     * fire-and-forget insert that returns early. See issue #244.
     */
    suspend fun onToolCall(event: ToolCallEvent)

    /**
     * Called when a client completes OAuth and receives an access token.
     * Default no-op so existing implementations don't break.
     */
    fun onTokenGranted(event: TokenGrantEvent) {}

    /**
     * Called for every MCP JSON-RPC method (initialize, tools/list, tools/call,
     * resources/list, resources/read, ping, notifications/initialized, etc.).
     *
     * For `tools/call`, both [onRequest] and [onToolCall] fire so the existing
     * tool-call UI stays intact while the broader message log receives every
     * request.
     *
     * Suspending for the same reason as [onToolCall]: implementations must be
     * able to persist synchronously from the caller's perspective so the
     * summary notifier doesn't race an in-flight insert (#244).
     *
     * Default no-op so existing implementations don't break.
     */
    suspend fun onRequest(event: McpRequestEvent) {}
}

data class ToolCallEvent(
    val sessionId: String,
    val providerId: String,
    val timestamp: Long,
    val toolName: String,
    val arguments: Map<String, JsonElement>,
    val result: CallToolResult,
    val durationMs: Long
)

/**
 * Audit event emitted when an OAuth token is granted to a client.
 */
data class TokenGrantEvent(
    val timestamp: Long,
    val integration: String,
    val clientId: String,
    val clientName: String?,
    val grantType: String
)

/**
 * Audit event emitted for every MCP JSON-RPC request. Captures the method
 * name, raw params (opaque, may contain user data for `prompts/get` /
 * `resources/read` -- downstream persistence is expected to encrypt at rest),
 * approximate result size, and wall-clock duration.
 *
 * @property resultBytes serialized JSON length of the response for requests,
 *   or `null` for notifications (no response), parse failures, and errors.
 */
data class McpRequestEvent(
    val sessionId: String,
    val providerId: String,
    val timestamp: Long,
    val method: String,
    val params: JsonElement?,
    val resultBytes: Int?,
    val durationMs: Long
)
