package com.rousecontext.app.ui.navigation.destinations

import com.rousecontext.app.auth.DeviceCredentialProvider
import com.rousecontext.tunnel.CertProvisioningFlow
import com.rousecontext.tunnel.CertificateStore
import com.rousecontext.tunnel.CsrGenerator
import com.rousecontext.tunnel.DeviceCredential
import com.rousecontext.tunnel.DeviceKeyManager
import com.rousecontext.tunnel.RelayApiClient
import java.io.IOException
import java.security.KeyPair
import java.security.KeyPairGenerator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Issue #662: `AddIntegrationDestination` kicks off cert provisioning with a
 * bare `catch (_: Exception)`. That catch was written for a *provisioning
 * failure* ("best-effort; integrationSetup will retry") but it also swallows
 * coroutine cancellation, undoing the guard #660 added one frame below in
 * [CertProvisioningFlow.execute] -- the same shape #616 found on #615.
 *
 * Both directions are pinned here, because the careless fix (widening the
 * rethrow) breaks the second one:
 *   - cancellation while `execute` is suspended must PROPAGATE;
 *   - an ordinary provisioning failure must stay best-effort and SILENT.
 */
class AddIntegrationProvisioningTest {

    // --- direction 1: cancellation propagates -------------------------------

    @Test
    fun `cancellation during cert provisioning propagates out of the destination`() = runBlocking {
        val suspended = CompletableDeferred<Unit>()
        val outcome = CompletableDeferred<String>()
        // getCertificate() is the first suspend call inside
        // CertProvisioningFlow.execute; parking there puts the cancellation
        // squarely inside the call the destination wraps.
        val flow = certProvisioningFlow(
            ParkingCertStore(parked = suspended, release = CompletableDeferred())
        )

        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                provisionCertsInBackground(StubCredentialProvider(CREDENTIAL), flow)
                outcome.complete("returned normally (cancellation swallowed)")
            } catch (e: CancellationException) {
                outcome.complete("threw CancellationException")
                throw e
            }
        }

        suspended.await()
        job.cancel()
        job.join()

        assertEquals("threw CancellationException", outcome.await())
        coroutineContext.cancelChildren()
    }

    // --- direction 2: ordinary failure stays best-effort --------------------

    @Test
    fun `provisioning failure is swallowed`() = runBlocking {
        val flow = certProvisioningFlow(ThrowingCertStore(RuntimeException("relay exploded")))
        // Must not throw: the destination has no error surface for this.
        provisionCertsInBackground(StubCredentialProvider(CREDENTIAL), flow)
    }

    @Test
    fun `credential provider failure is swallowed`() = runBlocking {
        val flow = certProvisioningFlow(ThrowingCertStore(RuntimeException("unreached")))
        provisionCertsInBackground(FailingCredentialProvider(), flow)
    }

    @Test
    fun `null credential skips provisioning`() = runBlocking {
        val store = ThrowingCertStore(RuntimeException("must not be reached"))
        provisionCertsInBackground(StubCredentialProvider(null), certProvisioningFlow(store))
        assertEquals(0, store.reads)
    }

    // --- fixtures ------------------------------------------------------------

    private fun certProvisioningFlow(store: CertificateStore) = CertProvisioningFlow(
        csrGenerator = CsrGenerator(),
        relayApiClient = RelayApiClient(baseUrl = "http://localhost:1"),
        certificateStore = store,
        deviceKeyManager = StubDeviceKeyManager()
    )

    private class StubCredentialProvider(private val credential: DeviceCredential?) :
        DeviceCredentialProvider {
        override suspend fun forRegistration(): DeviceCredential? = credential
        override suspend fun forProvisioning(): DeviceCredential? = credential
    }

    private class FailingCredentialProvider : DeviceCredentialProvider {
        override suspend fun forRegistration(): DeviceCredential? =
            throw IOException("no credential")

        override suspend fun forProvisioning(): DeviceCredential? =
            throw IOException("no credential")
    }

    private class StubDeviceKeyManager : DeviceKeyManager {
        override fun getOrCreateKeyPair(): KeyPair = KEY_PAIR
    }

    /** Parks forever in [getCertificate] so the test can cancel mid-`execute`. */
    private class ParkingCertStore(
        private val parked: CompletableDeferred<Unit>,
        private val release: CompletableDeferred<Unit>
    ) : NoopCertStore() {
        override suspend fun getCertificate(): String? {
            parked.complete(Unit)
            release.await()
            return null
        }
    }

    /** Fails the very first read, exercising the ordinary-failure path. */
    private class ThrowingCertStore(private val cause: RuntimeException) : NoopCertStore() {
        var reads = 0
            private set

        override suspend fun getCertificate(): String? {
            reads++
            throw cause
        }
    }

    private companion object {
        val KEY_PAIR: KeyPair = KeyPairGenerator.getInstance("EC")
            .apply { initialize(256) }
            .generateKeyPair()
        val CREDENTIAL: DeviceCredential = DeviceCredential.Firebase("test-id-token")
    }
}

private open class NoopCertStore : CertificateStore {
    override suspend fun storeCertificate(pemChain: String) = Unit
    override suspend fun getCertificate(): String? = null
    override suspend fun storeClientCertificate(pemChain: String) = Unit
    override suspend fun getClientCertificate(): String? = null
    override suspend fun storeRelayCaCert(pem: String) = Unit
    override suspend fun getRelayCaCert(): String? = null
    override suspend fun storeSubdomain(subdomain: String) = Unit
    override suspend fun getSubdomain(): String? = "device"
    override suspend fun storeIntegrationSecrets(secrets: Map<String, String>) = Unit
    override suspend fun getIntegrationSecrets(): Map<String, String>? = null
    override suspend fun getCertChain(): List<ByteArray>? = null
    override suspend fun getPrivateKeyBytes(): ByteArray? = null
    override suspend fun storeCertChain(chain: List<ByteArray>) = Unit
    override suspend fun getCertExpiry(): Long? = null
    override suspend fun getKnownFingerprints(): Set<String> = emptySet()
    override suspend fun storeFingerprint(fingerprint: String) = Unit
    override suspend fun hasFingerprintBootstrapMarker(): Boolean = false
    override suspend fun writeFingerprintBootstrapMarker() = Unit
    override suspend fun clear() = Unit
    override suspend fun clearCertificates() = Unit
}
