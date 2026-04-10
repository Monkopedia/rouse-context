package com.rousecontext.app.ui.screens

/**
 * Distinguishes between first-time setup and editing settings for an
 * already-enabled integration.
 *
 * In [SETUP] mode the primary button says "Enable" and the flow continues
 * through cert provisioning. In [SETTINGS] mode the button says "Save" and
 * the action persists changes without re-provisioning.
 */
enum class SetupMode {
    SETUP,
    SETTINGS
}
