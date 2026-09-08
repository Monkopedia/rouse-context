package com.rousecontext.app.support

import com.rousecontext.api.CrashReporter
import com.rousecontext.app.BuildConfig
import com.rousecontext.app.state.AppStatePreferences
import kotlinx.coroutines.flow.Flow

/**
 * The single place that decides whether crash reports may leave the device,
 * and the only thing that writes that decision to [CrashReporter]. Issue #546.
 *
 * ## Why this is not `!BuildConfig.DEBUG`
 *
 * It used to be. The FOSS build shipped ACRA on by default with no opt-out,
 * while the F-Droid listing claimed "no analytics or tracking in the app" — a
 * shipped store listing making a false privacy claim. The owner's ruling on
 * #546 is that collection is **opt-in, and only ever opt-in**: it is never
 * enabled without an explicit user action.
 *
 * ## Why the stored preference has to be read on every launch
 *
 * `RouseApplication.onCreate` re-affirms collection on each start. While that
 * re-affirmation came from `BuildConfig.DEBUG`, a Settings toggle would have
 * appeared to work and then silently reset on the next launch — the trap
 * recorded on #546. [applyToReporter] is that startup call, and it reads the
 * preference, so the toggle and the startup path agree by construction.
 *
 * @param requiresOptIn whether this distribution gates collection on user
 *   consent. True on FOSS (bound in its `DistributionModule`). False on the
 *   google/Play distribution, where Firebase Crashlytics keeps the pre-existing
 *   release-builds-collect behaviour of #233; that build ships no consent UI,
 *   so flipping it here would leave collection off with no way to turn it on.
 * @param isDebugBuild debug builds never phone home regardless of consent, so
 *   local repros do not open spurious issues.
 */
class CrashReportingPreference(
    private val preferences: AppStatePreferences,
    private val crashReporter: CrashReporter,
    private val requiresOptIn: Boolean,
    private val isDebugBuild: Boolean = BuildConfig.DEBUG
) {

    /**
     * Whether this build exposes user-facing crash-reporting consent — the
     * first-run sheet and the Settings › Support switch. False on google, where
     * the row would toggle a preference nothing reads.
     */
    val isUserControlled: Boolean get() = requiresOptIn

    /** Current stored choice, for the Settings switch. */
    fun observeOptIn(): Flow<Boolean> = preferences.observeCrashReportingOptIn()

    /**
     * Whether the first-run consent sheet should be shown. True only on a build
     * that asks for consent and only until the sheet has been exited once, by
     * any route.
     */
    suspend fun shouldAskForConsent(): Boolean =
        requiresOptIn && !preferences.crashReportingConsentAsked()

    /**
     * Record that the consent sheet has been shown. Called on every exit —
     * including "Not now", swipe-dismiss and back — so there is no second ask.
     */
    suspend fun markConsentAsked() {
        preferences.markCrashReportingConsentAsked()
    }

    /**
     * Persist the user's choice and apply it immediately, so turning reporting
     * on covers a crash in this session rather than only after a restart.
     */
    suspend fun setOptIn(enabled: Boolean) {
        preferences.setCrashReportingOptIn(enabled)
        applyToReporter()
    }

    /**
     * Push the stored choice into the reporter. Called from
     * `RouseApplication.onCreate`; this is what makes the preference survive a
     * relaunch instead of being overwritten by the build variant.
     */
    suspend fun applyToReporter() {
        crashReporter.setCollectionEnabled(collectionEnabled(preferences.crashReportingOptIn()))
    }

    private fun collectionEnabled(optedIn: Boolean): Boolean =
        !isDebugBuild && (optedIn || !requiresOptIn)
}
