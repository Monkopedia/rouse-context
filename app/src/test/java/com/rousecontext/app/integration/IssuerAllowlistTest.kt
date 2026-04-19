package com.rousecontext.app.integration

import com.rousecontext.app.di.EXPECTED_CERT_ISSUERS
import com.rousecontext.app.integration.harness.AppIntegrationTestHarness
import com.rousecontext.tunnel.CertificateStore
import com.rousecontext.tunnel.CtLogFetcher
import com.rousecontext.tunnel.CtLogMonitor
import com.rousecontext.tunnel.SecurityCheckResult
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Regression guard for issue #235: production `EXPECTED_CERT_ISSUERS` must
 * include the Google Trust Services intermediates the relay now issues
 * from, AND [CtLogMonitor] must actually accept CT-log entries issued by
 * an allowlisted issuer.
 *
 * The original #235 bug was `EXPECTED_CERT_ISSUERS` silently drifting out
 * of sync with the live CA: the relay cut over to GTS, the allowlist was
 * still Let's Encrypt only, and every legitimate cert triggered an
 * `Alert`-level security notification. This test holds two lines
 * simultaneously:
 *
 *   1. The constant still contains a GTS prefix. A future cleanup that
 *      removes GTS entries entirely would regress the bug exactly the way
 *      #235 did.
 *   2. The [CtLogMonitor.check] path returns [SecurityCheckResult.Verified]
 *      when the CT log response's issuer is in the allowlist.
 *
 * The test relay issues device certs from a local `CN=Test CA` root, which
 * intentionally is NOT in production's `EXPECTED_CERT_ISSUERS`. So we
 * inline a per-test allowlist containing the fixture issuer to exercise
 * the "verify" branch, and assert the production constant still covers
 * GTS separately.
 */
@RunWith(RobolectricTestRunner::class)
class IssuerAllowlistTest {

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
    fun `provisioned cert issuer is accepted by matching CtLogMonitor allowlist`() = runBlocking {
        val subdomain = harness.provisionDevice()
        val certStore: CertificateStore = harness.koin.get()

        // --- Extract the issuer DN from the provisioned client cert. ---
        // The test relay uses a stub ACME client that returns the literal
        // string "stub-cert" for the server cert; that isn't valid PEM.
        // The client cert (signed by the device CA = the fixture's Test CA)
        // is real X.509 and is what we parse here. The issuer DN we end
        // up with is the same Test CA subject, which is exactly what we
        // need to seed the per-test CtLogMonitor allowlist.
        val clientCertPem = certStore.getClientCertificate()
        assertNotNull(
            "client certificate must have been persisted by CertProvisioningFlow",
            clientCertPem
        )
        val issuerDn = parsePemCertificate(clientCertPem!!).issuerX500Principal.name
        assertTrue(
            "issuer DN should be non-empty (test CA is `CN=Test CA`), got: '$issuerDn'",
            issuerDn.isNotBlank()
        )

        // --- Feed a canned crt.sh response mentioning that issuer and
        //     assert CtLogMonitor verifies.                                 ---
        val baseDomain = harness.baseDomain
        val cannedResponse = """
            [
              {
                "issuer_ca_id": 1,
                "issuer_name": ${jsonString(issuerDn)},
                "common_name": "*.$subdomain.$baseDomain",
                "name_value": "*.$subdomain.$baseDomain",
                "id": 1,
                "entry_timestamp": "2025-01-01T00:00:00",
                "not_before": "2025-01-01T00:00:00",
                "not_after": "2026-01-01T00:00:00",
                "serial_number": "01"
              }
            ]
        """.trimIndent()
        val fetcher = object : CtLogFetcher {
            override suspend fun fetch(domain: String): String = cannedResponse
        }

        val monitor = CtLogMonitor(
            certificateStore = certStore,
            ctLogFetcher = fetcher,
            expectedIssuers = setOf(issuerDn),
            baseDomain = baseDomain
        )

        val result = monitor.check()
        assertEquals(
            "CtLogMonitor must return Verified when the CT log issuer is in the allowlist. " +
                "Got: $result",
            SecurityCheckResult.Verified,
            result
        )
    }

    @Test
    fun `production EXPECTED_CERT_ISSUERS covers Google Trust Services (issue 235)`() {
        // #235's root cause was the allowlist falling out of sync with the CA
        // the relay actually issues from. Post-#213 the relay uses GTS. This
        // test protects against a future cleanup that drops GTS entries
        // without a companion CA migration.
        val hasGts = EXPECTED_CERT_ISSUERS.any {
            it.contains("O=Google Trust Services")
        }
        assertTrue(
            "EXPECTED_CERT_ISSUERS must include at least one Google Trust Services " +
                "intermediate. Post-#213 the relay issues certs via GTS; removing " +
                "these entries would regress issue #235. Current set: " +
                EXPECTED_CERT_ISSUERS,
            hasGts
        )
    }

    /**
     * Access the base domain the fixture runs with, mirroring the harness
     * default. Kept as an extension to avoid adding another getter to the
     * harness for a one-off value.
     */
    private val AppIntegrationTestHarness.baseDomain: String
        get() = "relay.test.local"

    /**
     * Minimal JSON string escape. `issuerDn` contains embedded commas and
     * equals signs — safe for JSON bodies — but we still need to quote and
     * escape backslashes / double-quotes defensively.
     */
    private fun jsonString(raw: String): String {
        val escaped = raw.replace("\\", "\\\\").replace("\"", "\\\"")
        return "\"$escaped\""
    }
}
