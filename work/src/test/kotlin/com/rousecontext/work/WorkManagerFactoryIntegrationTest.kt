package com.rousecontext.work

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.rousecontext.notifications.AndroidSecurityCheckNotifier
import com.rousecontext.notifications.SecurityCheckNotifier
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
                    single<SecurityCheckNotifier> { AndroidSecurityCheckNotifier(context) }
                    single { SecurityCheckPreferences(context) }
                }
            )
        }

        val config = Configuration.Builder()
            .setExecutor(SynchronousExecutor())
            .setTaskExecutor(SynchronousExecutor())
            .setWorkerFactory(KoinWorkerFactory(GlobalContext.get()))
            .build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, config)

        // Reset DataStore-backed state to a known baseline.
        kotlinx.coroutines.runBlocking {
            SecurityCheckPreferences(context).clearResults()
        }
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

        // After the DataStore migration (#116) SecurityCheckPreferences is suspend-backed,
        // so SynchronousExecutor no longer drains the worker's structured-concurrency work
        // by the time enqueue().result.get() returns. Poll for a terminal state instead.
        val info = pollWorkInfoUntilFinished(wm, request.id, timeoutMs = 5_000)
        assertEquals(
            "Worker should have succeeded (injection + check + prefs update)",
            WorkInfo.State.SUCCEEDED,
            info.state
        )

        // Prefs write only happens if both `selfCertVerifier` and `ctLogMonitor` were
        // injected — otherwise the lateinit access in doWork() throws first.
        val prefs = SecurityCheckPreferences(context)
        kotlinx.coroutines.runBlocking {
            assertEquals("verified", prefs.selfCertResult())
            assertEquals("verified", prefs.ctLogResult())
        }
    }

    private fun pollWorkInfoUntilFinished(
        wm: WorkManager,
        id: java.util.UUID,
        timeoutMs: Long,
        pollIntervalMs: Long = 50
    ): WorkInfo {
        val deadline = System.currentTimeMillis() + timeoutMs
        var last: WorkInfo? = null
        while (System.currentTimeMillis() < deadline) {
            val info = wm.getWorkInfoById(id).get()
            if (info != null) {
                last = info
                if (info.state.isFinished) return info
            }
            Thread.sleep(pollIntervalMs)
        }
        throw AssertionError(
            "WorkInfo did not reach a terminal state within ${timeoutMs}ms " +
                "(last state=${last?.state})"
        )
    }
}

private class StubCheckSource(private val result: SecurityCheckResult) : SecurityCheckSource {
    override suspend fun check(): SecurityCheckResult = result
}
