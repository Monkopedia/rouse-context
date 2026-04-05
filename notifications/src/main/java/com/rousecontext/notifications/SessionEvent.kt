package com.rousecontext.notifications

import com.rousecontext.mcp.core.ToolCallEvent

/**
 * Events that the notification state machine responds to.
 * Maps tunnel lifecycle + MCP tool calls + system events into a unified stream.
 */
sealed interface SessionEvent {
    data object MuxConnected : SessionEvent
    data class MuxDisconnected(val toolCallCount: Int) : SessionEvent
    data class StreamOpened(val streamCount: Int) : SessionEvent
    data class StreamClosed(val streamCount: Int) : SessionEvent

    data class ErrorOccurred(
        val message: String,
        val streamId: Int? = null
    ) : SessionEvent

    data class ToolCallCompleted(val event: ToolCallEvent) : SessionEvent

    data object CertRenewalStarted : SessionEvent
    data class CertRenewalFailed(val message: String) : SessionEvent
    data object CertExpired : SessionEvent

    data class RateLimited(val retryAfterMillis: Long) : SessionEvent
    data class SecurityAlert(val message: String) : SessionEvent
    data class PermissionChanged(val granted: Boolean) : SessionEvent
}
