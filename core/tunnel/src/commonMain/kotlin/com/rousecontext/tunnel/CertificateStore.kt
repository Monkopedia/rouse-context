package com.rousecontext.tunnel

/**
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
}
