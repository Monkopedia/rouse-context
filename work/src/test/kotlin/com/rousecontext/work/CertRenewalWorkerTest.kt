package com.rousecontext.work

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker.Result
import androidx.work.testing.TestListenableWorkerBuilder
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CertRenewalWorkerTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `cert expiring within 14 days triggers renewal`() = runBlocking {
        val now = System.currentTimeMillis()
        val expiresIn10Days = now + 10 * 24 * 60 * 60 * 1000L
        val store = FakeCertificateStore(certExpiry = expiresIn10Days)

        val worker = TestListenableWorkerBuilder<CertRenewalWorker>(context)
            .build()
        worker.certificateStore = store

        val result = worker.doWork()

        assertTrue("Renewal should have been attempted", store.renewalAttempted)
        assertEquals(Result.success(), result)
    }

    @Test
    fun `cert not expiring is no-op`() = runBlocking {
        val now = System.currentTimeMillis()
        val expiresIn30Days = now + 30 * 24 * 60 * 60 * 1000L
        val store = FakeCertificateStore(certExpiry = expiresIn30Days)

        val worker = TestListenableWorkerBuilder<CertRenewalWorker>(context)
            .build()
        worker.certificateStore = store

        val result = worker.doWork()

        assertTrue("No renewal should be attempted", !store.renewalAttempted)
        assertEquals(Result.success(), result)
    }

    @Test
    fun `no cert at all is no-op`() = runBlocking {
        val store = FakeCertificateStore(certExpiry = null)

        val worker = TestListenableWorkerBuilder<CertRenewalWorker>(context)
            .build()
        worker.certificateStore = store

        val result = worker.doWork()

        assertTrue("No renewal should be attempted when no cert exists", !store.renewalAttempted)
        assertEquals(Result.success(), result)
    }

    @Test
    fun `renewal failure returns retry`() = runBlocking {
        val now = System.currentTimeMillis()
        val expiresIn5Days = now + 5 * 24 * 60 * 60 * 1000L
        val store = FakeCertificateStore(
            certExpiry = expiresIn5Days,
            renewalShouldFail = true
        )

        val worker = TestListenableWorkerBuilder<CertRenewalWorker>(context)
            .build()
        worker.certificateStore = store

        val result = worker.doWork()

        assertTrue("Renewal should have been attempted", store.renewalAttempted)
        assertEquals(Result.retry(), result)
    }
}

class FakeCertificateStore(
    private val certExpiry: Long?,
    private val renewalShouldFail: Boolean = false
) : CertRenewalStore {

    var renewalAttempted = false
        private set

    override suspend fun getCertExpiry(): Long? = certExpiry

    override suspend fun renewCertificate() {
        renewalAttempted = true
        if (renewalShouldFail) {
            throw CertRenewalException("Rate limited")
        }
    }
}
