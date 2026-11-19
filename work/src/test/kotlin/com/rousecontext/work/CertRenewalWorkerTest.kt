package com.rousecontext.work

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.ListenableWorker.Result
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import com.rousecontext.tunnel.CertificateStore
import com.rousecontext.tunnel.RenewalResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

private const val MS_PER_DAY = 24L * 60L * 60L * 1000L
private const val TEST_BASE_DOMAIN = "rousecontext.test"

@RunWith(RobolectricTestRunner::class)
class CertRenewalWorkerTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val renewalPrefs = CertRenewalPreferences(context)

    @Test
    fun `cert valid and not in renewal window is skipped`() = runBlocking {
        val now = 1_000_000L
        val store = FakeCertificateStore(expiry = now + 60 * MS_PER_DAY)
        val renewer = FakeRenewer()
        val auth = RecordingAuthProvider(null)
        var rescheduleDelay: Long? = null
        val worker = buildWorker(
            renewer = renewer,
            store = store,
            auth = auth,
            clock = { now },
            onReschedule = { rescheduleDelay = it }
        )

        val result = worker.doWork()

        assertEquals(Result.success(), result)
        assertFalse("mTLS renewal should NOT be attempted", renewer.mtlsAttempted)
        assertFalse("Firebase renewal should NOT be attempted", renewer.firebaseAttempted)
        assertNull("No reschedule", rescheduleDelay)
    }

    @Test
    fun `cert inside renewal window triggers mTLS renewal`() = runBlocking {
        val now = 1_000_000L
        val store = FakeCertificateStore(expiry = now + 5 * MS_PER_DAY)
        val renewer = FakeRenewer(mtlsResult = RenewalResult.Success)
        val auth = RecordingAuthProvider(null)
        val worker = buildWorker(
            renewer = renewer,
            store = store,
            auth = auth,
            clock = { now }
        )

        val result = worker.doWork()

        assertEquals(Result.success(), result)
        assertTrue("mTLS renewal should be attempted", renewer.mtlsAttempted)
        assertEquals(TEST_BASE_DOMAIN, renewer.mtlsDomain)
        assertFalse("Firebase renewal should NOT be attempted", renewer.firebaseAttempted)
    }

    @Test
    fun `expired cert triggers Firebase renewal when auth provider supplies credentials`() =
        runBlocking {
            val now = 1_000_000L
            val store = FakeCertificateStore(expiry = now - MS_PER_DAY)
            val renewer = FakeRenewer(firebaseResult = RenewalResult.Success)
            val auth = RecordingAuthProvider(
                FirebaseCredentials(token = "tok-1", signature = "sig-1")
            )
            val worker = buildWorker(
                renewer = renewer,
                store = store,
                auth = auth,
                clock = { now }
            )

            val result = worker.doWork()

            assertEquals(Result.success(), result)
            assertFalse("mTLS renewal should NOT be attempted", renewer.mtlsAttempted)
            assertTrue("Firebase renewal should be attempted", renewer.firebaseAttempted)
            assertEquals("tok-1", renewer.firebaseToken)
            assertEquals("sig-1", renewer.firebaseSig)
            assertEquals(TEST_BASE_DOMAIN, renewer.firebaseDomain)
        }

    @Test
    fun `rate-limited result reschedules with retryAfterSeconds and returns success`() =
        runBlocking {
            val now = 1_000_000L
            val store = FakeCertificateStore(expiry = now + 5 * MS_PER_DAY)
            val renewer = FakeRenewer(
                mtlsResult = RenewalResult.RateLimited(retryAfterSeconds = 90L)
            )
            val auth = RecordingAuthProvider(null)
            var rescheduleDelay: Long? = null
            val worker = buildWorker(
                renewer = renewer,
                store = store,
                auth = auth,
                clock = { now },
                onReschedule = { rescheduleDelay = it }
            )

            val result = worker.doWork()

            // Issue #831: the delayed one-time work is the only retry. Returning
            // retry() here would fire WorkManager's 15-minute backoff inside the
            // server's retry_after window.
            assertEquals(Result.success(), result)
            assertTrue(renewer.mtlsAttempted)
            assertEquals(90L, rescheduleDelay)
        }

    @Test
    fun `rate-limited run enqueues exactly one delayed renewal at retry_after`() = runBlocking {
        val workManager = initTestWorkManager()
        val before = System.currentTimeMillis()

        val result = runRateLimited(retryAfterSeconds = 90L)

        assertEquals(
            "Rate-limited run must not ask WorkManager for a backoff retry",
            Result.success(),
            result
        )
        val pending = pendingDelayed(workManager)
        assertEquals("Exactly one delayed renewal must be pending", 1, pending.size)
        val scheduledAt = pending.single().nextScheduleTimeMillis
        assertTrue(
            "Delayed renewal must fire at ~retry_after (was ${scheduledAt - before}ms)",
            scheduledAt in (before + 90_000L)..(System.currentTimeMillis() + 90_000L)
        )
    }

    @Test
    fun `second rate-limited run does not stack or postpone the pending delayed renewal`() =
        runBlocking {
            val workManager = initTestWorkManager()
            val before = System.currentTimeMillis()

            runRateLimited(retryAfterSeconds = 90L)
            val first = pendingDelayed(workManager).single()
            val second = runRateLimited(retryAfterSeconds = 3_600L)

            assertEquals(Result.success(), second)
            val pending = pendingDelayed(workManager)
            assertEquals("Delayed renewals must not stack", 1, pending.size)
            assertEquals(
                "The earlier, correctly timed delayed renewal must be kept",
                first.id,
                pending.single().id
            )
            assertTrue(
                "Delayed renewal must not be pushed past the first retry_after",
                pending.single().nextScheduleTimeMillis <=
                    System.currentTimeMillis() + 90_000L &&
                    pending.single().nextScheduleTimeMillis >= before + 90_000L
            )
        }

    @Test
    fun `rate-limited delayed run reschedules itself at the new retry_after`() = runBlocking {
        val workManager = initTestWorkManager()
        CertRenewalScheduler.enqueueDelayed(context, delaySeconds = 10L)
        val stale = pendingDelayed(workManager).single()
        val before = System.currentTimeMillis()

        val result = runRateLimited(
            retryAfterSeconds = 600L,
            tags = setOf(CertRenewalScheduler.TAG_DELAYED)
        )

        assertEquals(Result.success(), result)
        val pending = pendingDelayed(workManager)
        assertEquals("Delayed renewals must not stack", 1, pending.size)
        assertTrue("Delayed run must replace its own slot", pending.single().id != stale.id)
        assertTrue(
            "Replacement must honour the new retry_after",
            pending.single().nextScheduleTimeMillis >= before + 600_000L
        )
    }

    @Test
    fun `no cert at all is a no-op`() = runBlocking {
        val store = FakeCertificateStore(expiry = null)
        val renewer = FakeRenewer()
        val auth = RecordingAuthProvider(null)
        val worker = buildWorker(
            renewer = renewer,
            store = store,
            auth = auth,
            clock = { 1_000_000L }
        )

        val result = worker.doWork()

        assertEquals(Result.success(), result)
        assertFalse(renewer.mtlsAttempted)
        assertFalse(renewer.firebaseAttempted)
    }

    @Test
    fun `expired cert with no Firebase credentials returns retry`() = runBlocking {
        val now = 1_000_000L
        val store = FakeCertificateStore(expiry = now - MS_PER_DAY)
        val renewer = FakeRenewer()
        val auth = RecordingAuthProvider(null)
        val worker = buildWorker(
            renewer = renewer,
            store = store,
            auth = auth,
            clock = { now }
        )

        val result = worker.doWork()

        assertEquals(Result.retry(), result)
        assertFalse(renewer.mtlsAttempted)
        // Renewer is invoked (it asks the provider for creds internally), but returns
        // FirebaseAuthUnavailable which we handle as a retry with EXPIRED_NO_AUTH outcome.
        assertTrue(renewer.firebaseAttempted)
        assertEquals(
            CertRenewalWorker.Outcome.EXPIRED_NO_AUTH.name,
            readLastOutcome()
        )
    }

    @Test
    fun `network error result returns retry without reschedule`() = runBlocking {
        val now = 1_000_000L
        val store = FakeCertificateStore(expiry = now + 5 * MS_PER_DAY)
        val renewer = FakeRenewer(
            mtlsResult = RenewalResult.NetworkError(RuntimeException("boom"))
        )
        val auth = RecordingAuthProvider(null)
        var rescheduleDelay: Long? = null
        val worker = buildWorker(
            renewer = renewer,
            store = store,
            auth = auth,
            clock = { now },
            onReschedule = { rescheduleDelay = it }
        )

        val result = worker.doWork()

        assertEquals(Result.retry(), result)
        assertNull(rescheduleDelay)
    }

    @Test
    fun `relay error result returns retry and does not record terminal outcome`() = runBlocking {
        val now = 1_000_000L
        val store = FakeCertificateStore(expiry = now + 5 * MS_PER_DAY)
        val renewer = FakeRenewer(
            mtlsResult = RenewalResult.RelayError(statusCode = 502, message = "bad gateway")
        )
        val auth = RecordingAuthProvider(null)
        var rescheduleDelay: Long? = null
        val worker = buildWorker(
            renewer = renewer,
            store = store,
            auth = auth,
            clock = { now },
            onReschedule = { rescheduleDelay = it }
        )

        val result = worker.doWork()

        assertEquals(Result.retry(), result)
        assertTrue("mTLS renewal should be attempted", renewer.mtlsAttempted)
        assertNull("RelayError should not reschedule with a delay", rescheduleDelay)
        val outcome = readLastOutcome()
        assertEquals(CertRenewalWorker.Outcome.RELAY_ERROR.name, outcome)
        assertFalse(
            "RelayError is retryable, not a terminal failure",
            outcome == CertRenewalWorker.Outcome.KEY_GEN_FAILED.name ||
                outcome == CertRenewalWorker.Outcome.CN_MISMATCH.name
        )
    }

    @Test
    fun `key generation failure returns success and records terminal outcome`() = runBlocking {
        val now = 1_000_000L
        val store = FakeCertificateStore(expiry = now + 5 * MS_PER_DAY)
        val renewer = FakeRenewer(
            mtlsResult = RenewalResult.KeyGenerationFailed(RuntimeException("keystore boom"))
        )
        val auth = RecordingAuthProvider(null)
        val worker = buildWorker(
            renewer = renewer,
            store = store,
            auth = auth,
            clock = { now }
        )

        val result = worker.doWork()

        assertEquals(Result.success(), result)
        assertTrue("mTLS renewal should be attempted", renewer.mtlsAttempted)
        assertEquals(
            CertRenewalWorker.Outcome.KEY_GEN_FAILED.name,
            readLastOutcome()
        )
    }

    @Test
    fun `CN mismatch returns success and records terminal outcome`() = runBlocking {
        val now = 1_000_000L
        val store = FakeCertificateStore(expiry = now + 5 * MS_PER_DAY)
        val renewer = FakeRenewer(
            mtlsResult = RenewalResult.CnMismatch(
                expected = "abc.rousecontext.test",
                actual = "xyz.rousecontext.test"
            )
        )
        val auth = RecordingAuthProvider(null)
        val worker = buildWorker(
            renewer = renewer,
            store = store,
            auth = auth,
            clock = { now }
        )

        val result = worker.doWork()

        assertEquals(Result.success(), result)
        assertTrue("mTLS renewal should be attempted", renewer.mtlsAttempted)
        assertEquals(
            CertRenewalWorker.Outcome.CN_MISMATCH.name,
            readLastOutcome()
        )
    }

    private fun readLastOutcome(): String? = kotlinx.coroutines.runBlocking {
        renewalPrefs.lastOutcome()
    }

    private fun initTestWorkManager(): WorkManager {
        val config = Configuration.Builder()
            .setExecutor(SynchronousExecutor())
            .setTaskExecutor(SynchronousExecutor())
            .build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, config)
        return WorkManager.getInstance(context)
    }

    private fun pendingDelayed(workManager: WorkManager): List<WorkInfo> = workManager
        .getWorkInfosForUniqueWork(CertRenewalWorker.WORK_NAME_DELAYED)
        .get()
        .filter { it.state == WorkInfo.State.ENQUEUED }

    /** Runs a worker wired to the real [CertRenewalScheduler] against a rate-limited renewer. */
    private suspend fun runRateLimited(
        retryAfterSeconds: Long,
        tags: Set<String> = emptySet()
    ): Result {
        val now = System.currentTimeMillis()
        val worker = TestListenableWorkerBuilder<CertRenewalWorker>(context)
            .setTags(tags.toList())
            .build()
        worker.renewer = FakeRenewer(
            mtlsResult = RenewalResult.RateLimited(retryAfterSeconds = retryAfterSeconds)
        )
        worker.certificateStore = FakeCertificateStore(expiry = now + 5 * MS_PER_DAY)
        worker.authProvider = RecordingAuthProvider(null)
        worker.baseDomain = TEST_BASE_DOMAIN
        worker.clock = { now }
        worker.preferences = renewalPrefs
        return worker.doWork()
    }

    private fun buildWorker(
        renewer: CertRenewer,
        store: CertificateStore,
        auth: RenewalAuthProvider,
        clock: () -> Long,
        onReschedule: (Long) -> Unit = {}
    ): CertRenewalWorker {
        val worker = TestListenableWorkerBuilder<CertRenewalWorker>(context).build()
        worker.renewer = renewer
        worker.certificateStore = store
        worker.authProvider = auth
        worker.baseDomain = TEST_BASE_DOMAIN
        worker.clock = clock
        worker.rescheduleWithDelay = onReschedule
        worker.renewalWindowDays = CertRenewalWorker.DEFAULT_RENEWAL_WINDOW_DAYS
        worker.preferences = renewalPrefs
        return worker
    }
}

private class FakeRenewer(
    private val mtlsResult: RenewalResult = RenewalResult.Success,
    private val firebaseResult: RenewalResult = RenewalResult.Success
) : CertRenewer {

    var mtlsAttempted = false
        private set
    var mtlsDomain: String? = null
        private set
    var mtlsSignature: String? = null
        private set
    var firebaseAttempted = false
        private set
    var firebaseToken: String? = null
        private set
    var firebaseSig: String? = null
        private set
    var firebaseDomain: String? = null
        private set

    override suspend fun renewWithMtls(
        authProvider: RenewalAuthProvider,
        baseDomain: String
    ): RenewalResult {
        mtlsAttempted = true
        mtlsDomain = baseDomain
        val sig = authProvider.signCsr(FAKE_CSR_DER)
        if (sig == null) {
            return RenewalResult.FirebaseAuthUnavailable
        }
        mtlsSignature = sig
        return mtlsResult
    }

    override suspend fun renewWithFirebase(
        authProvider: RenewalAuthProvider,
        baseDomain: String
    ): RenewalResult {
        firebaseAttempted = true
        val credentials = authProvider.acquireFirebaseCredentials(FAKE_CSR_DER)
        if (credentials == null) {
            return RenewalResult.FirebaseAuthUnavailable
        }
        this.firebaseToken = credentials.token
        this.firebaseSig = credentials.signature
        this.firebaseDomain = baseDomain
        return firebaseResult
    }

    companion object {
        val FAKE_CSR_DER = byteArrayOf(0x30, 0x01, 0x00)
    }
}

private class RecordingAuthProvider(
    private val credentials: FirebaseCredentials?,
    private val mtlsSignature: String? = "mtls-sig"
) : RenewalAuthProvider {
    override suspend fun signCsr(csrDer: ByteArray): String? = mtlsSignature
    override suspend fun acquireFirebaseCredentials(csrDer: ByteArray): FirebaseCredentials? =
        credentials
}

private class FakeCertificateStore(private val expiry: Long?) : CertificateStore {
    override suspend fun getCertExpiry(): Long? = expiry
    override suspend fun getCertChain(): List<ByteArray>? = null
    override suspend fun getPrivateKeyBytes(): ByteArray? = null
    override suspend fun storeCertChain(chain: List<ByteArray>) = Unit
    override suspend fun getKnownFingerprints(): Set<String> = emptySet()
    override suspend fun storeFingerprint(fingerprint: String) = Unit
    override suspend fun hasFingerprintBootstrapMarker(): Boolean = false
    override suspend fun writeFingerprintBootstrapMarker() = Unit
    override suspend fun storeCertificate(pemChain: String) = Unit
    override suspend fun getCertificate(): String? = null
    override suspend fun storeClientCertificate(pemChain: String) = Unit
    override suspend fun getClientCertificate(): String? = null
    override suspend fun storeRelayCaCert(pem: String) = Unit
    override suspend fun getRelayCaCert(): String? = null
    override suspend fun storeSubdomain(subdomain: String) = Unit
    override suspend fun getSubdomain(): String? = null
    override suspend fun storeIntegrationSecrets(secrets: Map<String, String>) = Unit
    override suspend fun getIntegrationSecrets(): Map<String, String>? = null

    // storePrivateKey / getPrivateKey inherit the deprecated default no-op/null impls.
    override suspend fun clear() = Unit
    override suspend fun clearCertificates() = Unit
}
