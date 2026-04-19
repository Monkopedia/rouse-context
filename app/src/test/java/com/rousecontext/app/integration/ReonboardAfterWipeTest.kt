package com.rousecontext.app.integration

import com.rousecontext.app.integration.harness.AppIntegrationTestHarness
import com.rousecontext.app.integration.harness.TestEchoMcpIntegration
import com.rousecontext.tunnel.CertificateStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Re-onboarding after a full data wipe (issue #271).
 *
 * Motivating incident: on 2026-04-18 a manual `pm clear` on a provisioned
 * device followed by fresh onboarding surfaced four distinct bugs in a single
 * session — #237 (missing mTLS on /rotate-secret), #238 (retry storms against
 * /register), #243 (stale in-memory registration status) and #244 (integration
 * setup hangs on a half-cleared store). None of those regressions had
 * automated coverage; this file is the catch-all guard so the next pass
 * through the same scenario fails loudly in CI instead of on a tester's
 * device.
 *
 * The single `@Test` mirrors the real-world flow — we only go round-trip
 * once rather than per-assertion so the integration suite stays well under
 * the 10-minute CI wall cap (each onboarding hits a real TLS handshake +
 * real relay subprocess):
 *
 *   1. Provision the device end-to-end via [provisionDevice] (captures the
 *      first subdomain + stores a first client cert + integration secrets).
 *   2. Simulate the wipe: stop the harness, drop every persistent artefact
 *      via [AppIntegrationTestHarness.clearPersistentState], restart the
 *      harness. Koin singletons, Room DBs, DataStore files and the
 *      [com.rousecontext.tunnel.CertificateStore] are all fresh.
 *   3. Onboard a second time. A different Firebase token is used so the
 *      test-mode relay treats it as a different UID (the relay's
 *      `/register` handler rejects same-UID re-registration without a
 *      private-key-backed signature by design — the key was wiped on the
 *      device side, so a fresh account is the realistic recovery path).
 *   4. Cross-check every invariant: different subdomain, different public
 *      key, exactly one `/request-subdomain` hop on the fresh relay,
 *      fully-populated store.
 *
 * Not covered here (separate issues own these):
 *   - Android system-level `pm clear` semantics (covered by manual
 *     device-farm runs, intentionally out of scope per #271).
 *   - #200-era PEM-file migration (code removed in #232).
 *   - Full re-onboard for every integration (one integration exercises the
 *     same push path — the relay is the piece under test, not the per-
 *     integration id).
 */
@RunWith(RobolectricTestRunner::class)
class ReonboardAfterWipeTest {

    // Register one integration so the relay's /register response carries a
    // non-empty `secrets` map and the store's `getIntegrationSecrets()` is
    // populated. `TestEchoMcpIntegration` is the existing in-harness shim.
    private val harness = AppIntegrationTestHarness(
        integrationsFactory = { listOf(TestEchoMcpIntegration()) }
    )

    @Before
    fun setUp() {
        harness.start()
    }

    @After
    fun tearDown() {
        harness.stop()
    }

    /**
     * Provision → wipe → re-provision, then cross-check every regression
     * guard in one shot. All three assertions we care about (fresh
     * subdomain, fresh key, single `/request-subdomain` call) share the
     * same setup and the same pair of provisioning rounds; keeping them in
     * one `@Test` halves integration-test wall time on CI. Failures surface
     * distinct messages so a green-to-red transition still pinpoints which
     * invariant broke.
     */
    @Test
    fun `re-onboarding after data wipe restores a fully fresh install`() = runBlocking {
        // --- Round 1: fresh provisioning on a pristine harness. -----------
        val firstSubdomain = harness.provisionDevice(
            firebaseToken = FIRST_FIREBASE_TOKEN,
            fcmToken = FIRST_FCM_TOKEN
        )
        val firstStore: CertificateStore = harness.koin.get()
        val firstClientCertPem = firstStore.getClientCertificate()
        assertNotNull("first run must persist a client cert", firstClientCertPem)
        assertNotNull(
            "first run must have persisted integration secrets",
            firstStore.getIntegrationSecrets()
        )
        val firstPublicKey = parsePemCertificate(firstClientCertPem!!).publicKey.encoded
        assertEquals(
            "first onboarding must produce exactly one /request-subdomain call " +
                "(#238 retry-storm guard)",
            1,
            harness.fixture.requestSubdomainCalls()
        )

        // --- Wipe: drop every persistent artefact, restart the harness. ---
        wipeAndRestart()
        assertStoreFullyEmpty(harness.koin.get())

        // --- Round 2: re-provision on the fresh harness. A different -----
        // Firebase token maps to a different test-mode UID so the relay
        // treats this onboarding as a brand-new account.
        val secondSubdomain = harness.provisionDevice(
            firebaseToken = SECOND_FIREBASE_TOKEN,
            fcmToken = SECOND_FCM_TOKEN
        )

        assertNotEquals(
            "re-onboarding must yield a new subdomain (regression guard for the " +
                "2026-04-18 `pm clear` scenario — #271)",
            firstSubdomain,
            secondSubdomain
        )
        // Fresh relay → counter is 1 after exactly one hop, not 0 (skipped)
        // or 2+ (retry storm).
        assertEquals(
            "re-onboarding must hit /request-subdomain exactly once on the fresh relay",
            1,
            harness.fixture.requestSubdomainCalls()
        )
        val secondStore: CertificateStore = harness.koin.get()
        assertStoreFullyPopulated(secondStore, secondSubdomain)

        // Key material regeneration: the harness's software DeviceKeyManager
        // is re-instantiated on `start()`, so the second `/register/certs`
        // must bind a brand-new public key. Reusing the key would defeat
        // the point of clearing the Android Keystore alias in production.
        val secondPublicKey =
            parsePemCertificate(secondStore.getClientCertificate()!!).publicKey.encoded
        assertNotEquals(
            "re-onboarded client cert must bind a fresh public key — reusing the key " +
                "across a wipe would defeat the Keystore-alias clear",
            firstPublicKey.toList(),
            secondPublicKey.toList()
        )
    }

    /** Tear down, drop every persistent artefact, then restart from scratch. */
    private fun wipeAndRestart() {
        harness.stop()
        harness.clearPersistentState()
        harness.start()
    }

    /** Post-wipe invariant: the fresh Koin graph sees an empty store. */
    private suspend fun assertStoreFullyEmpty(store: CertificateStore) {
        assertTrue(
            "subdomain must be gone after wipe, got: ${store.getSubdomain()}",
            store.getSubdomain() == null
        )
        assertTrue(
            "client cert must be gone after wipe",
            store.getClientCertificate() == null
        )
    }

    /** Post-reprovision invariants covering every file the cert store owns. */
    private suspend fun assertStoreFullyPopulated(
        store: CertificateStore,
        expectedSubdomain: String
    ) {
        assertEquals(
            "CertificateStore.getSubdomain() must round-trip the new subdomain",
            expectedSubdomain,
            store.getSubdomain()
        )
        assertNotNull("second run must persist a fresh client cert", store.getClientCertificate())
        assertNotNull("second run must persist the relay CA cert", store.getRelayCaCert())
        assertNotNull("second run must persist the server (ACME) cert", store.getCertificate())

        val secrets = store.getIntegrationSecrets()
        // Secrets are generated per-registration by the relay — they should
        // be present and non-empty. We do NOT require them to differ from the
        // first-run secrets: the relay's generator draws from a public word
        // list and collisions, while astronomically unlikely, are not a
        // correctness violation.
        assertNotNull("post-wipe integration secrets must be persisted", secrets)
        assertTrue(
            "post-wipe integration secrets map must be non-empty (regression guard " +
                "for #244 — half-cleared store returning null secrets)",
            secrets!!.isNotEmpty()
        )
        assertTrue(
            "post-wipe integration secrets must contain the harness integration's id " +
                "(TestEchoMcpIntegration), got keys: ${secrets.keys}",
            secrets.containsKey(TestEchoMcpIntegration.ID)
        )
    }

    private companion object {
        // Two distinct tokens so the test-mode relay's
        // `format!("test-uid-{}", stable_uid_hash(token))` produces different
        // UIDs for each onboarding. Real production would use one Google
        // account either way; the UID delta is the test-shaped equivalent of
        // a fresh install where the relay-side device record hasn't been
        // carried over (which is the true invariant we care about on the
        // device).
        const val FIRST_FIREBASE_TOKEN = "integration-firebase-token-first"
        const val FIRST_FCM_TOKEN = "integration-fcm-token-first"
        const val SECOND_FIREBASE_TOKEN = "integration-firebase-token-second"
        const val SECOND_FCM_TOKEN = "integration-fcm-token-second"
    }
}
