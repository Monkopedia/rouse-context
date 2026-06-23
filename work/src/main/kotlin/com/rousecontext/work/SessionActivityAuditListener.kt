package com.rousecontext.work

import com.rousecontext.mcp.core.AuditListener
import com.rousecontext.mcp.core.McpRequestEvent
import com.rousecontext.mcp.core.TokenGrantEvent
import com.rousecontext.mcp.core.ToolCallEvent

/**
 * [AuditListener] decorator that promotes the current wake cycle to
 * *substantive* whenever a `tools/call` completes, then delegates to the real
 * audit sink (the app's Room-backed listener).
 *
 * This is the seam that feeds [SessionActivityTracker] from the MCP session
 * layer: `onToolCall` fires exactly once per `tools/call` (see
 * [AuditListener.onToolCall]), which is precisely the "substantive" signal
 * [IdleTimeoutManager] needs to decide between the long idle timeout and the
 * short quick-disconnect timeout. Discovery-only methods (`initialize`,
 * `tools/list`, `resources/read`, …) flow through [onRequest] only and leave the
 * tracker untouched.
 */
class SessionActivityAuditListener(
    private val delegate: AuditListener,
    private val tracker: SessionActivityTracker
) : AuditListener {

    override suspend fun onToolCall(event: ToolCallEvent) {
        tracker.recordToolCall()
        delegate.onToolCall(event)
    }

    override fun onTokenGranted(event: TokenGrantEvent) {
        delegate.onTokenGranted(event)
    }

    override suspend fun onRequest(event: McpRequestEvent) {
        delegate.onRequest(event)
    }
}
