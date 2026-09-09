package com.rousecontext.app.ui.screenshots

import com.rousecontext.app.BuildConfig
import com.rousecontext.app.ui.screens.SettingsState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The version string screenshot fixtures render in the Settings About row,
 * instead of the version this build happens to carry.
 *
 * Deliberately not any real version, past or future: a reader looking at
 * `91_settings_crash_reports_off_light.png` should not be able to mistake it
 * for a claim about which release the golden was recorded from.
 */
internal const val SCREENSHOT_VERSION_NAME = "0.0.0-screenshot"

/**
 * Guards the `versionName` pin that keeps Settings goldens stable across
 * releases (issue #628's family; the same shape as the timezone pin in
 * [ScreenshotTimeZonePinTest]).
 *
 * `SettingsState.versionName` defaults to `BuildConfig.VERSION_NAME` and is
 * never assigned anywhere else — production reads the default, so the default
 * cannot move — and `SettingsScreen` renders it through
 * `R.string.screen_settings_version` in the About section. Any golden that
 * scrolls far enough to show that row therefore embeds the version number, and
 * moves on **every** release bump.
 *
 * That is not hypothetical. It fired on v1.0.10, the first bump after the
 * crash-reporting goldens existed: `versionCode 10 -> 11` turned four goldens
 * red for the single reason that "Version 1.0.9" became "Version 1.0.10".
 * Re-recording would have cleared the board and left the trap armed for the
 * next release, with no signal to whoever hit it that it was expected.
 *
 * The affected goldens are measured, not inferred — the same discipline #719
 * imposed on the timezone list after three investigations inherited a
 * plausible-looking list that was wrong. Pinning the version and re-running
 * `:app:verifyRoborazziDebug` moves exactly these four, and no others:
 *
 *  - `app/screenshots/91_settings_crash_reports_off_{light,dark}.png`
 *  - `app/screenshots/92_settings_crash_reports_on_{light,dark}.png`
 *    (all four from `ScreenScreenshotTest.settingsCrashReports*`)
 *
 * The other seven `SettingsState` fixtures in this package are pinned too, and
 * pinning them moved nothing — measured the same way, by pinning all eight and
 * reading which PNGs `recordRoborazziDebug` rewrote:
 *
 *  - `38_settings_no_battery`, `39_settings_trust_verified`,
 *    `40_settings_trust_warning`, `41_settings_trust_alert`,
 *    `42_settings_checks_disabled` (`ScreenScreenshotTest`)
 *  - `84_real_settings_row` (`BackgroundDeliveryScreenshotTest`)
 *  - `fastlane/.../phoneScreenshots/4_settings` (`ListingScreenshotTest`)
 *
 * Their captures stop above the About section — `42` scrolls only as far as
 * the Security "Check interval" row — so the version never reaches their
 * pixels. They are pinned anyway, so that a fixture which later scrolls
 * further is already safe rather than becoming the next release's surprise.
 *
 * To re-measure: revert the `versionName = SCREENSHOT_VERSION_NAME` arguments,
 * change `versionName` in `app/build.gradle.kts`, and run
 * `./gradlew :app:verifyRoborazziDebug`. Count the failing *tests* and divide
 * by three — the task retries each failure twice, so four broken goldens
 * report as twelve failures.
 */
class ScreenshotVersionPinTest {

    /**
     * The pin has to be a value no release will ever carry.
     *
     * This is the assertion that survives the tempting wrong fix. When these
     * goldens next go red, the cheap move is to set the pin to whatever
     * `BuildConfig.VERSION_NAME` now says and re-record — which looks like a
     * pin, passes verification that day, and silently restores the once-per-
     * release breakage. That turns this test red instead.
     */
    @Test
    fun `screenshot version pin is not a real version`() {
        assertNotEquals(
            "SCREENSHOT_VERSION_NAME has been set to the build's own version. The pin " +
                "then tracks every release bump, which is the exact breakage it exists " +
                "to stop. Use a value no release will ever carry.",
            BuildConfig.VERSION_NAME,
            SCREENSHOT_VERSION_NAME
        )
    }

    /**
     * The fixture behind the four goldens that actually render the row.
     *
     * Asserting on the state the test builds — rather than trusting that the
     * argument is still spelled at the call site — is what makes a dropped pin
     * fail here, in a test that names the problem, instead of as an unexplained
     * pixel diff during the next release.
     */
    @Test
    fun `crash-report settings fixture pins the version it renders`() {
        assertEquals(
            "The Settings crash-report screenshot fixture no longer pins versionName, " +
                "so 91_/92_settings_crash_reports_* will move on the next release bump.",
            SCREENSHOT_VERSION_NAME,
            settingsCrashReportingState(enabled = false).versionName
        )
    }

    /**
     * A `SettingsState` built with no `versionName` still reads the live build
     * version. Pinned at the call sites on purpose, not fixed in the default:
     * that default is the only thing that puts a version on the About row in
     * the shipped app, so changing it would blank the real screen to fix a
     * test.
     */
    @Test
    fun `the unpinned default is still the build version`() {
        assertEquals(
            "SettingsState.versionName no longer defaults to BuildConfig.VERSION_NAME. " +
                "Production never assigns versionName, so the About row in the shipped " +
                "app now shows whatever this default became.",
            BuildConfig.VERSION_NAME,
            SettingsState().versionName
        )
    }
}
