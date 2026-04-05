package com.rousecontext.api

import com.rousecontext.mcp.core.McpServerProvider

/**
 * Contract that all integration modules implement to plug into the app.
 *
 * Each integration provides an MCP server (via [provider]), platform availability
 * checks, and navigation routes for its onboarding and settings screens.
 */
interface McpIntegration {

    /** Unique identifier, e.g. "health". */
    val id: String

    /** Human-readable name, e.g. "Health Connect". */
    val displayName: String

    /** Short description for the Add Integration picker. */
    val description: String

    /** URL path prefix, e.g. "/health". */
    val path: String

    /** The MCP server provider for tool/resource registration. */
    val provider: McpServerProvider

    /**
     * Whether the underlying platform is available on this device.
     * For example, Health Connect checks whether the SDK is installed.
     */
    suspend fun isAvailable(): Boolean

    /**
     * Relative route for the onboarding flow, appended to the integration prefix.
     * For example, "setup" becomes "integration/{id}/setup".
     */
    val onboardingRoute: String

    /**
     * Relative route for the settings/detail screen, appended to the integration prefix.
     * For example, "settings" becomes "integration/{id}/settings".
     */
    val settingsRoute: String
}
