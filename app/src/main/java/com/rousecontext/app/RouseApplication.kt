package com.rousecontext.app

import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.work.Configuration
import com.rousecontext.app.debug.debugModules
import com.rousecontext.app.di.appModule
import com.rousecontext.app.di.distributionModule
import com.rousecontext.app.state.AppStatePreferences
import com.rousecontext.app.support.CrashReporterInitializer
import com.rousecontext.app.support.CrashReportingPreference
import com.rousecontext.notifications.NotificationChannels
import com.rousecontext.tunnel.CertificateStore
import com.rousecontext.work.CertRenewalScheduler
import com.rousecontext.work.KoinWorkerFactory
import com.rousecontext.work.SecurityCheckScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.core.qualifier.named
import org.koin.dsl.module

/**
 * Application subclass that initializes Koin DI.
 */
class RouseApplication :
    Application(),
    Configuration.Provider {

    /**
     * Application-scoped coroutine scope for work that must outlive any single
     * ViewModel or composable (onboarding, cert-renewal scheduling, state reads).
     *
     * Nothing cancels it. This class overrides only [attachBaseContext] and
     * [onCreate] — there is no `onTerminate` (which Android does not call on real
     * devices anyway) and no `appScope.cancel()` anywhere in `main`. Process death
     * is the only teardown. Test sources inject their own scope and cancel that.
     */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /**
     * WorkManager configuration that supplies [KoinWorkerFactory] so workers with
     * `lateinit` or `KoinComponent`-injected collaborators resolve them at creation time.
     *
     * The default initializer is disabled in the manifest (`AndroidManifest.xml`), so
     * WorkManager calls back into this provider the first time it's requested.
     */
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(KoinWorkerFactory(GlobalContext.get()))
            .build()

    /**
     * Crash-reporting backends that hook the process-wide uncaught-exception
     * handler MUST install before any other init runs, and ACRA specifically
     * requires [attachBaseContext] (it inspects the base context and forks a
     * dedicated sender process). [CrashReporterInitializer] is flavor-specific:
     * the `foss` source set initializes ACRA here; the `google` source set is a
     * no-op (Crashlytics self-initializes via its Gradle-plugin ContentProvider).
     * Issue #464.
     */
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        CrashReporterInitializer.initialize(this)
    }

    override fun onCreate() {
        super.onCreate()

        // Stop any existing Koin instance (Robolectric may recreate Application)
        if (GlobalContext.getOrNull() != null) {
            stopKoin()
        }

        startKoin {
            androidContext(this@RouseApplication)
            modules(
                scopeModule(),
                appModule,
                distributionModule,
                *debugModules().toTypedArray()
            )
        }

        // Notification channels MUST be created before any foreground service
        // calls startForeground(). On cold-start FCM wakes, the service's
        // onCreate fires on the next main-looper message after this returns,
        // so the channel must exist by then. Issue #325.
        NotificationChannels.createAll(this)

        // Defer non-critical init to a posted message so that cold-start FCM
        // wakes reach TunnelForegroundService.startForeground() as fast as
        // possible. The 5s FGS timer starts when startForegroundService() is
        // called; Application.onCreate runs BEFORE the service's onCreate, so
        // every millisecond saved here reduces the risk of
        // ForegroundServiceDidNotStartInTimeException. Issue #325.
        Handler(Looper.getMainLooper()).post {
            appScope.launch { configureCrashReporting() }
            scheduleSecurityChecks()
            CertRenewalScheduler.enqueuePeriodic(this)
            enqueueImmediateCertRenewalIfNeeded()
        }
    }

    /**
     * Fire-and-forget app-start hook that forces an immediate cert renewal
     * when the stored cert is near-expiry or already expired (issue #289).
     *
     * Without this, the periodic worker's 24h interval means a user opening
     * the app with an expired cert would sit stuck until the next periodic
     * tick — the TLS tunnel handshake would fail well before that, with no
     * in-band recovery path.
     *
     * Runs on [appScope] so [onCreate] stays non-blocking; Koin resolution
     * for [CertificateStore] also happens here rather than in the static
     * scheduler to keep the scheduler reusable from tests that don't run
     * the full DI graph.
     */
    private fun enqueueImmediateCertRenewalIfNeeded() {
        appScope.launch {
            val certStore: CertificateStore = GlobalContext.get().get()
            CertRenewalScheduler.enqueueImmediateIfExpiring(this@RouseApplication, certStore)
        }
    }

    /**
     * Re-affirm crash-reporting collection from the STORED user preference on
     * every launch (issues #233, #546).
     *
     * This used to read `BuildConfig.DEBUG` alone, which made collection a pure
     * function of the build variant and would have overwritten a Settings
     * toggle on the next start. [CrashReportingPreference] owns the decision
     * now; this hook only supplies the startup moment.
     *
     * Suspending because the preference lives in DataStore, so it runs on
     * [appScope] rather than blocking the posted-message path above.
     */
    internal suspend fun configureCrashReporting(
        crashReportingPreference: CrashReportingPreference = GlobalContext.get().get()
    ) {
        crashReportingPreference.applyToReporter()
    }

    /**
     * Enqueue the periodic security-check worker. Interval is read from
     * [AppStatePreferences]; we launch into [appScope] because the read is
     * suspending and onCreate must not block.
     */
    private fun scheduleSecurityChecks() {
        appScope.launch {
            val appState = AppStatePreferences(this@RouseApplication)
            val intervalHours = appState.securityCheckIntervalHours()
            val flexHours = (intervalHours / 4).coerceAtLeast(1)
            SecurityCheckScheduler.enqueuePeriodic(
                this@RouseApplication,
                intervalHours = intervalHours,
                flexHours = flexHours
            )
        }
    }

    private fun scopeModule() = module {
        single(named("appScope")) { appScope }
    }
}
