package com.rousecontext.api

/**
 * Thin abstraction over the crash-reporting backend so feature modules can
 * report caught exceptions without pulling in Firebase directly. The
 * production implementation wraps Firebase Crashlytics; tests inject a fake.
 *
 * Uncaught exceptions are already reported by Crashlytics' default handler,
 * so only use this interface for exceptions the code currently swallows or
 * degrades into a recoverable state — that way we keep Crashlytics signal
 * focused on failures that actually harm the user.
 */
interface CrashReporter {

    /**
     * Record a caught exception. Non-fatal reports appear in the Crashlytics
     * console alongside uncaught crashes but don't crash the app.
     */
    fun logCaughtException(throwable: Throwable)

    /**
     * Attach a breadcrumb message to future crash reports. Useful for tracing
     * which step inside a multi-phase flow failed.
     */
    fun log(message: String)

    /**
     * Enable or disable collection at runtime. Wired to a Settings toggle so
     * users can opt out without reinstalling.
     */
    fun setCollectionEnabled(enabled: Boolean)

    companion object {
        /**
         * Shared no-op used when crash reporting is unavailable (unit tests,
         * modules that haven't been wired yet). Production code should always
         * resolve a real [CrashReporter] via Koin.
         */
        val NoOp: CrashReporter = object : CrashReporter {
            override fun logCaughtException(throwable: Throwable) = Unit
            override fun log(message: String) = Unit
            override fun setCollectionEnabled(enabled: Boolean) = Unit
        }
    }
}
