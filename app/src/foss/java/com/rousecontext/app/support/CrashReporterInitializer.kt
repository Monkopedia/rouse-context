package com.rousecontext.app.support

import android.app.Application
import com.rousecontext.app.BuildConfig
import org.acra.ACRA
import org.acra.config.CoreConfigurationBuilder
import org.acra.config.HttpSenderConfigurationBuilder
import org.acra.data.StringFormat
import org.acra.sender.HttpSender

/**
 * `foss`-flavor crash-reporting initializer (issue #464).
 *
 * Initializes ACRA, the FOSS replacement for Firebase Crashlytics. ACRA hooks
 * the process-wide uncaught-exception handler and forks a dedicated sender
 * process, so it MUST be initialized from [Application.attachBaseContext] (the
 * shared [com.rousecontext.app.RouseApplication] calls this).
 *
 * Reports are POSTed as JSON to the relay's `POST /crash` endpoint, where they
 * are sanitized, deduped, and turned into GitHub issues (mirroring the
 * Crashlytics→issue convention). The endpoint URL derives from the same
 * `BuildConfig` relay host the tunnel uses, over HTTPS.
 *
 * Collection is gated to release builds here (debug builds never phone home, so
 * local repros don't open spurious issues). The runtime toggle is also exposed
 * via [AcraCrashReporter.setCollectionEnabled], wired through the shared
 * `RouseApplication.configureCrashReporting` path so a future Settings opt-out
 * can flip it at runtime.
 */
object CrashReporterInitializer {
    fun initialize(application: Application) {
        if (ACRA.isInitialised) return

        val builder = CoreConfigurationBuilder()
            .withBuildConfigClass(BuildConfig::class.java)
            .withReportFormat(StringFormat.JSON)
            // Don't collect logcat: it can contain data from other apps and
            // PII. The relay also sanitizes, but minimizing at the source is
            // cheaper and safer. Stack trace + app/OS version is enough to
            // triage a crash into a GitHub issue.
            .withLogcatArguments(emptyList())
            .withPluginConfigurations(
                HttpSenderConfigurationBuilder()
                    .withUri(crashReportUri())
                    .withHttpMethod(HttpSender.Method.POST)
                    .build()
            )

        ACRA.init(application, builder)

        // Mirror the google flavor's debug/release gate (Crashlytics is
        // collection-disabled in debug). Release builds collect by default
        // until a user opts out. The shared configureCrashReporting() hook
        // reaffirms this shortly after onCreate.
        ACRA.errorReporter.setEnabled(!BuildConfig.DEBUG)
    }

    /**
     * Build the relay crash-ingest URL from the same relay host the tunnel
     * uses. Always HTTPS; includes an explicit port only when it isn't the
     * standard 443 (e.g. a self-hosting fork or a dev relay).
     */
    private fun crashReportUri(): String {
        val host = BuildConfig.RELAY_HOST
        val port = BuildConfig.RELAY_PORT
        val authority = if (port == 443) host else "$host:$port"
        return "https://$authority/crash"
    }
}
