package com.rousecontext.bridge

import javax.net.ssl.SSLContext

/**
 * Provides the server-side TLS context for accepting incoming MCP client connections.
 *
 * Production implementations read from [CertificateStore] (Android Keystore-backed).
 * Test implementations use in-memory self-signed certificates.
 */
interface TlsCertProvider {

    /**
     * Returns an [SSLContext] configured with the device's server certificate and private key,
     * suitable for TLS server-side accept.
     *
     * Returns null if no certificate is available (e.g., onboarding not complete).
     */
    suspend fun serverSslContext(): SSLContext?
}
