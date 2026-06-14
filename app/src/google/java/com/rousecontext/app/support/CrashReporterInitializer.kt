package com.rousecontext.app.support

import android.app.Application

/**
 * `google`-flavor crash-reporting initializer (issue #464).
 *
 * Firebase Crashlytics self-initializes via the `firebase-crashlytics` Gradle
 * plugin's ContentProvider (`CrashlyticsInitProvider`) before
 * [Application.onCreate], so there is nothing to wire up in
 * [Application.attachBaseContext]. This no-op exists purely so the shared
 * [com.rousecontext.app.RouseApplication.attachBaseContext] can call a
 * flavor-agnostic entry point; the `foss` source set provides an ACRA-backed
 * counterpart.
 */
object CrashReporterInitializer {
    // `application` is unused in the google flavor — Crashlytics self-initializes
    // via its plugin ContentProvider — but the parameter is part of the shared,
    // flavor-agnostic entry point the foss flavor uses to call ACRA.init().
    @Suppress("UnusedParameter")
    fun initialize(application: Application) {
        // No-op: Crashlytics initializes itself via its plugin ContentProvider.
    }
}
