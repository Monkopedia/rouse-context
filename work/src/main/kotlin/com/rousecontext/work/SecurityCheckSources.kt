package com.rousecontext.work

import com.rousecontext.tunnel.CertificateStore
import com.rousecontext.tunnel.CtLogMonitor
import com.rousecontext.tunnel.SecurityCheckResult
import com.rousecontext.tunnel.SelfCertVerifier

/**
 * [SecurityCheckSource] that delegates to [SelfCertVerifier.verify] against
 * the current cert chain stored in [CertificateStore].
 *
 * The periodic worker has no inbound TLS connection, so instead of verifying
 * a presented chain we verify the store's own leaf cert against the set of
 * known fingerprints. Any mismatch (e.g. keystore tamper) still produces
 * an [SecurityCheckResult.Alert]; store I/O errors produce a warning.
 */
class StoredCertVerifierSource(
    private val certificateStore: CertificateStore,
    private val verifier: SelfCertVerifier
) : SecurityCheckSource {

    override suspend fun check(): SecurityCheckResult {
        val chain = try {
            certificateStore.getCertChain()
        } catch (e: Exception) {
            return SecurityCheckResult.Warning(
                "Could not retrieve cert chain: ${e.message}"
            )
        }
        if (chain.isNullOrEmpty()) {
            // Issue #228: no cert stored yet (pre-onboarding / post-reinstall)
            // — nothing to verify against. This is not a security condition,
            // so MUST NOT surface a user-facing notification. The worker still
            // records "skipped" so the Settings screen can show the waiting
            // state if the user checks.
            return SecurityCheckResult.Skipped("No certificate stored")
        }
        return verifier.verify(chain)
    }
}

/**
 * [SecurityCheckSource] that delegates to [CtLogMonitor.check] for CT log
 * monitoring of unauthorized certificate issuance.
 */
class CtLogMonitorSource(private val monitor: CtLogMonitor) : SecurityCheckSource {

    override suspend fun check(): SecurityCheckResult = monitor.check()
}
