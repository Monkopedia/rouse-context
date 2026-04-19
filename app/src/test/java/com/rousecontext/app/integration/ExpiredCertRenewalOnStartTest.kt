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
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner

/**
 * Integration-tier coverage for issue #278 — the "device sat idle past 90-day
 * cert expiry, then the user opens the app" flow.
 *
 * # What this test file covers (distinct from #277)
 *
 * Issue #277 ([CertRenewalWorkerSchedulingTest]) verifies WorkManager
 * *scheduling* wiring (constraints, reschedule-after-success, periodic-work
 * enqueue semantics) and the **near-expiry mTLS** branch of
 * [CertRenewalWorker.doWork]. The worker's **already-expired Firebase**
 * branch — entered only when `millisUntilExpiry <= 0` — was not exercised
 * at integration tier prior to this file.
 *
 * The distinction matters because:
 *
 * - The near-expiry branch reaches [CertRenewer.renewWithMtls]; the expired
 *   branch reaches [CertRenewer.renewWithFirebase]. These are *different*
 *   relay endpoints (`/renew` with mTLS proof-of-possession vs. `/renew`
 *   with a Firebase token + CSR signature), and the [RenewalAuthProvider]
 *   contract splits between [RenewalAuthProvider.signCsr] and
 *   [RenewalAuthProvider.acquireFirebaseCredentials] along the same line.
 *   A regression that only fires on the expired path (Firebase token
 *   acquisition bug, keystore signing bug specific to that call site) would
 *   sail past the near-expiry tests unnoticed.
 *
 * - The #277 test file overrides the on-disk cert file with a keytool-minted
 *   stub whose `notAfter` is ~10 days out. That path never drives the code
 *   the expired branch executes. Here we mint a stub whose `notAfter` is in
 *   the past, observe the worker take the Firebase path, and confirm the
 *   success/failure outcomes are persisted via [CertRenewalPreferences].
 *
 * # What this test file does NOT cover
 *
 * The issue's original scenario — "user opens the app with an expired cert
 * and renewal kicks in *immediately*" — cannot pass against current code.
 * [com.rousecontext.app.RouseApplication.onCreate] only calls
 * [CertRenewalScheduler.enqueuePeriodic], which schedules a periodic
 * worker on a 24-hour interval. There is no app-start or tunnel-connect
 * path that checks cert expiry and forces an immediate renewal run.
 *
 * This gap is tracked as a follow-up in issue #289 (filed during the
 * discovery phase of #278). The [appStartDoesNotForceImmediateRenewal]
 * scenario below is a **regression guard** documenting current behaviour
 * so #289's fix can be verified: once the app-start path learns to force
 * an immediate run on near-expiry cert, that test flips to assert the
 * *new* correct behaviour (renewal fired once at startup) and the
 * worker's Firebase-path coverage stays useful as the underlying exercise
 * of the expired-cert code branch.
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
    // Scenario 3: regression guard for gap #289 — app start does NOT force
    // an immediate renewal run today
    // ------------------------------------------------------------------

    @Test
    fun `app start only schedules periodic work and does not run renewal immediately`() =
        runBlocking {
            harness.provisionDevice()
            overwriteCertOnDisk(daysUntilExpiry = -5L)

            // This is the exact call made from `RouseApplication.onCreate` —
            // no other renewal-kickoff exists in the production startup path.
            CertRenewalScheduler.enqueuePeriodic(context)

            val wm = WorkManager.getInstance(context)
            val infos = wm.getWorkInfosForUniqueWork(CertRenewalWorker.WORK_NAME).get()
            assertEquals(
                "enqueuePeriodic must create exactly one unique work entry",
                1,
                infos.size
            )
            val info = infos.single()
            assertNotNull("periodic work must be tracked by WorkManager", info)
            assertEquals(
                "Periodic work is ENQUEUED waiting for the next period — NOT running. " +
                    "Until gap #289 is fixed, a user who opens the app with an expired " +
                    "cert waits up to `PERIODIC_INTERVAL_HOURS` (24h) for renewal to fire. " +
                    "Once #289 lands, flip this assertion to verify the immediate kick.",
                WorkInfo.State.ENQUEUED,
                info.state
            )
            assertEquals(
                "No renewer method must have fired from startup alone — the periodic " +
                    "delay is what is stranding the expired-cert user. This is the " +
                    "regression that gap #289 tracks. The [fakeRenewer] is per-test so " +
                    "its counters reset across [setUp]; the call-count assertion is the " +
                    "reliable signal that the worker did not run. (A sibling " +
                    "`CertRenewalPreferences.lastOutcome` check would be flaky — the " +
                    "`cert_renewal` DataStore file is reused across tests in the same " +
                    "JVM and already holds whatever prior scenarios wrote.)",
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
