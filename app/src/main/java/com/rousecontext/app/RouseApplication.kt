package com.rousecontext.app

import android.app.Application
import androidx.work.Configuration
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.rousecontext.app.debug.debugModules
import com.rousecontext.app.di.appModule
import com.rousecontext.app.state.AppStatePreferences
import com.rousecontext.notifications.NotificationChannels
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

        NotificationChannels.createAll(this)
        scheduleSecurityChecks()
        CertRenewalScheduler.enqueuePeriodic(this)
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
