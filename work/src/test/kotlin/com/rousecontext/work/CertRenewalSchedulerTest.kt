package com.rousecontext.work

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.rousecontext.tunnel.CertificateStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Unit tests for [CertRenewalScheduler.enqueueImmediateIfExpiring] (issue #289).
 *
 * The periodic + delayed paths are already covered by the scheduling
 * integration test in `:app`. This suite focuses on the new app-start
 * immediate-renewal decision: expired / near-expiry enqueues, far-from-expiry
 * does not, and the unique-work slot is distinct from the periodic worker's.
 */
@RunWith(RobolectricTestRunner::class)
class CertRenewalSchedulerTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var workManager: WorkManager

    @Before
    fun setUp() {
        val config = Configuration.Builder()
            .setExecutor(SynchronousExecutor())
            .setTaskExecutor(SynchronousExecutor())
            .setMinimumLoggingLevel(android.util.Log.DEBUG)
            .build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, config)
        workManager = WorkManager.getInstance(context)
    }

    @After
    fun tearDown() {
        workManager.cancelAllWork().result.get()
    }

    @Test
    fun `no stored cert - does not enqueue immediate work`() = runBlocking {
        val store = SchedulerFakeCertificateStore(expiry = null)

        val enqueued = CertRenewalScheduler.enqueueImmediateIfExpiring(context, store)

        assertFalse("No cert = nothing to renew", enqueued)
        val infos = workManager
            .getWorkInfosForUniqueWork(CertRenewalScheduler.WORK_NAME_IMMEDIATE)
            .get()
        assertTrue("No immediate work should exist", infos.isEmpty())
    }

    @Test
    fun `cert far from expiry - does not enqueue immediate work`() = runBlocking {
        val now = 1_000_000L
        val store = SchedulerFakeCertificateStore(expiry = now + 60L * MS_PER_DAY)

        val enqueued = CertRenewalScheduler.enqueueImmediateIfExpiring(
            context,
            store,
            now = now
        )

        assertFalse(enqueued)
        val infos = workManager
            .getWorkInfosForUniqueWork(CertRenewalScheduler.WORK_NAME_IMMEDIATE)
            .get()
        assertTrue(
            "Far-from-expiry cert must not trigger immediate renewal",
            infos.isEmpty()
        )
    }

    @Test
    fun `cert inside renewal window - enqueues immediate one-time work`() = runBlocking {
        val now = 1_000_000L
        val store = SchedulerFakeCertificateStore(expiry = now + 3L * MS_PER_DAY)

        val enqueued = CertRenewalScheduler.enqueueImmediateIfExpiring(
            context,
            store,
            now = now
        )

        assertTrue("Near-expiry cert must enqueue immediate renewal", enqueued)
        val infos = workManager
            .getWorkInfosForUniqueWork(CertRenewalScheduler.WORK_NAME_IMMEDIATE)
            .get()
        assertEquals(1, infos.size)
        assertEquals(WorkInfo.State.ENQUEUED, infos.single().state)
    }

    @Test
    fun `expired cert - enqueues immediate one-time work`() = runBlocking {
        val now = 1_000_000_000L
        val store = SchedulerFakeCertificateStore(expiry = now - MS_PER_DAY)

        val enqueued = CertRenewalScheduler.enqueueImmediateIfExpiring(
            context,
            store,
            now = now
        )

        assertTrue("Expired cert must enqueue immediate renewal", enqueued)
        val infos = workManager
            .getWorkInfosForUniqueWork(CertRenewalScheduler.WORK_NAME_IMMEDIATE)
            .get()
        assertEquals(1, infos.size)
    }

    @Test
    fun `immediate slot is distinct from periodic slot`() = runBlocking {
        val now = 1_000_000L
        val store = SchedulerFakeCertificateStore(expiry = now - MS_PER_DAY)

        CertRenewalScheduler.enqueuePeriodic(context)
        CertRenewalScheduler.enqueueImmediateIfExpiring(context, store, now = now)

        val periodic = workManager
            .getWorkInfosForUniqueWork(CertRenewalWorker.WORK_NAME)
            .get()
        val immediate = workManager
            .getWorkInfosForUniqueWork(CertRenewalScheduler.WORK_NAME_IMMEDIATE)
            .get()

        assertEquals(
            "Periodic slot must still have exactly the periodic worker — " +
                "immediate enqueue must NOT clobber it",
            1,
            periodic.size
        )
        assertEquals(
            "Immediate slot must have the one-time worker",
            1,
            immediate.size
        )
    }

    @Test
    fun `re-enqueue while previous immediate run is pending - keeps the existing request`() =
        runBlocking {
            val now = 1_000_000L
            val store = SchedulerFakeCertificateStore(expiry = now - MS_PER_DAY)

            CertRenewalScheduler.enqueueImmediateIfExpiring(context, store, now = now)
            val firstId = workManager
                .getWorkInfosForUniqueWork(CertRenewalScheduler.WORK_NAME_IMMEDIATE)
                .get()
                .single()
                .id

            // Simulate a second app start while the first immediate request is
            // still pending (e.g. the user restarts before WorkManager gets a
            // chance to run the first). ExistingWorkPolicy.KEEP should preserve
            // the existing request rather than replacing it — otherwise run
            // attempts would reset on every restart.
            CertRenewalScheduler.enqueueImmediateIfExpiring(context, store, now = now)
            val secondId = workManager
                .getWorkInfosForUniqueWork(CertRenewalScheduler.WORK_NAME_IMMEDIATE)
                .get()
                .single()
                .id

            assertEquals(
                "KEEP policy must preserve the original work id across re-enqueue",
                firstId,
                secondId
            )
        }

    @Test
    fun `enqueueOneShot adds a one-time request to the one-shot slot`() = runBlocking {
        CertRenewalScheduler.enqueueOneShot(context)

        val infos = workManager
            .getWorkInfosForUniqueWork(CertRenewalScheduler.WORK_NAME_ONE_SHOT)
            .get()
        assertEquals(1, infos.size)
        assertEquals(WorkInfo.State.ENQUEUED, infos.single().state)
    }

    @Test
    fun `enqueueOneShot REPLACE collapses repeated taps to one pending request`() = runBlocking {
        CertRenewalScheduler.enqueueOneShot(context)
        val firstId = workManager
            .getWorkInfosForUniqueWork(CertRenewalScheduler.WORK_NAME_ONE_SHOT)
            .get()
            .single()
            .id

        // User spams the "Renew cert now" button. REPLACE means the second request
        // supersedes the first rather than queueing a backlog.
        CertRenewalScheduler.enqueueOneShot(context)
        val infos = workManager
            .getWorkInfosForUniqueWork(CertRenewalScheduler.WORK_NAME_ONE_SHOT)
            .get()

        assertEquals(
            "REPLACE must leave exactly one request in the slot after a re-enqueue",
            1,
            infos.size
        )
        assertTrue(
            "REPLACE must produce a new work id, not preserve the original",
            firstId != infos.single().id
        )
    }

    @Test
    fun `enqueueOneShot slot is distinct from periodic and immediate slots`() = runBlocking {
        val now = 1_000_000L
        val store = SchedulerFakeCertificateStore(expiry = now - MS_PER_DAY)

        CertRenewalScheduler.enqueuePeriodic(context)
        CertRenewalScheduler.enqueueImmediateIfExpiring(context, store, now = now)
        CertRenewalScheduler.enqueueOneShot(context)

        val periodic = workManager
            .getWorkInfosForUniqueWork(CertRenewalWorker.WORK_NAME)
            .get()
        val immediate = workManager
            .getWorkInfosForUniqueWork(CertRenewalScheduler.WORK_NAME_IMMEDIATE)
            .get()
        val oneShot = workManager
            .getWorkInfosForUniqueWork(CertRenewalScheduler.WORK_NAME_ONE_SHOT)
            .get()

        assertEquals(1, periodic.size)
        assertEquals(1, immediate.size)
        assertEquals(1, oneShot.size)
    }

    private companion object {
        const val MS_PER_DAY = 24L * 60L * 60L * 1000L
    }
}

private class SchedulerFakeCertificateStore(private val expiry: Long?) : CertificateStore {
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
    override suspend fun clear() = Unit
    override suspend fun clearCertificates() = Unit
}
