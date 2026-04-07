package com.rousecontext.mcp.core

import io.modelcontextprotocol.kotlin.sdk.CallToolResult
import kotlinx.serialization.json.JsonElement

/**
 * Callback for auditing MCP tool invocations and token grants.
 * Implemented by :app to persist a per-tool-call history log.
 */
interface AuditListener {
    fun onToolCall(event: ToolCallEvent)

    /**
     * Called when a client completes OAuth and receives an access token.
     * Default no-op so existing implementations don't break.
     */
    fun onTokenGranted(event: TokenGrantEvent) {}
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
