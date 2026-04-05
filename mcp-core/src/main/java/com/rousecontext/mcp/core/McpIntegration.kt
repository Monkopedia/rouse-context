package com.rousecontext.mcp.core

/**
 * Contract for an MCP integration module.
 *
 * Each integration exposes health, notification, or other device data
 * through MCP tools and resources. The app module discovers integrations
 * and wires them into the MCP session.
 */
interface McpIntegration {

    /** Unique identifier for this integration (e.g. "health"). */
    val id: String

    /** Human-readable name shown in the UI (e.g. "Health Connect"). */
    val displayName: String

    /** Short description of what this integration shares. */
    val description: String

    /** URL path segment used for routing (e.g. "health"). */
    val path: String

    /** The MCP server provider that registers tools/resources. */
    val provider: McpServerProvider

    /** Navigation route for first-time onboarding flow, or null if none needed. */
    val onboardingRoute: String?

    /** Navigation route for integration-specific settings, or null if none. */
    val settingsRoute: String?

    /** Returns true if this integration is available on the current device. */
    suspend fun isAvailable(): Boolean
}
