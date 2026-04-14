package com.rousecontext.work

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.rousecontext.tunnel.SecurityCheckResult
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.core.qualifier.named
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner

/**
 * End-to-end: enqueue [SecurityCheckWorker] through WorkManager initialised with a
 * configuration that uses [KoinWorkerFactory]. Without the factory wiring, the worker
 * would throw [UninitializedPropertyAccessException] before writing its result.
 *
 * This proves the production path works — `Configuration.Provider` in RouseApplication
 * hands the same factory to WorkManager in the app.
 */
@RunWith(RobolectricTestRunner::class)
class WorkManagerFactoryIntegrationTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        stopKoin()
        context = ApplicationProvider.getApplicationContext()

        startKoin {
            modules(
                module {
                    single<SecurityCheckSource>(named("selfCert")) {
                        StubCheckSource(SecurityCheckResult.Verified)
                    }
                    single<SecurityCheckSource>(named("ctLog")) {
                        StubCheckSource(SecurityCheckResult.Verified)
                    }
                }
            )
        }

        val config = Configuration.Builder()
            .setExecutor(SynchronousExecutor())
            .setTaskExecutor(SynchronousExecutor())
            .setWorkerFactory(KoinWorkerFactory(GlobalContext.get()))
            .build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, config)

        // Clear any prior run state.
        context.getSharedPreferences(SecurityCheckWorker.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @After
    fun tearDown() {
        stopKoin()
    }

    @Test
    fun `WorkManager runs SecurityCheckWorker with injected dependencies`() {
        val request = OneTimeWorkRequestBuilder<SecurityCheckWorker>().build()
        val wm = WorkManager.getInstance(context)

        wm.enqueue(request).result.get()

        val info = requireNotNull(wm.getWorkInfoById(request.id).get()) {
            "WorkInfo must be present after enqueue"
        }
        assertEquals(
            "Worker should have succeeded (injection + check + prefs update)",
            WorkInfo.State.SUCCEEDED,
            info.state
        )

        // Prefs write only happens if both `selfCertVerifier` and `ctLogMonitor` were
        // injected — otherwise the lateinit access in doWork() throws first.
        val prefs = context.getSharedPreferences(
            SecurityCheckWorker.PREFS_NAME,
            Context.MODE_PRIVATE
        )
        assertEquals(
            "verified",
            prefs.getString(SecurityCheckWorker.KEY_SELF_CERT_RESULT, null)
        )
        assertEquals(
            "verified",
            prefs.getString(SecurityCheckWorker.KEY_CT_LOG_RESULT, null)
        )
    }
}

private class StubCheckSource(private val result: SecurityCheckResult) : SecurityCheckSource {
    override suspend fun check(): SecurityCheckResult = result
}
