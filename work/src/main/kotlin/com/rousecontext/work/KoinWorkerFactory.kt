package com.rousecontext.work

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import org.koin.core.Koin
import org.koin.core.qualifier.named

/**
 * WorkerFactory that constructs our workers and supplies their Koin-injected collaborators.
 *
 * WorkManager's default factory only invokes the no-arg `(Context, WorkerParameters)` constructor,
 * which leaves `lateinit` fields on [SecurityCheckWorker] uninitialized. This factory dispatches on
 * the class name and uses the root [Koin] instance to populate those fields so the worker's
 * `doWork()` doesn't throw `UninitializedPropertyAccessException` on first run.
 *
 * For workers that self-inject via [org.koin.core.component.KoinComponent] (e.g.
 * [CertRenewalWorker]), this factory simply constructs the worker and returns it — the worker
 * resolves its own dependencies lazily on first access.
 *
 * Wire this factory from `Configuration.Provider.workManagerConfiguration` in the `Application`.
 */
class KoinWorkerFactory(private val koin: Koin) : WorkerFactory() {

    override fun createWorker(
        appContext: Context,
        workerClassName: String,
        workerParameters: WorkerParameters
    ): ListenableWorker? = when (workerClassName) {
        SecurityCheckWorker::class.java.name ->
            SecurityCheckWorker(appContext, workerParameters).apply {
                selfCertVerifier = koin.get(named("selfCert"))
                ctLogMonitor = koin.get(named("ctLog"))
            }
        CertRenewalWorker::class.java.name ->
            // CertRenewalWorker is a KoinComponent and self-injects its collaborators.
            // Constructing it here (instead of falling through to the default factory) keeps
            // the app's worker set explicit and lets us validate worker wiring in one place.
            CertRenewalWorker(appContext, workerParameters)
        else -> null
    }
}
