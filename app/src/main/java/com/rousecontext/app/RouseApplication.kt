package com.rousecontext.app

import android.app.Application
import android.util.Log
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.rousecontext.app.debug.debugModules
import com.rousecontext.app.di.appModule
import com.rousecontext.notifications.NotificationChannels
import com.rousecontext.work.FcmTokenRegistrar
import com.rousecontext.work.SecurityCheckWorker
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.core.qualifier.named
import org.koin.dsl.module

/**
 * Application subclass that initializes Koin DI.
 */
class RouseApplication : Application() {

    /** Application-scoped coroutine scope, cancelled in [onTerminate]. */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

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
        registerFcmToken()
        scheduleSecurityChecks()
    }

    private fun scheduleSecurityChecks() {
        val request = PeriodicWorkRequestBuilder<SecurityCheckWorker>(
            4,
            TimeUnit.HOURS,
            1,
            TimeUnit.HOURS
        ).build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "security-check",
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }

    /**
     * Ensures the current FCM token is registered in Firestore.
     * Runs on app start so the relay always has an up-to-date token,
     * even if a previous onNewToken callback was missed.
     */
    private fun registerFcmToken() {
        val registrar: FcmTokenRegistrar by inject()
        appScope.launch {
            try {
                registrar.registerCurrentToken()
            } catch (e: Exception) {
                Log.w(TAG, "FCM token registration failed (will retry on next launch)", e)
            }
        }
    }

    private fun scopeModule() = module {
        single(named("appScope")) { appScope }
    }

    companion object {
        private const val TAG = "RouseApplication"
    }
}
