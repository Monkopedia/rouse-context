package com.rousecontext.app.support

import com.rousecontext.api.CrashReporter
import java.util.concurrent.atomic.AtomicLong
import org.acra.ACRA

/**
 * [CrashReporter] backed by ACRA (the FOSS Crashlytics replacement), used by
 * the `foss` flavor. The parallel `google` flavor binds
 * [com.rousecontext.app.support.FirebaseCrashReporter]; both sit behind the
 * same Koin seam. Issue #464.
 *
 * ACRA is initialized in [CrashReporterInitializer] from
 * `Application.attachBaseContext`. Before that runs (or if init is skipped,
 * e.g. in the ACRA sender process), [ACRA.errorReporter] returns a safe no-op
 * stub, so every method here degrades gracefully rather than throwing.
 */
class AcraCrashReporter : CrashReporter {

    /** Monotonic counter so breadcrumb custom-data keys never collide. */
    private val breadcrumbSeq = AtomicLong(0)

    override fun logCaughtException(throwable: Throwable) {
        // Non-fatal: report without killing the process, mirroring
        // Crashlytics.recordException.
        ACRA.errorReporter.handleSilentException(throwable)
    }

    override fun log(message: String) {
        // Attach as custom data so it rides along with the next crash report,
        // the closest ACRA analogue to a Crashlytics breadcrumb. Keys are
        // sequenced so successive breadcrumbs don't overwrite each other.
        val key = "breadcrumb_${breadcrumbSeq.incrementAndGet()}"
        ACRA.errorReporter.putCustomData(key, message)
    }

    override fun setCollectionEnabled(enabled: Boolean) {
        ACRA.errorReporter.setEnabled(enabled)
    }
}
