package com.rousecontext.mcp.tool

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Standard JSON envelope for tool error results. Ensures every error path in
 * the MCP DSL produces valid JSON that the Anthropic connector proxy can parse,
 * matching the shape used by integration-level error helpers (e.g. `outreachError`).
 */
@Serializable
data class ToolErrorResponse(val success: Boolean = false, val error: String) {
    companion object {
        /**
         * Json instance that encodes defaults so the `success` field always
         * appears in the wire output, matching the Outreach/Usage providers.
         */
        internal val json: Json = Json { encodeDefaults = true }
    }
}
