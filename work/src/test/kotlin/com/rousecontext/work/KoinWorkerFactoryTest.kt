package com.rousecontext.work

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import com.rousecontext.notifications.AndroidSecurityCheckNotifier
import com.rousecontext.notifications.SecurityCheckNotifier
import com.rousecontext.tunnel.CertificateStore
import com.rousecontext.tunnel.RenewalResult
import com.rousecontext.tunnel.SecurityCheckResult
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.core.qualifier.named
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class KoinWorkerFactoryTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        stopKoin()
        startKoin {
            modules(
                module {
                    single<SecurityCheckSource>(named("selfCert")) {
                        StubSource(SecurityCheckResult.Verified)
                    }
                    single<SecurityCheckSource>(named("ctLog")) {
                        StubSource(SecurityCheckResult.Verified)
                    }
                    single<SecurityCheckNotifier> { AndroidSecurityCheckNotifier(context) }
                    single<CertRenewer> { StubRenewer() }
                    single<CertificateStore> { StubCertStore() }
                    single<RenewalAuthProvider> { StubAuthProvider() }
                    single<String>(named(CertRenewalWorker.KOIN_BASE_DOMAIN_NAME)) {
                        "rousecontext.test"
                    }
                }
            )
        }
    }

    @After
    fun tearDown() {
        stopKoin()
    }

    @Test
    fun `factory returns SecurityCheckWorker with injected lateinit fields`() = runBlocking {
        val factory = KoinWorkerFactory(GlobalContext.get())

        val worker = factory.createWorker(
            context,
            SecurityCheckWorker::class.java.name,
            stubParams()
        )

        assertNotNull("factory should produce a worker", worker)
        val securityWorker = worker as SecurityCheckWorker

        // Accessing the lateinit fields must not throw. If injection didn't happen,
        // this would throw UninitializedPropertyAccessException.
        assertEquals(
            SecurityCheckResult.Verified,
            securityWorker.selfCertVerifier.check()
        )
        assertEquals(
            SecurityCheckResult.Verified,
            securityWorker.ctLogMonitor.check()
        )
    }

    @Test
    fun `factory returns CertRenewalWorker`() {
        val factory = KoinWorkerFactory(GlobalContext.get())

        val worker = factory.createWorker(
            context,
            CertRenewalWorker::class.java.name,
            stubParams()
        )

        assertNotNull("factory should produce a CertRenewalWorker", worker)
        assert(worker is CertRenewalWorker)
    }

    @Test
    fun `factory returns null for unknown class name`() {
        val factory = KoinWorkerFactory(GlobalContext.get())

        val worker = factory.createWorker(
            context,
            "com.example.UnknownWorker",
            stubParams()
        )

        assertNull(worker)
    }

    /**
     * Build a [WorkerParameters] instance by constructing a throwaway SecurityCheckWorker
     * via [TestListenableWorkerBuilder] and extracting its params. Reflection is used
     * because WorkManager doesn't expose a public constructor for [WorkerParameters].
     */
    private fun stubParams(): WorkerParameters {
        val stub = TestListenableWorkerBuilder<SecurityCheckWorker>(context).build()
        val field = androidx.work.ListenableWorker::class.java
            .getDeclaredField("mWorkerParams")
        field.isAccessible = true
        return field.get(stub) as WorkerParameters
    }
}

private class StubSource(private val result: SecurityCheckResult) : SecurityCheckSource {
    override suspend fun check(): SecurityCheckResult = result
}

private class StubRenewer : CertRenewer {
    override suspend fun renewWithMtls(
        authProvider: RenewalAuthProvider,
        baseDomain: String
    ): RenewalResult = RenewalResult.Success
    override suspend fun renewWithFirebase(
        authProvider: RenewalAuthProvider,
        baseDomain: String
    ): RenewalResult = RenewalResult.Success
}

private class StubAuthProvider : RenewalAuthProvider {
    override suspend fun signCsr(csrDer: ByteArray): String? = null
    override suspend fun acquireFirebaseCredentials(csrDer: ByteArray): FirebaseCredentials? = null
}

private class StubCertStore : CertificateStore {
    override suspend fun getCertExpiry(): Long? = null
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
    override suspend fun storePrivateKey(pemKey: String) = Unit
    override suspend fun getPrivateKey(): String? = null
    override suspend fun clear() = Unit
    override suspend fun clearCertificates() = Unit
}
