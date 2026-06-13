package com.rousecontext.app.integration

import com.rousecontext.app.auth.DeviceCredentialProvider
import com.rousecontext.app.auth.KeypairDeviceCredentialProvider
import com.rousecontext.app.auth.KeypairRenewalAuthProvider
import com.rousecontext.app.integration.harness.AppIntegrationTestHarness
import com.rousecontext.tunnel.CertProvisioningFlow
import com.rousecontext.tunnel.CertProvisioningResult
import com.rousecontext.tunnel.CertificateStore
import com.rousecontext.tunnel.DeviceCredential
import com.rousecontext.tunnel.OnboardingFlow
import com.rousecontext.tunnel.OnboardingResult
import com.rousecontext.tunnel.RenewalResult
import com.rousecontext.work.CertRenewalWorker
import com.rousecontext.work.CertRenewer
import com.rousecontext.work.RenewalAuthProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.qualifier.named
import org.robolectric.RobolectricTestRunner

/**
 * End-to-end keypair device-identity round trip for the `foss` flavor
 * (issue #469, part of the #460 F-Droid epic).
 *
 * This is the live coverage that #462 left open: the relay-side keypair auth
 * was covered by Rust tests and the client proof-builder by a Kotlin unit
 * test, but nothing exercised the *foss Android client talking to a running
 * relay binary with keypair auth*. It runs under the dedicated
 * `:app:fossIntegrationTest` Gradle task, which reuses `testFossDebugUnitTest`'s
 * classpath so the foss `distributionModule`
 * ([KeypairDeviceCredentialProvider] / [KeypairRenewalAuthProvider]) is the
 * flavor module live in the harness's Koin graph. Living in `app/src/testFoss`
 * keeps it off the google `integrationTest` classpath, where
 * `DeviceCredentialProvider` would resolve to the Firebase implementation.
 *
 * The scenario, all against the real `rouse-relay` subprocess:
 *
 *  1. **Register** — the foss [DeviceCredentialProvider] builds a signed
 *     [DeviceCredential.Keypair] (public key + register proof, zero Firebase);
 *     [OnboardingFlow] registers via `POST /register` keypair variant.
 *  2. **Provision** — [CertProvisioningFlow] submits the CSR via
 *     `POST /register/certs` keypair variant; the relay keys the device on the
 *     CSR's public-key thumbprint and issues the server + client certs.
 *  3. **Renew** — the production [CertRenewer] bridge drives the expired-cert
 *     path through the foss [KeypairRenewalAuthProvider], which signs a fresh
 *     `PURPOSE_RENEW` proof + the renewal CSR; the relay re-verifies the proof
 *     against the stored public key on `POST /renew` keypair Path B.
 *
 * Every hop authenticates the device cryptographically — the relay verifies
 * each proof / signature against the registered public key, so a green run
 * proves the relay accepted the foss proofs end-to-end. test-mode only stubs
 * ACME / Firestore / Firebase; the keypair verification path is the real one.
 *
 * Note on the FCM token: the relay still requires a non-empty `fcm_token` on
 * `/register` (a push-wake concern, not a keypair-auth one), while the foss
 * flavor currently ships a `NoOpFcmTokenProvider` returning `""`. A real foss
 * push token / wake endpoint lands with UnifiedPush (#463); until then this
 * test passes a placeholder so the keypair-auth round trip — the gap #469
 * closes — is exercised independently of the push transport. See the PR for
 * the surfaced friction.
 */
@RunWith(RobolectricTestRunner::class)
class KeypairRoundTripIntegrationTest {

    private val harness = AppIntegrationTestHarness()

    @Before
    fun setUp() {
        harness.start()
    }

    @After
    fun tearDown() {
        harness.stop()
    }

    @Test
    @Suppress("LongMethod") // Straight-line end-to-end script, like the sibling integration tests.
    fun `foss keypair register provision and renew round trip against the real relay`() =
        runBlocking {
            // --- The foss distributionModule must be the one live in the graph. ---
            val credentialProvider: DeviceCredentialProvider = harness.koin.get()
            val renewalAuthProvider: RenewalAuthProvider = harness.koin.get()
            assertTrue(
                "foss variant must bind KeypairDeviceCredentialProvider, got " +
                    "${credentialProvider::class.java.name}",
                credentialProvider is KeypairDeviceCredentialProvider
            )
            assertTrue(
                "foss variant must bind KeypairRenewalAuthProvider, got " +
                    "${renewalAuthProvider::class.java.name}",
                renewalAuthProvider is KeypairRenewalAuthProvider
            )

            // --- 1. Register via a keypair proof (no Firebase). ---
            val registerCredential = credentialProvider.forRegistration()
            assertNotNull("foss provider must build a registration credential", registerCredential)
            assertTrue(
                "registration credential must be a keypair proof, got: $registerCredential",
                registerCredential is DeviceCredential.Keypair
            )

            val onboardingFlow: OnboardingFlow = harness.koin.get()
            val onboardResult = onboardingFlow.execute(
                credential = registerCredential!!,
                fcmToken = PLACEHOLDER_PUSH_TOKEN
            )
            assertTrue(
                "keypair onboarding must succeed, got: $onboardResult",
                onboardResult is OnboardingResult.Success
            )

            // The keypair path registers directly: the Firebase-only
            // `/request-subdomain` reservation must never be touched.
            assertEquals(
                "keypair registration must skip the Firebase-only subdomain reservation",
                0,
                harness.fixture.requestSubdomainCalls()
            )
            assertEquals(
                "no /register/certs calls should have landed before provisioning",
                0,
                harness.fixture.registerCertsCalls()
            )

            // --- 2. Provision certs via the keypair round-2 (CSR thumbprint). ---
            val provisioningCredential = credentialProvider.forProvisioning()
            assertTrue(
                "provisioning credential must be a keypair proof, got: $provisioningCredential",
                provisioningCredential is DeviceCredential.Keypair
            )

            val certProvisioningFlow: CertProvisioningFlow = harness.koin.get()
            val certResult = certProvisioningFlow.execute(credential = provisioningCredential!!)
            assertTrue(
                "keypair cert provisioning must succeed, got: $certResult",
                certResult is CertProvisioningResult.Success
            )

            val certStore: CertificateStore = harness.koin.get()
            val initialServerCert = certStore.getCertificate()
            val initialClientCert = certStore.getClientCertificate()
            assertNotNull("server cert must be persisted after provisioning", initialServerCert)
            assertNotNull("client cert must be persisted after provisioning", initialClientCert)
            assertEquals(
                "exactly one /register/certs call should have landed",
                1,
                harness.fixture.registerCertsCalls()
            )
            assertEquals(
                "keypair provisioning must not touch /request-subdomain",
                0,
                harness.fixture.requestSubdomainCalls()
            )

            // --- 3. Renew via the keypair proof (expired-cert Path B). ---
            // Drive the production CertRenewer bridge so the real foss
            // KeypairRenewalAuthProvider signs both the renewal CSR and a fresh
            // PURPOSE_RENEW proof; the relay re-verifies the proof against the
            // stored public key. renewWithFirebase() is the bridge's
            // expired-cert entry point (it internally prefers the keypair
            // provider and falls back to Firebase — which the foss provider
            // declines), so it exercises the keypair branch.
            val renewer: CertRenewer = harness.koin.get()
            val baseDomain: String =
                harness.koin.get(named(CertRenewalWorker.KOIN_BASE_DOMAIN_NAME))

            val renewResult = renewer.renewWithFirebase(renewalAuthProvider, baseDomain)
            assertTrue(
                "keypair renewal must succeed, got: $renewResult",
                renewResult is RenewalResult.Success
            )
            assertEquals(
                "relay should see exactly one /renew call",
                1,
                harness.fixture.renewCalls()
            )

            // The relay-issued client cert carries a fresh random serial on
            // every issuance (device_ca::sign_client_cert), so a real renewal
            // rolls it. (The test ACME stub returns a constant server cert, so
            // we can't assert the server cert changes — same constraint as
            // CertRotationIntegrationTest.)
            val renewedClientCert = certStore.getClientCertificate()
            assertNotNull("renewed client cert must be persisted", renewedClientCert)
            assertNotEquals(
                "renewed client cert must differ from the provisioned one — proves the " +
                    "keypair /renew proof was accepted and a fresh cert issued",
                initialClientCert,
                renewedClientCert
            )
        }

    private companion object {
        // The relay requires a non-empty fcm_token on /register; the foss
        // NoOpFcmTokenProvider returns "" until UnifiedPush (#463) supplies a
        // real push endpoint. Stand in a placeholder so the keypair-auth round
        // trip is exercised independently of the push transport.
        const val PLACEHOLDER_PUSH_TOKEN = "foss-keypair-it-placeholder-push-token"
    }
}
