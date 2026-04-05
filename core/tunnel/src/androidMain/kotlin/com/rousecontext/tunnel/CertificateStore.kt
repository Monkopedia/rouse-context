package com.rousecontext.tunnel

import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.time.Instant

/**
 * Provides device certificates for TLS. The tunnel module defines the interface;
 * the app module provides the implementation backed by Android Keystore + file storage.
 */
interface CertificateStore {
    /** Device cert chain for mTLS to relay and TLS server accept. */
    fun getCertChain(): List<X509Certificate>?

    /** Private key reference (from Android Keystore on device, or in-memory for tests). */
    fun getPrivateKey(): PrivateKey?

    /** Store a new cert chain (after onboarding or renewal). */
    fun storeCertChain(certs: List<X509Certificate>)

    /** Device subdomain (e.g. "abc123" for abc123.rousecontext.com). */
    fun getSubdomain(): String?

    /** Cert expiry for renewal checks. */
    fun getCertExpiry(): Instant?
}
