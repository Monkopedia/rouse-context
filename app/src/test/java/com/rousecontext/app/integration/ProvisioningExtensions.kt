package com.rousecontext.app.integration

import com.rousecontext.app.integration.harness.AppIntegrationTestHarness
import com.rousecontext.tunnel.CertProvisioningFlow
import com.rousecontext.tunnel.CertProvisioningResult
import com.rousecontext.tunnel.OnboardingFlow
import com.rousecontext.tunnel.OnboardingResult
import java.io.ByteArrayInputStream
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import org.junit.Assert.assertTrue

/**
 * Helpers shared across the `batch A` integration scenarios (issue #251).
 *
 * Each helper is a plain extension on [AppIntegrationTestHarness] so tests
 * read like a straight-line script against the harness instance. Keeping
 * them as extensions (rather than methods on the harness) means the harness
 * itself (issue #250) stays minimal and the scenario-specific glue lives
 * beside the tests that use it.
 */

internal const val TEST_FIREBASE_TOKEN = "integration-firebase-token"
internal const val TEST_FCM_TOKEN = "integration-fcm-token"

/**
 * Run [OnboardingFlow] + [CertProvisioningFlow] end-to-end against the
 * fixture relay and return the assigned subdomain. Fails the test with a
 * clear message if either step does not end in `Success`.
 *
 * Every `batch A` scenario starts with this sequence, so pulling it into
 * one helper keeps the individual tests focused on the assertion that
 * matters for their specific regression.
 */
internal suspend fun AppIntegrationTestHarness.provisionDevice(
    firebaseToken: String = TEST_FIREBASE_TOKEN,
    fcmToken: String = TEST_FCM_TOKEN
): String {
    val onboardingFlow: OnboardingFlow = koin.get()
    val certProvisioningFlow: CertProvisioningFlow = koin.get()

    val onboardResult = onboardingFlow.execute(
        firebaseToken = firebaseToken,
        fcmToken = fcmToken
    )
    assertTrue(
        "onboarding must succeed, got: $onboardResult",
        onboardResult is OnboardingResult.Success
    )

    val certResult = certProvisioningFlow.execute(firebaseToken = firebaseToken)
    assertTrue(
        "cert provisioning must succeed, got: $certResult",
        certResult is CertProvisioningResult.Success
    )

    return (onboardResult as OnboardingResult.Success).subdomain
}

/**
 * Parse the leaf certificate out of a PEM string (which may contain a full
 * chain — ACME-issued server certs typically do). Uses the multi-cert
 * [CertificateFactory.generateCertificates] because Conscrypt's single-cert
 * path rejects PEM blobs with multiple `BEGIN CERTIFICATE` blocks.
 */
internal fun parsePemCertificate(pem: String): X509Certificate {
    val cf = CertificateFactory.getInstance("X.509")
    val all = cf.generateCertificates(ByteArrayInputStream(pem.toByteArray()))
    require(all.isNotEmpty()) { "PEM contained no certificates" }
    return all.first() as X509Certificate
}
