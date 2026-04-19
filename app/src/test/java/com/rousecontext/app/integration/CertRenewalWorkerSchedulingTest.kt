package com.rousecontext.app.integration

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.rousecontext.app.integration.harness.AppIntegrationTestHarness
import com.rousecontext.tunnel.RenewalResult
import com.rousecontext.work.CertRenewalPreferences
import com.rousecontext.work.CertRenewalScheduler
import com.rousecontext.work.CertRenewalWorker
import com.rousecontext.work.CertRenewer
import com.rousecontext.work.FirebaseCredentials
import com.rousecontext.work.KoinWorkerFactory
import com.rousecontext.work.RenewalAuthProvider
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner

/**
 * Integration-tier scheduling harness for [CertRenewalWorker] (issue #277).
 *
 * Production wiring (`RouseApplication.onCreate` → [CertRenewalScheduler.enqueuePeriodic])
 * schedules a periodic job that drives [CertRenewer] inside the
 * [CertRenewalWorker]. The worker's decision logic is unit-tested separately;
 * this file covers the WorkManager side of the contract — enqueue semantics,
 * constraints, trigger-near-expiry behaviour, failure backoff, and reschedule
 * after success — which is the day-60-90 ticking time bomb referenced in the
 * umbrella issue #270 if any of those wirings regresses.
 *
 * The scenarios overwrite the on-disk cert PEM written by
 * [AppIntegrationTestHarness.provisionDevice] with a keytool-generated
 * self-signed cert whose validity puts
 * [com.rousecontext.tunnel.CertificateStore.getCertExpiry] at
 * the required offset from `System.currentTimeMillis()`. Renewal outcomes
 * (success / failure / rate-limited) come from a fake [CertRenewer] loaded
 * into the harness's Koin graph with `allowOverride = true`; exercising the
 * real relay again here would repeat `CertRotationIntegrationTest` without
 * adding coverage of the worker's scheduling path.
 */
@RunWith(RobolectricTestRunner::class)
class CertRenewalWorkerSchedulingTest {

    private val harness = AppIntegrationTestHarness()
    private val context: Context = ApplicationProvider.getApplicationContext()

    private val fakeRenewer = RecordingFakeRenewer()
    private val fakeAuth = FakeAuthProvider()

    @Before
    fun setUp() {
        harness.start()

        // Swap `CertRenewer` / `RenewalAuthProvider` onto the live Koin graph so
        // the worker — constructed by `KoinWorkerFactory(harness.koin)` — resolves
        // the fakes. The real bindings would drive the relay subprocess, which is
        // already covered by `CertRotationIntegrationTest` and would make
        // failure-mode scenarios (rate-limit, network error) impossible to
        // simulate cleanly.
        harness.koin.loadModules(
            listOf(
                module {
                    single<CertRenewer> { fakeRenewer }
                    single<RenewalAuthProvider> { fakeAuth }
                }
            ),
            allowOverride = true
        )

        val config = Configuration.Builder()
            .setExecutor(SynchronousExecutor())
            .setTaskExecutor(SynchronousExecutor())
            .setWorkerFactory(KoinWorkerFactory(harness.koin))
            .setMinimumLoggingLevel(android.util.Log.DEBUG)
            .build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, config)
    }

    @After
    fun tearDown() {
        harness.stop()
    }

    // ------------------------------------------------------------------
    // Scenario 1: enqueue-on-app-start creates the expected periodic work
    // ------------------------------------------------------------------

    @Test
    fun `enqueuePeriodic schedules unique periodic work with expected constraints`() {
        CertRenewalScheduler.enqueuePeriodic(context)

        val wm = WorkManager.getInstance(context)
        val infos = wm.getWorkInfosForUniqueWork(CertRenewalWorker.WORK_NAME).get()

        assertEquals(
            "CertRenewalScheduler.enqueuePeriodic must create exactly one unique " +
                "work entry under `${CertRenewalWorker.WORK_NAME}`",
            1,
            infos.size
        )
        val info = infos.single()
        assertEquals(
            "Freshly-enqueued periodic work should be ENQUEUED (waiting for next run)",
            WorkInfo.State.ENQUEUED,
            info.state
        )
        val constraints = info.constraints
        assertEquals(
            "Renewal must require network connectivity to reach the relay",
            NetworkType.CONNECTED,
            constraints.requiredNetworkType
        )
        assertTrue(
            "Renewal must not fire on a dying phone",
            constraints.requiresBatteryNotLow()
        )
        assertFalse(
            "Charging must NOT be required — the renewal MUST run within the cert " +
                "expiry window even if the device never charges",
            constraints.requiresCharging()
        )
    }

    // ------------------------------------------------------------------
    // Scenario 2: cert within the renewal window triggers renewal
    // ------------------------------------------------------------------

    @Test
    fun `worker renews when cert expiry is within the renewal window`() = runBlocking {
        harness.provisionDevice()
        overwriteCertOnDisk(daysUntilExpiry = 10L)

        val workId = enqueueAndDrain()

        val wm = WorkManager.getInstance(context)
        val info = requireNotNull(wm.getWorkInfoById(workId).get()) {
            "WorkInfo missing for renewal work $workId"
        }
        assertEquals(
            "Worker returned Result.success() after a fake-renewer SUCCESS",
            WorkInfo.State.SUCCEEDED,
            info.state
        )
        assertEquals(
            "mTLS renewal path must have been invoked exactly once",
            1,
            fakeRenewer.mtlsCalls
        )
        assertEquals(
            "Firebase renewal path must not fire when cert is unexpired",
            0,
            fakeRenewer.firebaseCalls
        )
        val outcome = CertRenewalPreferences(context).lastOutcome()
        assertEquals(CertRenewalWorker.Outcome.SUCCESS.name, outcome)
    }

    // ------------------------------------------------------------------
    // Scenario 3: cert well outside the renewal window does not renew
    // ------------------------------------------------------------------

    @Test
    fun `worker skips renewal when cert is far from expiry`() = runBlocking {
        harness.provisionDevice()
        overwriteCertOnDisk(daysUntilExpiry = 80L)

        val workId = enqueueAndDrain()

        val wm = WorkManager.getInstance(context)
        val info = requireNotNull(wm.getWorkInfoById(workId).get()) {
            "WorkInfo missing for renewal work $workId"
        }
        assertEquals(
            "Worker returned Result.success() after the early-out path",
            WorkInfo.State.SUCCEEDED,
            info.state
        )
        assertEquals(
            "Cert well outside the renewal window must not invoke any renewer path",
            0,
            fakeRenewer.mtlsCalls + fakeRenewer.firebaseCalls
        )
        val outcome = CertRenewalPreferences(context).lastOutcome()
        assertEquals(
            "Outcome must record the early-out so the dashboard shows a green tick",
            CertRenewalWorker.Outcome.SKIP_VALID.name,
            outcome
        )
    }

    // ------------------------------------------------------------------
    // Scenario 4: transient renewer failure reports RETRY under WorkManager
    // ------------------------------------------------------------------

    @Test
    fun `worker returns RETRY on transient renewal failure`() = runBlocking {
        harness.provisionDevice()
        overwriteCertOnDisk(daysUntilExpiry = 10L)

        fakeRenewer.mtlsResult = RenewalResult.NetworkError(RuntimeException("boom"))

        val workId = enqueueAndDrain()

        val wm = WorkManager.getInstance(context)
        val info = requireNotNull(wm.getWorkInfoById(workId).get()) {
            "WorkInfo missing for renewal work $workId"
        }
        // One-time work that returned `Result.retry()` sits in ENQUEUED with an
        // incremented run-attempt counter — WorkManager will retry after the
        // backoff period. For periodic work the same `Result.retry()` translates
        // to "rerun after the backoff window"; both cases are equivalent here
        // from the scheduler's point of view.
        assertEquals(
            "Retryable failure must leave the work ENQUEUED for WorkManager to retry",
            WorkInfo.State.ENQUEUED,
            info.state
        )
        assertTrue(
            "runAttemptCount must have incremented from the failed attempt",
            info.runAttemptCount >= 1
        )
        assertEquals(
            "Renewer must still have been invoked — the retry is *because* it failed",
            1,
            fakeRenewer.mtlsCalls
        )
        val outcome = CertRenewalPreferences(context).lastOutcome()
        assertEquals(
            CertRenewalWorker.Outcome.NETWORK_ERROR.name,
            outcome
        )
    }

    // ------------------------------------------------------------------
    // Scenario 5: periodic work reschedules after a successful run
    // ------------------------------------------------------------------

    @Test
    fun `periodic work is still enqueued for the next period after success`() = runBlocking {
        harness.provisionDevice()
        overwriteCertOnDisk(daysUntilExpiry = 10L)

        CertRenewalScheduler.enqueuePeriodic(context)

        val wm = WorkManager.getInstance(context)
        val initial = wm.getWorkInfosForUniqueWork(CertRenewalWorker.WORK_NAME).get().single()

        // Drive the first period to completion. For a periodic worker that
        // returns Result.success() the state transitions RUNNING → ENQUEUED
        // again for the next period — which is exactly the "re-schedule after
        // success" behaviour issue #277 wants verified.
        val testDriver = WorkManagerTestInitHelper.getTestDriver(context)
            ?: error("WorkManagerTestInitHelper.getTestDriver returned null")
        testDriver.setPeriodDelayMet(initial.id)
        testDriver.setAllConstraintsMet(initial.id)

        val after = pollUntil(timeoutMs = POLL_TIMEOUT_MS) {
            val infos = wm.getWorkInfosForUniqueWork(CertRenewalWorker.WORK_NAME).get()
            infos.singleOrNull()?.takeIf { it.state == WorkInfo.State.ENQUEUED }
        } ?: error(
            "Periodic work did not return to ENQUEUED within ${POLL_TIMEOUT_MS}ms " +
                "(last state=${wm.getWorkInfosForUniqueWork(CertRenewalWorker.WORK_NAME)
                    .get().firstOrNull()?.state})"
        )

        assertEquals(
            "Same unique work id — periodic work must be rescheduled under the " +
                "same unique-name slot",
            initial.id,
            after.id
        )
        assertEquals(
            "After a successful period the work must be ENQUEUED for the next run",
            WorkInfo.State.ENQUEUED,
            after.state
        )
        assertEquals(
            "mTLS renewal was invoked during the first period",
            1,
            fakeRenewer.mtlsCalls
        )
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private fun enqueueAndDrain(): UUID {
        // Use a OneTimeWorkRequest here rather than a periodic. The production
        // scheduling path is covered separately by the `enqueuePeriodic`
        // scenario; using one-time work for the behavioural scenarios lets us
        // assert terminal states (SUCCEEDED / ENQUEUED-after-retry) directly on
        // the individual work id without worrying about WorkManager's periodic
        // "state bounces back to ENQUEUED on success" behaviour masking the
        // terminal result of the run itself.
        val request = OneTimeWorkRequestBuilder<CertRenewalWorker>().build()
        val wm = WorkManager.getInstance(context)
        wm.enqueue(request).result.get()

        // Test driver is required to force the constraints-gated worker to run
        // immediately — the prod worker requires CONNECTED + !batteryLow which
        // Robolectric reports as unmet by default.
        val testDriver = WorkManagerTestInitHelper.getTestDriver(context)
            ?: error("WorkManagerTestInitHelper.getTestDriver returned null")
        testDriver.setAllConstraintsMet(request.id)

        // SynchronousExecutor dispatches the worker body on the caller thread,
        // but the DataStore-backed `recordLastAttempt` writes via a structured
        // child coroutine that isn't drained by `enqueue().result.get()`. Poll
        // for the terminal state — same pattern as WorkManagerFactoryIntegrationTest.
        pollWorkInfo(wm, request.id)
        return request.id
    }

    private fun pollWorkInfo(wm: WorkManager, id: UUID): WorkInfo = pollUntil(POLL_TIMEOUT_MS) {
        val info: WorkInfo? = wm.getWorkInfoById(id).get()
        if (info != null && info.state != WorkInfo.State.RUNNING) info else null
    } ?: error("WorkInfo for $id did not leave RUNNING within ${POLL_TIMEOUT_MS}ms")

    private fun <T : Any> pollUntil(timeoutMs: Long, block: () -> T?): T? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val v = block()
            if (v != null) return v
            Thread.sleep(POLL_INTERVAL_MS)
        }
        return null
    }

    /**
     * Write a self-signed cert PEM to the location [com.rousecontext.app.cert.FileCertificateStore]
     * reads from, with `notAfter` set to `daysUntilExpiry` days from now.
     *
     * We drive [keytool] — already a transitive prerequisite of
     * [com.rousecontext.tunnel.integration.TestRelayFixture] — rather than pulling
     * BouncyCastle in just for this. The worker only cares about [notAfter]; the
     * subject/issuer/key algorithm of the overwritten cert is irrelevant to the
     * scheduling decision.
     */
    private fun overwriteCertOnDisk(daysUntilExpiry: Long) {
        val pem = generatePemWithValidity(daysUntilExpiry)
        val certFile = File(context.filesDir, "rouse_cert.pem")
        certFile.writeText(pem)
    }

    private fun generatePemWithValidity(daysUntilExpiry: Long): String {
        val workDir = File.createTempFile("cert-renewal-test-", "").apply {
            delete()
            mkdirs()
        }
        try {
            val keystore = File(workDir, "ks.p12")
            val certOut = File(workDir, "cert.pem")

            // keytool's -startdate accepts an offset string like "-1d+0H+0M+0S"
            // (no spaces). Anchor notBefore to "yesterday" so no clock-skew
            // edge makes a nominally-still-valid cert look future-dated, then
            // set validity so notAfter = yesterday + (daysUntilExpiry + 1).
            val startDate = "-1d"
            val validity = (daysUntilExpiry + 1L).coerceAtLeast(1L).toString()

            runKeytool(
                "-genkeypair", "-alias", "stub",
                "-keyalg", "RSA", "-keysize", "2048",
                "-sigalg", "SHA256withRSA",
                "-dname", "CN=renewal-scheduling-test",
                "-startdate", startDate,
                "-validity", validity,
                "-storetype", "PKCS12",
                "-keystore", keystore.absolutePath,
                "-storepass", KEYTOOL_STOREPASS,
                "-keypass", KEYTOOL_STOREPASS
            )
            runKeytool(
                "-exportcert", "-alias", "stub",
                "-keystore", keystore.absolutePath,
                "-storepass", KEYTOOL_STOREPASS,
                "-rfc",
                "-file", certOut.absolutePath
            )
            return certOut.readText()
        } finally {
            workDir.deleteRecursively()
        }
    }

    private fun runKeytool(vararg args: String) {
        val proc = ProcessBuilder("keytool", *args)
            .redirectErrorStream(true)
            .start()
        val output = proc.inputStream.bufferedReader().readText()
        val exit = proc.waitFor()
        if (exit != 0) {
            error("keytool exit=$exit args=${args.toList()}\n$output")
        }
    }

    private companion object {
        const val POLL_TIMEOUT_MS = 10_000L
        const val POLL_INTERVAL_MS = 50L
        const val KEYTOOL_STOREPASS = "changeit"
    }
}

// ------------------------------------------------------------------
// Test doubles
// ------------------------------------------------------------------

private class RecordingFakeRenewer(
    var mtlsResult: RenewalResult = RenewalResult.Success,
    var firebaseResult: RenewalResult = RenewalResult.Success
) : CertRenewer {
    var mtlsCalls: Int = 0
        private set
    var firebaseCalls: Int = 0
        private set

    override suspend fun renewWithMtls(
        authProvider: RenewalAuthProvider,
        baseDomain: String
    ): RenewalResult {
        mtlsCalls++
        return mtlsResult
    }

    override suspend fun renewWithFirebase(
        authProvider: RenewalAuthProvider,
        baseDomain: String
    ): RenewalResult {
        firebaseCalls++
        return firebaseResult
    }
}

private class FakeAuthProvider : RenewalAuthProvider {
    override suspend fun signCsr(csrDer: ByteArray): String? = "stub-signature"
    override suspend fun acquireFirebaseCredentials(csrDer: ByteArray): FirebaseCredentials? =
        FirebaseCredentials(token = "stub-token", signature = "stub-signature")
}
