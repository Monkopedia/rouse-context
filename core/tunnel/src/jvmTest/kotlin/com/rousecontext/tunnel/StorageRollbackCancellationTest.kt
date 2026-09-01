package com.rousecontext.tunnel

import java.io.IOException
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking

/**
 * What the two provisioning flows do with a **cancellation** raised while they
 * are persisting freshly-issued state (#646).
 *
 * Both wrap a run of `suspend` [CertificateStore] writes in a broad
 * `catch (Exception)` whose handler performs a **rollback**:
 *
 *  - [OnboardingFlow] calls `certificateStore.clear()` and returns
 *    [OnboardingResult.StorageFailed];
 *  - [CertProvisioningFlow] calls `certificateStore.clearCertificates()` and
 *    returns [CertProvisioningResult.StorageFailed].
 *
 * A cancelled write is not a failed write. The rollback is the consequence that
 * actually lands here: it is real destructive work performed *after*
 * cancellation, which `.claude/rules/coroutines.md` forbids outright, and in
 * [OnboardingFlow]'s case `clear()` drops the subdomain the relay has already
 * assigned -- the device is registered upstream and blank locally.
 *
 * Each flow is pinned in both directions, and each cancellation test asserts
 * the rollback did **not** run, not merely that something was thrown: the
 * rollback is the damage, so its absence is the property worth holding.
 *
 * As elsewhere in this sweep the scope is never cancelled by the test -- the
 * store raises cancellation directly, so nothing about the assertion depends on
 * the test's own teardown.
 */
class StorageRollbackCancellationTest {

    private val mockServer = MockRelayServer()

    @BeforeTest
    fun setUp() = mockServer.start()

    @AfterTest
    fun tearDown() = mockServer.stop()

    @Test
    fun `cancelling an onboarding write propagates and does not roll back`() = runBlocking {
        val store = RollbackRecordingStore(
            onIdentityWrite = CancellationException("onboarding scope torn down")
        )

        val thrown = assertFailsWith<Throwable> { onboardingOver(store).execute(TOKEN, FCM) }

        assertTrue(
            thrown is CancellationException,
            "a cancelled onboarding write must propagate cancellation, got " +
                "${thrown.javaClass.name}: ${thrown.message}"
        )
        assertEquals(
            0,
            store.clearCalls,
            "cancellation must not trigger the onboarding rollback: a cancelled write is " +
                "not a failed write, and clear() drops the subdomain the relay already assigned"
        )
    }

    @Test
    fun `an IO failure during an onboarding write still rolls back and reports it`(): Unit =
        runBlocking {
            val store = RollbackRecordingStore(onIdentityWrite = IOException("Disk full"))

            val result = onboardingOver(store).execute(TOKEN, FCM)

            assertIs<OnboardingResult.StorageFailed>(result)
            assertEquals(
                1,
                store.clearCalls,
                "the ordinary failure path must still roll back"
            )
        }

    @Test
    fun `cancelling a cert-provisioning write propagates and does not roll back`() = runBlocking {
        val store = RollbackRecordingStore(
            onCertWrite = CancellationException("provisioning scope torn down")
        )
        store.storeSubdomain("abc123")

        val thrown = assertFailsWith<Throwable> { provisioningOver(store).execute(TOKEN) }

        assertTrue(
            thrown is CancellationException,
            "a cancelled cert write must propagate cancellation, got " +
                "${thrown.javaClass.name}: ${thrown.message}"
        )
        assertEquals(
            0,
            store.clearCertificatesCalls,
            "cancellation must not trigger the cert rollback"
        )
    }

    @Test
    fun `an IO failure during a cert write still rolls back and reports it`(): Unit = runBlocking {
        val store = RollbackRecordingStore(onCertWrite = IOException("Disk full"))
        store.storeSubdomain("abc123")

        val result = provisioningOver(store).execute(TOKEN)

        assertIs<CertProvisioningResult.StorageFailed>(result)
        assertEquals(
            1,
            store.clearCertificatesCalls,
            "the ordinary failure path must still roll back"
        )
    }

    private fun onboardingOver(store: CertificateStore) = OnboardingFlow(
        relayApiClient = RelayApiClient(baseUrl = mockServer.baseUrl),
        certificateStore = store
    )

    private fun provisioningOver(store: CertificateStore) = CertProvisioningFlow(
        csrGenerator = CsrGenerator(),
        relayApiClient = RelayApiClient(baseUrl = mockServer.baseUrl),
        certificateStore = store,
        deviceKeyManager = InMemoryDeviceKeyManager()
    )

    /**
     * Store that fails a chosen family of writes and counts rollback calls.
     * Everything else is delegated to a real in-memory store so the flows reach
     * the guarded write in their normal state.
     */
    private class RollbackRecordingStore(
        private val onIdentityWrite: Throwable? = null,
        private val onCertWrite: Throwable? = null,
        private val delegate: InMemoryCertificateStore = InMemoryCertificateStore()
    ) : CertificateStore by delegate {

        var clearCalls = 0
            private set

        var clearCertificatesCalls = 0
            private set

        override suspend fun storeSubdomain(subdomain: String) {
            onIdentityWrite?.let { throw it }
            delegate.storeSubdomain(subdomain)
        }

        override suspend fun storeIntegrationSecrets(secrets: Map<String, String>) {
            onIdentityWrite?.let { throw it }
            delegate.storeIntegrationSecrets(secrets)
        }

        override suspend fun storeCertificate(pemChain: String) {
            onCertWrite?.let { throw it }
            delegate.storeCertificate(pemChain)
        }

        override suspend fun storeClientCertificate(pemChain: String) {
            onCertWrite?.let { throw it }
            delegate.storeClientCertificate(pemChain)
        }

        override suspend fun storeRelayCaCert(pem: String) {
            onCertWrite?.let { throw it }
            delegate.storeRelayCaCert(pem)
        }

        override suspend fun clear() {
            clearCalls++
            delegate.clear()
        }

        override suspend fun clearCertificates() {
            clearCertificatesCalls++
            delegate.clearCertificates()
        }
    }

    private companion object {
        const val TOKEN = "fake-firebase-id-token"
        const val FCM = "fake-fcm-registration-token"
    }
}
