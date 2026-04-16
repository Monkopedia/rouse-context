package com.rousecontext.tunnel

import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64

/**
 * Generates a PKCS#10 Certificate Signing Request backed by an ECDSA P-256 keypair.
 *
 * The key algorithm must match what the relay expects on the renewal signature path
 * (`SHA256withECDSA`) because the relay verifies the renewal signature with the public key
 * extracted from the original CSR at registration time. See `relay/src/api/renew.rs` for the
 * server-side verification, which uses `p256::ecdsa::VerifyingKey::from_public_key_der` and
 * will reject anything that isn't a P-256 SubjectPublicKeyInfo.
 */
class CsrGenerator {

    /**
     * Generate a new keypair and return a PEM-encoded CSR for the given common name.
     * Returns a [CsrResult] containing the CSR PEM and the private key PEM.
     */
    fun generate(commonName: String): CsrResult {
        val keyPairGenerator = KeyPairGenerator.getInstance("EC")
        keyPairGenerator.initialize(ECGenParameterSpec(EC_CURVE))
        val keyPair = keyPairGenerator.generateKeyPair()

        // Build a minimal PKCS#10 CSR using raw ASN.1
        val csrDer = buildCsr(commonName, keyPair)
        val csrPem = derToPem(csrDer, "CERTIFICATE REQUEST")

        val privateKeyDer = keyPair.private.encoded
        val privateKeyPem = derToPem(privateKeyDer, "PRIVATE KEY")

        return CsrResult(csrPem = csrPem, privateKeyPem = privateKeyPem, csrDer = csrDer)
    }

    private fun buildCsr(commonName: String, keyPair: java.security.KeyPair): ByteArray {
        // Build CertificationRequestInfo
        val version = byteArrayOf(0x02, 0x01, 0x00) // INTEGER 0
        val cnOid = byteArrayOf(
            0x06,
            0x03,
            0x55,
            0x04,
            // OID 2.5.4.3 (CN)
            0x03
        )
        val cnValue = derUtf8String(commonName)
        val atv = derSequence(cnOid + cnValue)
        val rdnSet = derSet(atv)
        val subject = derSequence(rdnSet)
        val publicKeyInfo = keyPair.public.encoded // Already DER-encoded SubjectPublicKeyInfo
        val attributes = byteArrayOf(0xA0.toByte(), 0x00) // Empty attributes [0] IMPLICIT

        val certRequestInfo = derSequence(version + subject + publicKeyInfo + attributes)

        // Sign the CertificationRequestInfo
        val signer = Signature.getInstance("SHA256withECDSA")
        signer.initSign(keyPair.private)
        signer.update(certRequestInfo)
        val signatureBytes = signer.sign()

        // Build the full CSR. Per RFC 5758, the ecdsa-with-SHA256 AlgorithmIdentifier MUST
        // omit the parameters field entirely (no NULL, unlike RSA PKCS#1).
        val signatureAlgorithm = derSequence(
            // OID 1.2.840.10045.4.3.2 (ecdsa-with-SHA256)
            byteArrayOf(
                0x06, 0x08, 0x2A.toByte(), 0x86.toByte(), 0x48, 0xCE.toByte(),
                0x3D, 0x04, 0x03, 0x02
            )
        )
        val signatureBitString = derBitString(signatureBytes)

        return derSequence(certRequestInfo + signatureAlgorithm + signatureBitString)
    }

    private fun derSequence(content: ByteArray): ByteArray = derTag(0x30, content)

    private fun derSet(content: ByteArray): ByteArray = derTag(0x31, content)

    private fun derUtf8String(value: String): ByteArray = derTag(
        0x0C,
        value.toByteArray(Charsets.UTF_8)
    )

    private fun derBitString(content: ByteArray): ByteArray {
        val payload = byteArrayOf(0x00) + content // 0 unused bits
        return derTag(0x03, payload)
    }

    private fun derTag(tag: Int, content: ByteArray): ByteArray {
        val lengthBytes = derLength(content.size)
        return byteArrayOf(tag.toByte()) + lengthBytes + content
    }

    private fun derLength(length: Int): ByteArray = when {
        length < 128 -> byteArrayOf(length.toByte())
        length < 256 -> byteArrayOf(0x81.toByte(), length.toByte())
        else -> byteArrayOf(
            0x82.toByte(),
            (length shr 8).toByte(),
            (length and 0xFF).toByte()
        )
    }

    private fun derToPem(der: ByteArray, label: String): String {
        val base64 = Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(der)
        return "-----BEGIN $label-----\n$base64\n-----END $label-----\n"
    }

    private companion object {
        const val EC_CURVE = "secp256r1"
    }
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
