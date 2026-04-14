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
 * Result of CSR generation, containing the PEM-encoded CSR, the raw DER-encoded CSR bytes
 * (needed for SHA256withECDSA signing during Firebase-path renewal), and the private key PEM.
 */
data class CsrResult(
    val csrPem: String,
    val privateKeyPem: String,
    val csrDer: ByteArray = ByteArray(0)
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CsrResult) return false
        return csrPem == other.csrPem &&
            privateKeyPem == other.privateKeyPem &&
            csrDer.contentEquals(other.csrDer)
    }

    override fun hashCode(): Int {
        var result = csrPem.hashCode()
        result = 31 * result + privateKeyPem.hashCode()
        result = 31 * result + csrDer.contentHashCode()
        return result
    }
}
