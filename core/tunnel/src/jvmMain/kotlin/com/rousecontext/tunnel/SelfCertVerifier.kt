package com.rousecontext.tunnel

import java.security.MessageDigest

/**
 * Verifies that a presented certificate matches the fingerprints
 * stored in [CertificateStore]. Only the leaf certificate is checked;
 * intermediate/root certs in the chain are ignored.
 *
 * During certificate renewal, both old and new fingerprints may be
 * stored simultaneously, so either matching is sufficient.
 *
 * If the store is unreachable (I/O error), the result is a [SecurityCheckResult.Warning]
 * rather than an [SecurityCheckResult.Alert], since the check could not be completed.
 *
 * Upgrade path (issue #180): devices provisioned before fingerprint recording
 * (pre-#111) have a valid leaf cert on disk but an empty known-fingerprints set.
 * Without self-healing, the very first periodic check would trip an Alert,
 * lock every HTTPS route into 503, and "Acknowledge" in the UI would not stick
 * because the next worker run re-alerts. On a completely empty set we treat the
 * presented leaf as trusted-on-first-sight and backfill the store; subsequent
 * runs then take the normal matching path. This is a property of the verifier
 * only -- the cert-provisioning flow is unchanged. If TLS itself was compromised
 * before the very first verify, the fingerprint file was never the weak link.
 *
 * One-shot guard (issue #210): the backfill branch above is gated by a sidecar
 * bootstrap marker. On first backfill we write the marker; on subsequent
 * calls an empty fingerprints set WITH the marker present means the file was
 * corrupted / truncated / restored from a partial snapshot after bootstrap,
 * and we MUST Alert rather than silently re-trusting whatever cert is
 * presented. The backfill is therefore a strictly one-time-per-install
 * migration, not a permanent self-heal.
 */
class SelfCertVerifier(private val certificateStore: CertificateStore) {

    /**
     * Verify that the leaf certificate in [certChainDer] (DER-encoded, leaf first)
     * has a SHA-256 fingerprint matching one of the known fingerprints in the store.
     */
    suspend fun verify(certChainDer: List<ByteArray>): SecurityCheckResult {
        if (certChainDer.isEmpty()) {
            return SecurityCheckResult.Alert("Empty certificate chain")
        }

        val knownFingerprints: Set<String>
        try {
            knownFingerprints = certificateStore.getKnownFingerprints()
        } catch (e: Exception) {
            return SecurityCheckResult.Warning(
                "Could not retrieve known fingerprints: ${e.message}"
            )
        }

        val leafFingerprint = sha256Fingerprint(certChainDer.first())

        return when {
            knownFingerprints.contains(leafFingerprint) -> SecurityCheckResult.Verified
            // Trust-on-first-sight backfill for pre-#111 installs whose fingerprints
            // file never got written. Only applies when the set is truly empty --
            // any non-empty set (including corrupt/garbage entries) must still
            // Alert so a real tamper signal is preserved.
            knownFingerprints.isEmpty() -> handleEmptyFingerprints(leafFingerprint)
            else -> SecurityCheckResult.Alert(
                "Leaf certificate fingerprint $leafFingerprint " +
                    "does not match any known fingerprint"
            )
        }
    }

    private suspend fun handleEmptyFingerprints(leafFingerprint: String): SecurityCheckResult {
        val markerPresent = try {
            certificateStore.hasFingerprintBootstrapMarker()
        } catch (e: Exception) {
            return SecurityCheckResult.Warning(
                "Could not check fingerprint bootstrap marker: ${e.message}"
            )
        }
        // Issue #210: once bootstrap has fired, an empty fingerprint set can
        // only come from corruption / partial-snapshot restore / buggy cleanup.
        // Re-backfilling would silently re-trust whatever cert we happen to be
        // looking at, defeating pinning. Alert instead.
        if (markerPresent) {
            return SecurityCheckResult.Alert(
                "Fingerprint store is empty but bootstrap marker is present; " +
                    "cannot silently re-trust leaf fingerprint $leafFingerprint"
            )
        }
        return backfillAndVerify(leafFingerprint)
    }

    private suspend fun backfillAndVerify(leafFingerprint: String): SecurityCheckResult = try {
        certificateStore.storeFingerprint(leafFingerprint)
        // Write the marker AFTER the fingerprint is stored: if the fingerprint
        // write fails, we must not claim bootstrap completed, or a retry would
        // surface the corruption path (empty set + marker present) incorrectly.
        certificateStore.writeFingerprintBootstrapMarker()
        SecurityCheckResult.Verified
    } catch (e: Exception) {
        SecurityCheckResult.Warning(
            "Could not backfill missing fingerprint: ${e.message}"
        )
    }

    companion object {
        /** Compute the colon-separated uppercase hex SHA-256 fingerprint of DER-encoded cert bytes. */
        fun sha256Fingerprint(der: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256")
            return digest.digest(der).joinToString(":") { "%02X".format(it) }
        }
    }
}
