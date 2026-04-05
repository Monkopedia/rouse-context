package com.rousecontext.tunnel

/**
 * Generates a PKCS#10 Certificate Signing Request.
 * expect/actual: JVM uses java.security, Android uses Android Keystore.
 */
expect class CsrGenerator() {

    /**
     * Generate a new keypair and return a PEM-encoded CSR for the given common name.
     * Returns a [CsrResult] containing the CSR PEM and the private key PEM.
     */
    fun generate(commonName: String): CsrResult
}

/**
 * Result of CSR generation, containing the PEM-encoded CSR and private key.
 */
data class CsrResult(
    val csrPem: String,
    val privateKeyPem: String,
)
