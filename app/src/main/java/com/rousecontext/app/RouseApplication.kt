package com.rousecontext.app

import android.app.Application
import android.content.Context
import androidx.work.Configuration
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.rousecontext.app.debug.debugModules
import com.rousecontext.app.di.appModule
import com.rousecontext.notifications.NotificationChannels
import com.rousecontext.work.CertRenewalScheduler
import com.rousecontext.work.KoinWorkerFactory
import com.rousecontext.work.SecurityCheckWorker
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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

    private fun scheduleSecurityChecks() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val intervalHours = prefs.getInt(KEY_SECURITY_CHECK_INTERVAL_HOURS, DEFAULT_INTERVAL_HOURS)
        val flexHours = (intervalHours / 4).coerceAtLeast(1)
        val request = PeriodicWorkRequestBuilder<SecurityCheckWorker>(
            intervalHours.toLong(),
            TimeUnit.HOURS,
            flexHours.toLong(),
            TimeUnit.HOURS
        ).build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "security-check",
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }

    private fun scopeModule() = module {
        single(named("appScope")) { appScope }
    }

    companion object {
        const val PREFS_NAME = "rouse_settings"
        const val KEY_SECURITY_CHECK_INTERVAL_HOURS = "security_check_interval_hours"
        const val DEFAULT_INTERVAL_HOURS = 12
    }
}
