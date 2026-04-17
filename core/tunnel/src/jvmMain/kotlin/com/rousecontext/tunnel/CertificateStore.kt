package com.rousecontext.tunnel

/**
 * Platform-agnostic interface for certificate storage.
 *
 * On Android, backed by Android Keystore (hardware-backed HSM).
 * On JVM tests, backed by in-memory stores.
 *
 * Combines identity storage (onboarding/renewal), binary cert access
 * (security monitoring), and fingerprint tracking.
 */
interface CertificateStore {

    // --- Binary cert access (security monitoring) ---

    /** Returns the certificate chain as DER-encoded byte arrays, leaf first. */
    suspend fun getCertChain(): List<ByteArray>?

    /** Returns the private key DER bytes (or a reference handle on Android). */
    suspend fun getPrivateKeyBytes(): ByteArray?

    /** Stores a certificate chain (DER-encoded, leaf first). */
    suspend fun storeCertChain(chain: List<ByteArray>)

    /** Returns the certificate expiry as epoch milliseconds, or null if no cert. */
    suspend fun getCertExpiry(): Long?

    /** Returns SHA-256 fingerprints of known/provisioned certificates. */
    suspend fun getKnownFingerprints(): Set<String>

    /** Stores a certificate fingerprint as known/trusted. */
    suspend fun storeFingerprint(fingerprint: String)

    /**
     * Returns whether the one-shot trust-on-first-sight bootstrap has already
     * fired for this install. Used by [SelfCertVerifier] to distinguish a
     * legitimate pre-#111 migration (marker absent, fingerprints empty) from
     * post-bootstrap corruption (marker present, fingerprints empty), which
     * MUST Alert instead of silently re-trusting whatever cert is presented.
     * See issue #210.
     */
    suspend fun hasFingerprintBootstrapMarker(): Boolean

    /**
     * Record that the one-shot fingerprint bootstrap has completed for this
     * install. Once written, the marker must persist for the lifetime of the
     * install and survive cert renewals; it is only cleared by
     * [clearCertificates] / [clear], which are invoked on a full provisioning
     * rollback where the known-fingerprint set is also dropped. See issue #210.
     */
    suspend fun writeFingerprintBootstrapMarker()

    // --- PEM cert access (onboarding/renewal) ---

    /** Store a PEM-encoded server certificate (ACME, serverAuth - for inner TLS). */
    suspend fun storeCertificate(pemChain: String)

    /** Retrieve the stored PEM-encoded server certificate, or null if none. */
    suspend fun getCertificate(): String?

    /** Store a PEM-encoded client certificate (relay CA, clientAuth - for outer mTLS). */
    suspend fun storeClientCertificate(pemChain: String)

    /** Retrieve the stored PEM-encoded client certificate, or null if none. */
    suspend fun getClientCertificate(): String?

    /** Store the relay CA certificate PEM (issuer of the client cert, for mTLS chain). */
    suspend fun storeRelayCaCert(pem: String)

    /** Retrieve the stored relay CA certificate PEM, or null if none. */
    suspend fun getRelayCaCert(): String?

    /** Store the device subdomain (e.g. "abc123.rousecontext.com"). */
    suspend fun storeSubdomain(subdomain: String)

    /** Retrieve the stored subdomain, or null if not onboarded. */
    suspend fun getSubdomain(): String?

    /** Store per-integration secrets (e.g. {"health": "brave-health"}). */
    suspend fun storeIntegrationSecrets(secrets: Map<String, String>)

    /** Retrieve the stored integration secrets, or null if not yet assigned. */
    suspend fun getIntegrationSecrets(): Map<String, String>?

    /** Convenience: look up the secret for a single integration. */
    suspend fun getSecretForIntegration(integration: String): String? =
        getIntegrationSecrets()?.get(integration)

    /** Store a PEM-encoded private key. On Android this is a no-op (key lives in HSM). */
    suspend fun storePrivateKey(pemKey: String)

    /** Retrieve the PEM-encoded private key, or null. On Android, returns a reference. */
    suspend fun getPrivateKey(): String?

    /** Remove all stored identity state (for rollback on partial failure). */
    suspend fun clear()

    /**
     * Remove only cert-related state (server cert, client cert, relay CA cert,
     * private key, fingerprints). Leaves the onboarding state (subdomain,
     * integration secrets) intact so a failed cert provisioning does not
     * roll back the user to the Welcome screen.
     *
     * Used by [CertProvisioningFlow] for narrow rollback on storage failures.
     */
    suspend fun clearCertificates()
}
