package com.rousecontext.work

import com.rousecontext.mcp.core.McpSession
import com.rousecontext.tunnel.TunnelClient
import kotlinx.coroutines.withTimeoutOrNull

/**
 * How long to wait for graceful MCP session shutdown before forcing tunnel
 * disconnect. Single source of truth for the #446 teardown contract.
 */
const val GRACEFUL_SHUTDOWN_TIMEOUT_MS = 5_000L

/**
 * Tears down the tunnel gracefully per the #446 contract: close MCP sessions
 * cleanly (bounded by [GRACEFUL_SHUTDOWN_TIMEOUT_MS] so a wedged transport
 * can't block teardown forever), then drop the tunnel.
 *
 * This is the single shared seam called from both the
 * [TunnelForegroundService] `onDestroy` path and the `IdleTimeoutManager`
 * `onTimeout` lambda (wired in the :app Koin module). It deliberately does NOT
 * introduce its own coroutine scope — callers invoke it from their own scope.
 */
suspend fun gracefulTunnelShutdown(mcpSession: McpSession, tunnelClient: TunnelClient) {
    withTimeoutOrNull(GRACEFUL_SHUTDOWN_TIMEOUT_MS) { mcpSession.shutdown() }
    tunnelClient.disconnect()
}
