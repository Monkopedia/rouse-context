package com.rousecontext.app.ui.screens

import com.rousecontext.app.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The Settings screen renders [SettingsState.versionName] into
 * `screen_settings_version`. Nothing in the app ever assigns that field — the
 * rendered path (`SettingsDestination`) passes the state through with only
 * `showAllMcpMessages` copied — so whatever the default is, is what the user
 * sees.
 *
 * It was the literal "0.1.0" from the first release through v1.0.8, so the
 * Settings screen advertised a version the app has never been. These tests pin
 * the default to the real build version so it cannot silently rot back.
 */
class SettingsVersionTest {

    @Test
    fun `default versionName is the real build version`() {
        assertEquals(
            "SettingsState.versionName must come from BuildConfig, not a literal",
            BuildConfig.VERSION_NAME,
            SettingsState().versionName
        )
    }

    @Test
    fun `default versionName is not the stale 0_1_0 literal`() {
        // Anti-vacuity: `BuildConfig.VERSION_NAME` could itself be "0.1.0" in
        // some future config, which would make the assertion above pass while
        // the bug was back. This fails loudly in that case.
        assertNotEquals("0.1.0", SettingsState().versionName)
    }
}
