package com.rousecontext.app

import android.app.Application
import com.rousecontext.app.di.appModule
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
                appModule
            )
        }
    }

    private fun scopeModule() = module {
        single(named("appScope")) { appScope }
    }
}
