package com.rousecontext.api

/**
 * Derived state of an integration, computed from user preferences, platform
 * availability, and token status.
 */
enum class IntegrationState {
    /** Not yet set up, platform available. Shows in Add picker. */
    Available,

    /** Previously set up but user-disabled. Shows in Add picker for re-enable. */
    Disabled,

    /** User-enabled, platform available, but no client has authorized yet. */
    Pending,

    /** User-enabled, platform available, at least one client authorized. */
    Active,

    /** Platform not available (e.g. Health Connect not installed). */
    Unavailable
}

/**
 * Derives the [IntegrationState] from the given inputs.
 *
 * @param userEnabled whether the user has toggled this integration on
 * @param wasEverEnabled whether the user has ever enabled this integration
 * @param isAvailable whether the underlying platform is present
 * @param hasTokens whether at least one client token exists for this integration
 */
fun deriveIntegrationState(
    userEnabled: Boolean,
    wasEverEnabled: Boolean,
    isAvailable: Boolean,
    hasTokens: Boolean
): IntegrationState = when {
    !isAvailable -> IntegrationState.Unavailable
    !userEnabled && wasEverEnabled -> IntegrationState.Disabled
    !userEnabled -> IntegrationState.Available
    hasTokens -> IntegrationState.Active
    else -> IntegrationState.Pending
}
