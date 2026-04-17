package com.rousecontext.tunnel

import java.security.KeyPair
import java.security.Signature
import java.util.Base64

/**
 * Generates a PKCS#10 Certificate Signing Request signed by an ECDSA P-256 keypair
 * supplied by the caller (typically via a [DeviceKeyManager]).
 *
 * The key algorithm must match what the relay expects on the renewal signature path
 * (`SHA256withECDSA`) because the relay verifies the renewal signature with the public key
 * extracted from the original CSR at registration time. See `relay/src/api/renew.rs` for the
 * server-side verification, which uses `p256::ecdsa::VerifyingKey::from_public_key_der` and
 * will reject anything that isn't a P-256 SubjectPublicKeyInfo.
 *
 * Issue #200: the caller owns the keypair lifecycle. On production Android that keypair lives
 * in the hardware-backed Keystore and its private bytes are never in app memory. On JVM
 * tests a software keypair is acceptable. Either way `CsrGenerator` just builds and signs
 * the DER; it never serialises the private key.
 */
class CsrGenerator {

    /**
     * Build a PEM-encoded CSR for [commonName] signed with [keyPair].
     *
     * The same [keyPair] MUST be supplied for registration and every subsequent renewal —
     * that is what `DeviceKeyManager.getOrCreateKeyPair` guarantees — so the public key
     * embedded in the CSR stays stable across renewals (the relay pins it at registration).
     */
    fun generate(commonName: String, keyPair: KeyPair): CsrResult {
        val csrDer = buildCsr(commonName, keyPair)
        val csrPem = derToPem(csrDer, "CERTIFICATE REQUEST")
        return CsrResult(csrPem = csrPem, csrDer = csrDer)
    }

    private fun buildCsr(commonName: String, keyPair: KeyPair): ByteArray {
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
}

/**
 * Result of CSR generation, containing the PEM-encoded CSR and the raw DER-encoded CSR
 * bytes (needed for SHA256withECDSA signing during Firebase-path renewal).
 */
data class CsrResult(val csrPem: String, val csrDer: ByteArray = ByteArray(0)) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CsrResult) return false
        return csrPem == other.csrPem &&
            csrDer.contentEquals(other.csrDer)
    }

    override fun hashCode(): Int {
        var result = csrPem.hashCode()
        result = 31 * result + csrDer.contentHashCode()
        return result
    }
}
