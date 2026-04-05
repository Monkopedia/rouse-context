package com.rousecontext.tunnel

/**
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
}
