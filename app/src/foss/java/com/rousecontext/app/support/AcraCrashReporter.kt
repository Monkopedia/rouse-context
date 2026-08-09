package com.rousecontext.app.support

import com.rousecontext.api.CrashReporter
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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
 *
 * ## Why [logCaughtException] dispatches off the caller's thread (issue #542)
 *
 * ACRA's `handleSilentException` is **blocking**: `CrashReportDataFactory.collect`
 * fans its collectors into a thread pool and then blocks the calling thread on
 * `FutureTask.get` until collection finishes or times out. Callers frequently
 * report from `lifecycleScope` (`Dispatchers.Main.immediate`) — e.g.
 * `TunnelForegroundService.collectIncomingSessions` catching a stream error —
 * which froze the main thread for ~60s and produced a background ANR on the
 * UnifiedPush wake broadcast. Silent (non-fatal) reports have no reason to
 * block, so collection is dispatched onto [ioDispatcher] and fire-and-forget.
 * Doing it here rather than at each call site protects every caller.
 *
 * The `google` flavor is unaffected — Crashlytics' `recordException` is already
 * asynchronous.
 *
 * @param scope application-lifetime scope the fire-and-forget report runs in.
 *   Structured rather than `GlobalScope` so reports are cancelled with the app.
 * @param ioDispatcher dispatcher ACRA's blocking collection runs on.
 * @param reportSilently seam for the blocking ACRA call, so the off-main
 *   behaviour is testable without initializing ACRA.
 */
class AcraCrashReporter(
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val reportSilently: (Throwable) -> Unit = {
        ACRA.errorReporter.handleSilentException(it)
    }
) : CrashReporter {

    /** Monotonic counter so breadcrumb custom-data keys never collide. */
    private val breadcrumbSeq = AtomicLong(0)

    override fun logCaughtException(throwable: Throwable) {
        // Non-fatal: report without killing the process, mirroring
        // Crashlytics.recordException. Dispatched off the caller's thread
        // because ACRA's collection blocks — see the class kdoc (#542).
        scope.launch(ioDispatcher) {
            // Swallow failures from the reporter itself. Before #542 a throw
            // here surfaced synchronously at the call site, and the call sites
            // are catch blocks (TunnelForegroundService.collectIncomingSessions
            // is `catch (e) { crashReporter.logCaughtException(e) }`). Dispatched
            // into `scope` — a SupervisorJob — an uncaught throw is NOT
            // propagated to a parent; it reaches the thread's default uncaught
            // handler and kills the process. That would make the crash reporter
            // the thing that turns a handled error into a fatal one.
            runCatching { reportSilently(throwable) }
        }
    }

    override fun log(message: String) {
        // Attach as custom data so it rides along with the next crash report,
        // the closest ACRA analogue to a Crashlytics breadcrumb. Keys are
        // sequenced so successive breadcrumbs don't overwrite each other.
        // putCustomData is an in-memory map write, not a collecting call, so
        // it stays on the caller's thread.
        val key = "breadcrumb_${breadcrumbSeq.incrementAndGet()}"
        ACRA.errorReporter.putCustomData(key, message)
    }

    override fun setCollectionEnabled(enabled: Boolean) {
        ACRA.errorReporter.setEnabled(enabled)
    }
}
