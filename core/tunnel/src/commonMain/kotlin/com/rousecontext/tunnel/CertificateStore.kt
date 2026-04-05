package com.rousecontext.tunnel

/**
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

    /** Returns SHA-256 fingerprints of known/provisioned certificates. */
    suspend fun getKnownFingerprints(): Set<String>

    /** Stores a certificate fingerprint as known/trusted. */
    suspend fun storeFingerprint(fingerprint: String)
}
