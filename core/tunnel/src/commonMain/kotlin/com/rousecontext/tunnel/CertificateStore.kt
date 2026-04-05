package com.rousecontext.tunnel

/**
<<<<<<< HEAD
<<<<<<< HEAD
<<<<<<< HEAD
=======
>>>>>>> feat/security-monitoring
 * Platform-agnostic interface for certificate storage.
 *
 * On Android, backed by Android Keystore (hardware-backed HSM).
 * On JVM tests, backed by in-memory stores.
 */
interface CertificateStore {
    /** Returns the certificate chain as DER-encoded byte arrays, leaf first. */
    suspend fun getCertChain(): List<ByteArray>?

    /** Returns the private key bytes (or a reference handle on Android). */
    suspend fun getPrivateKey(): ByteArray?

    /** Stores a certificate chain (DER-encoded, leaf first). */
    suspend fun storeCertChain(chain: List<ByteArray>)

    /** Returns the device's subdomain (e.g. "abc123" for abc123.rousecontext.com). */
    suspend fun getSubdomain(): String?

    /** Returns the certificate expiry as epoch milliseconds, or null if no cert. */
    suspend fun getCertExpiry(): Long?

<<<<<<< HEAD
    /** Returns fingerprints of known relay server certificates. */
    suspend fun getKnownFingerprints(): Set<String>

    /** Stores a relay server certificate fingerprint as trusted. */
    suspend fun storeFingerprint(fingerprint: String)
=======
 * Platform-specific certificate store that provides the device's TLS identity.
 * On Android, backed by Android Keystore. In tests, backed by in-memory certs.
 */
interface CertificateStore {
    /**
     * Get the PEM-encoded certificate chain for this device.
     */
    fun getCertificateChain(): List<ByteArray>

    /**
     * Sign data using the device's private key (for TLS handshake).
     * The private key never leaves the store.
     */
    fun sign(data: ByteArray): ByteArray

    /**
     * Get the key algorithm (e.g., "EC", "RSA").
     */
    fun getKeyAlgorithm(): String
>>>>>>> feat/tunnel-websocket-tls
=======
    /** Returns SHA-256 fingerprints of known/provisioned certificates. */
    suspend fun getKnownFingerprints(): Set<String>

    /** Stores a certificate fingerprint as known/trusted. */
    suspend fun storeFingerprint(fingerprint: String)
>>>>>>> feat/security-monitoring
=======
 * Platform-agnostic interface for storing and retrieving device identity.
 * On Android this wraps Android Keystore; on JVM tests it uses an in-memory map.
 */
interface CertificateStore {

    /** Store a PEM-encoded certificate chain. */
    suspend fun storeCertificate(pemChain: String)

    /** Retrieve the stored PEM-encoded certificate chain, or null if none. */
    suspend fun getCertificate(): String?

    /** Store the device subdomain (e.g. "abc123.rousecontext.com"). */
    suspend fun storeSubdomain(subdomain: String)

    /** Retrieve the stored subdomain, or null if not onboarded. */
    suspend fun getSubdomain(): String?

    /** Store a PEM-encoded private key. On Android this is a no-op (key lives in HSM). */
    suspend fun storePrivateKey(pemKey: String)

    /** Retrieve the PEM-encoded private key, or null. On Android, returns a reference. */
    suspend fun getPrivateKey(): String?

    /** Remove all stored identity state (for rollback on partial failure). */
    suspend fun clear()
>>>>>>> feat/tunnel-onboarding
}
