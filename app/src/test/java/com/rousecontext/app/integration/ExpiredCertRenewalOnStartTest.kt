package com.rousecontext.app.integration

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.rousecontext.app.integration.harness.AppIntegrationTestHarness
import com.rousecontext.tunnel.CertificateStore
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner

/**
 * Integration-tier coverage for issues #278 + #289.
 *
 * # What this test file covers
 *
 * Issue #277 ([CertRenewalWorkerSchedulingTest]) verifies WorkManager
 * *scheduling* wiring (constraints, reschedule-after-success, periodic-work
 * enqueue semantics) and the **near-expiry mTLS** branch of
 * [CertRenewalWorker.doWork]. Issue #278 asked for coverage of the
 * **already-expired Firebase** branch that sibling test didn't exercise.
 * Issue #289 asked for coverage of the app-start *immediate-renewal* path
 * that was missing prior to this fix.
 *
 * This file owns both:
 *
 * - Worker-path scenarios (the #278 coverage): an already-expired cert takes
 *   [CertRenewer.renewWithFirebase], not the mTLS path, and the
 *   `EXPIRED_NO_AUTH` outcome is persisted when Firebase auth is unavailable.
 * - App-start scheduling scenarios (the #289 coverage):
 *   [CertRenewalScheduler.enqueueImmediateIfExpiring] — the new call wired
 *   from [com.rousecontext.app.RouseApplication.onCreate] — actually enqueues
 *   an immediate one-time renewal when the cert is expired or near-expiry,
 *   is a no-op for a healthy cert, and does NOT clobber the periodic slot.
 *
 * # What this test file does NOT cover
 *
 * The `enqueueImmediateIfExpiring` threshold-decision unit tests (no cert,
 * far-from-expiry vs near-expiry, `ExistingWorkPolicy.KEEP` semantics) live
 * in `:work`'s [com.rousecontext.work.CertRenewalSchedulerTest]. That suite
 * drives the scheduler directly without the relay fixture, so it's the
 * cheaper place to cover pure-decision logic; this file is the integration
 * counterpart that proves the immediate-renewal path reaches the real
 * [com.rousecontext.app.cert.FileCertificateStore] off disk and actually
 * runs the worker end-to-end.
 *
 * # Why integration tier
 *
 * The [com.rousecontext.work.CertRenewalWorkerTest] unit tests already
 * cover the worker's decision logic with an in-memory
 * [com.rousecontext.tunnel.CertificateStore]. The integration-tier bar is
 * that the real [com.rousecontext.app.cert.FileCertificateStore] parses a
 * truly-expired PEM off disk, the real [KoinWorkerFactory] wires the
 * fakes onto the worker, the real [CertRenewalPreferences] DataStore
 * writes outcome telemetry, and WorkManager drives the whole thing — the
 * same shape every production-code regression would have to traverse to
 * be caught.
 *
 * We invoke [CertRenewalScheduler] directly (rather than booting a fresh
 * [com.rousecontext.app.RouseApplication] per scenario) because the harness
 * already stands up the full Koin graph with the fixture relay —
 * re-constructing the `Application` under Robolectric would re-boot Koin
 * underneath the running relay and hit teardown ordering bugs. The scheduler
 * call is the exact one that `onCreate` makes, so this covers the
 * production path without the `Application`-lifecycle tangle.
 */
@RunWith(RobolectricTestRunner::class)
class ExpiredCertRenewalOnStartTest {

    private val harness = AppIntegrationTestHarness()
    private val context: Context = ApplicationProvider.getApplicationContext()

    private val fakeRenewer = ExpiredCertFakeRenewer()
    private val fakeAuth = ExpiredCertFakeAuthProvider()

    @Before
    fun setUp() {
        harness.start()

        // Swap the `CertRenewer` + `RenewalAuthProvider` onto the live Koin graph
        // so the worker — constructed by `KoinWorkerFactory(harness.koin)` —
        // resolves the fakes. The real binding drives the relay subprocess
        // (covered in `CertRotationIntegrationTest`); swapping in fakes here
        // lets us assert *which* branch (mTLS vs. Firebase) the worker takes
        // and force the error variants (`FirebaseAuthUnavailable`) without
        // relay-side gymnastics.
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
    // Scenario 1: expired cert → Firebase renewal path, SUCCESS outcome
    // ------------------------------------------------------------------

    @Test
    fun `worker takes Firebase path when stored cert has already expired`() = runBlocking {
        harness.provisionDevice()
        overwriteCertOnDisk(daysUntilExpiry = -5L)

        val workId = enqueueAndDrain()

        val wm = WorkManager.getInstance(context)
        val info = requireNotNull(wm.getWorkInfoById(workId).get()) {
            "WorkInfo missing for expired-cert renewal work $workId"
        }
        assertEquals(
            "Worker must return Result.success() on Firebase-path success",
            WorkInfo.State.SUCCEEDED,
            info.state
        )
        assertEquals(
            "Expired cert must route to the Firebase renewal path, NOT the mTLS " +
                "path — the stored cert can no longer serve as proof-of-possession",
            1,
            fakeRenewer.firebaseCalls
        )
        assertEquals(
            "mTLS path must not fire for an already-expired cert — it would be " +
                "rejected by the relay as `RenewalResult.CertExpired` and waste a round-trip",
            0,
            fakeRenewer.mtlsCalls
        )
        val outcome = CertRenewalPreferences(context).lastOutcome()
        assertEquals(CertRenewalWorker.Outcome.SUCCESS.name, outcome)
    }

    // ------------------------------------------------------------------
    // Scenario 2: expired cert + Firebase auth unavailable → RETRY + EXPIRED_NO_AUTH
    // ------------------------------------------------------------------

    @Test
    fun `worker reports EXPIRED_NO_AUTH when Firebase auth is unavailable`() = runBlocking {
        harness.provisionDevice()
        overwriteCertOnDisk(daysUntilExpiry = -5L)

        // The renewer itself reports FirebaseAuthUnavailable — e.g. no signed-in
        // Firebase user, or Keystore signing threw transiently. The worker MUST
        // persist this outcome so the dashboard banner code (see
        // `certRenewalBannerFlow`) can surface "we need you to sign back in"
        // affordances later. Returning Result.retry keeps WorkManager pushing
        // the job back into the queue until the auth condition clears.
        fakeRenewer.firebaseResult = RenewalResult.FirebaseAuthUnavailable

        val workId = enqueueAndDrain()

        val wm = WorkManager.getInstance(context)
        val info = requireNotNull(wm.getWorkInfoById(workId).get()) {
            "WorkInfo missing for expired-cert renewal work $workId"
        }
        assertEquals(
            "Transient auth-unavailable must leave the work ENQUEUED for retry",
            WorkInfo.State.ENQUEUED,
            info.state
        )
        assertEquals(
            "Firebase renewal must still be attempted — the retry is *because* the " +
                "credentials provider returned null mid-call",
            1,
            fakeRenewer.firebaseCalls
        )
        val outcome = CertRenewalPreferences(context).lastOutcome()
        assertEquals(
            "Outcome must be EXPIRED_NO_AUTH so the dashboard can prompt re-auth " +
                "rather than surface a generic `network error`",
            CertRenewalWorker.Outcome.EXPIRED_NO_AUTH.name,
            outcome
        )
    }

    // ------------------------------------------------------------------
    // Scenario 3: app start with an already-expired cert forces an
    // immediate renewal (the #289 fix). Inverts the earlier regression
    // guard — once #289 landed, we no longer expect the expired-cert user
    // to sit stuck for up to 24h.
    // ------------------------------------------------------------------

    @Test
    fun `app start with expired cert forces an immediate renewal run`() = runBlocking {
        harness.provisionDevice()
        overwriteCertOnDisk(daysUntilExpiry = -5L)

        val certStore: CertificateStore = harness.koin.get()

        // Reproduce the exact `RouseApplication.onCreate` sequence.
        CertRenewalScheduler.enqueuePeriodic(context)
        val enqueued = CertRenewalScheduler.enqueueImmediateIfExpiring(context, certStore)

        assertTrue(
            "Expired cert at app start MUST force an immediate renewal — " +
                "this is the #289 fix. Before the fix landed, only the periodic " +
                "worker was scheduled and the expired-cert user sat stuck for " +
                "up to 24h.",
            enqueued
        )

        val wm = WorkManager.getInstance(context)
        val periodicInfos = wm.getWorkInfosForUniqueWork(CertRenewalWorker.WORK_NAME).get()
        assertEquals(
            "Periodic slot must still hold exactly one periodic worker entry",
            1,
            periodicInfos.size
        )
        assertNotNull(
            "Periodic work must be tracked",
            periodicInfos.single()
        )

        val immediateInfos = wm
            .getWorkInfosForUniqueWork(CertRenewalScheduler.WORK_NAME_IMMEDIATE)
            .get()
        assertEquals(
            "Immediate slot must hold the one-time renewal request",
            1,
            immediateInfos.size
        )
        val immediateInfo = immediateInfos.single()

        // Drive the constraints-gated one-time worker to completion.
        val testDriver = WorkManagerTestInitHelper.getTestDriver(context)
            ?: error("WorkManagerTestInitHelper.getTestDriver returned null")
        testDriver.setAllConstraintsMet(immediateInfo.id)

        val finalInfo = pollWorkInfo(wm, immediateInfo.id)
        assertEquals(
            "Immediate renewal must run to completion, not just sit enqueued",
            WorkInfo.State.SUCCEEDED,
            finalInfo.state
        )
        assertEquals(
            "Expired cert must take the Firebase renewal path",
            1,
            fakeRenewer.firebaseCalls
        )
        assertEquals(
            "mTLS path must not fire for an expired cert",
            0,
            fakeRenewer.mtlsCalls
        )
    }

    // ------------------------------------------------------------------
    // Scenario 4: near-expiry cert at app start also forces an immediate
    // renewal — the window is wider than "already expired" so the user
    // is pre-empted before the tunnel has a chance to break.
    // ------------------------------------------------------------------

    @Test
    fun `app start with near-expiry cert forces an immediate renewal run`() = runBlocking {
        harness.provisionDevice()
        overwriteCertOnDisk(daysUntilExpiry = 5L) // < 21-day window

        val certStore: CertificateStore = harness.koin.get()
        val enqueued = CertRenewalScheduler.enqueueImmediateIfExpiring(context, certStore)

        assertTrue(
            "Cert inside the 21-day renewal window must also trigger the " +
                "immediate run — otherwise the first tunnel reconnect after app " +
                "open races against the periodic schedule.",
            enqueued
        )

        val wm = WorkManager.getInstance(context)
        val immediateInfo = wm
            .getWorkInfosForUniqueWork(CertRenewalScheduler.WORK_NAME_IMMEDIATE)
            .get()
            .single()

        val testDriver = WorkManagerTestInitHelper.getTestDriver(context)
            ?: error("WorkManagerTestInitHelper.getTestDriver returned null")
        testDriver.setAllConstraintsMet(immediateInfo.id)

        val finalInfo = pollWorkInfo(wm, immediateInfo.id)
        assertEquals(WorkInfo.State.SUCCEEDED, finalInfo.state)
        assertEquals(
            "Near-expiry (not yet expired) cert must take the mTLS renewal path",
            1,
            fakeRenewer.mtlsCalls
        )
        assertEquals(
            "Firebase path must not fire for a still-valid near-expiry cert",
            0,
            fakeRenewer.firebaseCalls
        )
    }

    // ------------------------------------------------------------------
    // Scenario 5: healthy cert at app start is a no-op. Prevents a
    // regression where the new immediate-renewal path over-fires and
    // turns into a second periodic worker.
    // ------------------------------------------------------------------

    @Test
    fun `app start with healthy cert does not enqueue immediate work`() = runBlocking {
        harness.provisionDevice()
        overwriteCertOnDisk(daysUntilExpiry = 70L) // well beyond the 21-day window

        val certStore: CertificateStore = harness.koin.get()
        val enqueued = CertRenewalScheduler.enqueueImmediateIfExpiring(context, certStore)

        assertFalse(
            "Healthy cert (70d > 21d window) must NOT enqueue an immediate run — " +
                "the periodic worker is the correct owner of long-window renewals",
            enqueued
        )

        val wm = WorkManager.getInstance(context)
        val infos = wm
            .getWorkInfosForUniqueWork(CertRenewalScheduler.WORK_NAME_IMMEDIATE)
            .get()
        assertTrue(
            "No immediate work should exist for a healthy cert",
            infos.isEmpty()
        )
        assertEquals(
            "Renewer must not be invoked",
            0,
            fakeRenewer.mtlsCalls + fakeRenewer.firebaseCalls
        )
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    /**
     * Enqueue a one-time copy of [CertRenewalWorker] and drive it to a terminal
     * state under the synchronous test dispatcher. Same pattern as
     * [CertRenewalWorkerSchedulingTest.enqueueAndDrain] — one-time work lets us
     * assert the terminal state directly rather than untangle WorkManager's
     * periodic-work "state bounces back to ENQUEUED on success" semantics.
     *
     * Used by the worker-path scenarios that exercise [CertRenewalWorker]
     * directly; the app-start scenarios go through
     * [CertRenewalScheduler.enqueueImmediateIfExpiring] instead so they cover
     * the production enqueue path.
     */
    private fun enqueueAndDrain(): UUID {
        val request = OneTimeWorkRequestBuilder<CertRenewalWorker>().build()
        val wm = WorkManager.getInstance(context)
        wm.enqueue(request).result.get()

        val testDriver = WorkManagerTestInitHelper.getTestDriver(context)
            ?: error("WorkManagerTestInitHelper.getTestDriver returned null")
        testDriver.setAllConstraintsMet(request.id)

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
     * Write a self-signed cert PEM to the location
     * [com.rousecontext.app.cert.FileCertificateStore] reads from, with
     * `notAfter` set relative to now by [daysUntilExpiry] (negative = already
     * expired, positive = still valid).
     *
     * Implementation mirrors [CertRenewalWorkerSchedulingTest] — keytool is
     * already a transitive prereq of the integration harness, and the worker
     * only reads `notAfter` from the stored cert so subject / issuer / key
     * material are irrelevant to the scheduling decision.
     */
    private fun overwriteCertOnDisk(daysUntilExpiry: Long) {
        val pem = generatePemWithValidity(daysUntilExpiry)
        val certFile = File(context.filesDir, "rouse_cert.pem")
        certFile.writeText(pem)
    }

    private fun generatePemWithValidity(daysUntilExpiry: Long): String {
        val workDir = File.createTempFile("expired-cert-test-", "").apply {
            delete()
            mkdirs()
        }
        try {
            val keystore = File(workDir, "ks.p12")
            val certOut = File(workDir, "cert.pem")

            // Anchor the cert's validity window relative to "yesterday" by
            // picking a startdate offset and a total validity duration so
            // `startdate + validity` lands at `today + daysUntilExpiry`.
            // keytool refuses a zero- or negative-day validity, so for the
            // expired case we anchor far in the past (-60d) and choose a
            // short validity (e.g. 55d) so notAfter = today - 5d.
            val (startDate, validity) = if (daysUntilExpiry < 0L) {
                val absPast = -daysUntilExpiry
                // notBefore = today - (absPast + PRE_EXPIRY_MARGIN_DAYS),
                // validity = PRE_EXPIRY_MARGIN_DAYS,
                // so notAfter = today - absPast.
                val preMargin = PRE_EXPIRY_MARGIN_DAYS
                "-${absPast + preMargin}d" to preMargin.toString()
            } else {
                // Same anchor as the near-expiry sibling test.
                "-1d" to (daysUntilExpiry + 1L).coerceAtLeast(1L).toString()
            }

            runKeytool(
                "-genkeypair", "-alias", "stub",
                "-keyalg", "RSA", "-keysize", "2048",
                "-sigalg", "SHA256withRSA",
                "-dname", "CN=expired-cert-renewal-test",
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

        /**
         * Days of validity to give the keytool-minted stub cert when we want
         * it to be *already expired*. Anchors notBefore far enough in the
         * past that the cert's full `validity` window elapses before
         * "today - daysUntilExpiry", keeping keytool happy (it rejects
         * zero/negative validity). Value is intentionally short — the worker
         * only reads `notAfter`, so we keep the window as small as possible
         * to stay anchored close to "now" for future-proofing against any
         * further logic that samples the cert's notBefore.
         */
        const val PRE_EXPIRY_MARGIN_DAYS = 1L
    }
}

// ------------------------------------------------------------------
// Test doubles — intentionally local to this file. Mirror the pattern in
// `CertRenewalWorkerSchedulingTest` rather than sharing a common file,
// because each test file calibrates defaults (e.g. Firebase result vs.
// mTLS result) for its own scenarios.
// ------------------------------------------------------------------

private class ExpiredCertFakeRenewer(
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

private class ExpiredCertFakeAuthProvider : RenewalAuthProvider {
    override suspend fun signCsr(csrDer: ByteArray): String? = "stub-signature"
    override suspend fun acquireFirebaseCredentials(csrDer: ByteArray): FirebaseCredentials? =
        FirebaseCredentials(token = "stub-token", signature = "stub-signature")
}
