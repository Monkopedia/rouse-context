package com.rousecontext.app.support

import android.app.Application
import com.rousecontext.app.BuildConfig
import org.acra.ACRA
import org.acra.ACRAConstants
import org.acra.ReportField
import org.acra.config.CoreConfiguration
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

        ACRA.init(application, buildConfiguration())

        // Mirror the google flavor's debug/release gate (Crashlytics is
        // collection-disabled in debug). Release builds collect by default
        // until a user opts out. The shared configureCrashReporting() hook
        // reaffirms this shortly after onCreate.
        ACRA.errorReporter.setEnabled(!BuildConfig.DEBUG)
    }

    /**
     * The effective ACRA configuration, split out from [initialize] so the
     * report content is assertable from a unit test (see
     * `CrashReporterInitializerTest`).
     *
     * ## Why [ReportField.LOGCAT] is dropped from the report content (issue #583)
     *
     * Logcat can contain data from other apps and PII. The relay also
     * sanitizes, but minimizing at the source is cheaper and safer — a stack
     * trace plus app/OS version is enough to triage a crash into a GitHub
     * issue.
     *
     * This used to be expressed as `withLogcatArguments(emptyList())`, which
     * did **not** disable collection: `LOGCAT` stayed in the report content,
     * so `LogCatCollector` still ran, and emptying the arguments removed the
     * only flag that made the subprocess terminate. ACRA looks up `-t` in
     * `logcatArguments`; with an empty list that lookup misses, the tail count
     * is skipped, and the command degrades to bare `logcat`, which streams
     * forever. The collector then blocked on a read that never saw EOF while
     * the report thread waited on a no-timeout `FutureTask.get()` — an ANR
     * loop on device (~65s apart) before #542 moved reporting off the main
     * thread, and a permanently parked coroutine plus an orphaned `logcat`
     * child after it.
     *
     * Collection is gated on `config.reportContent.contains(field)`
     * (`BaseReportFieldCollector.shouldCollect`), so dropping the field is
     * what actually stops the collector. If logcat is ever wanted back it MUST
     * carry `-d` or `-t <n>` so the subprocess exits.
     */
    internal fun buildConfiguration(): CoreConfiguration = CoreConfigurationBuilder()
        .withBuildConfigClass(BuildConfig::class.java)
        .withReportFormat(StringFormat.JSON)
        .withReportContent(ACRAConstants.DEFAULT_REPORT_FIELDS - ReportField.LOGCAT)
        .withPluginConfigurations(
            HttpSenderConfigurationBuilder()
                .withUri(crashReportUri())
                .withHttpMethod(HttpSender.Method.POST)
                .build()
        )
        .build()

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
