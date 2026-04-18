package com.rousecontext.app.di

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression test for #235: the CT log allowlist must include Google Trust
 * Services intermediates after the #213 ACME migration. Without at least one
 * GTS entry, every post-#213 cert triggers a false-positive security alert.
 */
class ExpectedCertIssuersTest {

    @Test
    fun `includes Google Trust Services intermediates`() {
        val hasGts = EXPECTED_CERT_ISSUERS.any { it.contains("Google Trust Services") }
        assertTrue(
            "EXPECTED_CERT_ISSUERS must include at least one Google Trust Services " +
                "intermediate; otherwise post-#213 certs trigger false-positive alerts. " +
                "Current set: $EXPECTED_CERT_ISSUERS",
            hasGts
        )
    }

    @Test
    fun `retains Let's Encrypt intermediates for transitional legacy certs`() {
        // Pre-#213 device certs from Let's Encrypt remain valid until natural
        // expiry. They should not trigger alerts while they're still live.
        val hasLe = EXPECTED_CERT_ISSUERS.any { it.contains("Let's Encrypt") }
        assertTrue(
            "EXPECTED_CERT_ISSUERS must retain Let's Encrypt entries until legacy " +
                "pre-#213 certs have naturally expired (90-day max).",
            hasLe
        )
    }
}
