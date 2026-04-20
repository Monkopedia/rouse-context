package com.rousecontext.app

import android.app.Application
import android.os.Handler
import android.os.Looper
import androidx.work.Configuration
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.rousecontext.api.CrashReporter
import com.rousecontext.app.debug.debugModules
import com.rousecontext.app.di.appModule
import com.rousecontext.app.state.AppStatePreferences
import com.rousecontext.notifications.NotificationChannels
import com.rousecontext.tunnel.CertificateStore
import com.rousecontext.work.CertRenewalScheduler
import com.rousecontext.work.KoinWorkerFactory
import com.rousecontext.work.SecurityCheckWorker
import java.util.concurrent.TimeUnit
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

    /** Application-scoped coroutine scope, cancelled in [onTerminate]. */
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
            configureCrashReporting()
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
     * Wire Firebase Crashlytics (issue #233) collection to the build variant:
     * debug builds never phone home so local repros don't pollute the
     * dashboard, release builds collect by default. A user-facing toggle can
     * call [CrashReporter.setCollectionEnabled] later to honour opt-out.
     *
     * Separated from [onCreate] so tests can mock [CrashReporter] and assert
     * the initialization call without invoking Firebase's real runtime.
     */
    internal fun configureCrashReporting(
        crashReporter: CrashReporter = GlobalContext.get().get(),
        isDebugBuild: Boolean = BuildConfig.DEBUG
    ) {
        crashReporter.setCollectionEnabled(!isDebugBuild)
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
            val request = PeriodicWorkRequestBuilder<SecurityCheckWorker>(
                intervalHours.toLong(),
                TimeUnit.HOURS,
                flexHours.toLong(),
                TimeUnit.HOURS
            ).build()
            WorkManager.getInstance(this@RouseApplication).enqueueUniquePeriodicWork(
                "security-check",
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
        }
    }

    private fun scopeModule() = module {
        single(named("appScope")) { appScope }
    }
}
