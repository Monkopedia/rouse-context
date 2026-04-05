package com.rousecontext.tunnel

/**
<<<<<<< HEAD
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
}
