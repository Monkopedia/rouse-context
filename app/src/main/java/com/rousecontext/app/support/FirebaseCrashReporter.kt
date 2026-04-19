package com.rousecontext.app.support

import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.rousecontext.api.CrashReporter

/**
 * [CrashReporter] backed by Firebase Crashlytics.
 *
 * The underlying [FirebaseCrashlytics] singleton is resolved lazily so tests
 * that instantiate Koin without a running Firebase app don't trip over
 * initialization, and so `:app` code paths that don't actually crash never pay
 * the cost of loading the native Crashlytics runtime.
 *
 * Collection enabled/disabled state is persisted by Crashlytics itself across
 * process restarts (see `setCrashlyticsCollectionEnabled` docs), so this class
 * just forwards the current runtime preference — it doesn't need to read the
 * value back on startup.
 */
class FirebaseCrashReporter(
    private val crashlytics: () -> FirebaseCrashlytics = { FirebaseCrashlytics.getInstance() }
) : CrashReporter {

    override fun logCaughtException(throwable: Throwable) {
        crashlytics().recordException(throwable)
    }

    override fun log(message: String) {
        crashlytics().log(message)
    }

    override fun setCollectionEnabled(enabled: Boolean) {
        crashlytics().setCrashlyticsCollectionEnabled(enabled)
    }
}
