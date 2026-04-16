package com.rousecontext.mcp.core

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Identifies the AI client whose HTTP request is currently being dispatched.
 *
 * Populated by [McpRouting.handleMcp] after bearer-token validation, then
 * propagated via [kotlinx.coroutines.CoroutineContext] through the MCP SDK's
 * internal tool dispatch so individual tools can read the calling client's
 * name without adding a parameter to every tool signature.
 *
 * Intended read-path: tools that surface user-facing prompts like the
 * outreach `launch_app` notification ("Claude wants to open Spotify").
 * Absent clientName falls back to a generic "AI client" label.
 */
class McpClientContext(val clientName: String?) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<McpClientContext>
}
