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

        val leafDer = certChainDer.first()
        val leafFingerprint = sha256Fingerprint(leafDer)

        return if (knownFingerprints.contains(leafFingerprint)) {
            SecurityCheckResult.Verified
        } else {
            SecurityCheckResult.Alert(
                "Leaf certificate fingerprint $leafFingerprint does not match any known fingerprint"
            )
        }
    }

    companion object {
        /** Compute the colon-separated uppercase hex SHA-256 fingerprint of DER-encoded cert bytes. */
        fun sha256Fingerprint(der: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256")
            return digest.digest(der).joinToString(":") { "%02X".format(it) }
        }
    }
}
