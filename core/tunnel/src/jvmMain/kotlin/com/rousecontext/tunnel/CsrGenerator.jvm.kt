package com.rousecontext.tunnel

import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64

actual class CsrGenerator actual constructor() {

    actual fun generate(commonName: String): CsrResult {
        val keyPairGenerator = KeyPairGenerator.getInstance("RSA")
        keyPairGenerator.initialize(2048)
        val keyPair = keyPairGenerator.generateKeyPair()

        // Build a minimal PKCS#10 CSR using raw ASN.1
        val csrDer = buildCsr(commonName, keyPair)
        val csrPem = derToPem(csrDer, "CERTIFICATE REQUEST")

        val privateKeyDer = keyPair.private.encoded
        val privateKeyPem = derToPem(privateKeyDer, "PRIVATE KEY")

        return CsrResult(csrPem = csrPem, privateKeyPem = privateKeyPem)
    }

    private fun buildCsr(
        commonName: String,
        keyPair: java.security.KeyPair,
    ): ByteArray {
        // Build CertificationRequestInfo
        val version = byteArrayOf(0x02, 0x01, 0x00) // INTEGER 0
        val cnOid = byteArrayOf(
            0x06, 0x03, 0x55, 0x04, 0x03, // OID 2.5.4.3 (CN)
        )
        val cnValue = derUtf8String(commonName)
        val atv = derSequence(cnOid + cnValue)
        val rdnSet = derSet(atv)
        val subject = derSequence(rdnSet)
        val publicKeyInfo = keyPair.public.encoded // Already DER-encoded SubjectPublicKeyInfo
        val attributes = byteArrayOf(0xA0.toByte(), 0x00) // Empty attributes [0] IMPLICIT

        val certRequestInfo = derSequence(version + subject + publicKeyInfo + attributes)

        // Sign the CertificationRequestInfo
        val signer = Signature.getInstance("SHA256withRSA")
        signer.initSign(keyPair.private)
        signer.update(certRequestInfo)
        val signatureBytes = signer.sign()

        // Build the full CSR
        val signatureAlgorithm = derSequence(
            // OID 1.2.840.113549.1.1.11 (sha256WithRSAEncryption)
            byteArrayOf(
                0x06, 0x09, 0x2A.toByte(), 0x86.toByte(), 0x48, 0x86.toByte(),
                0xF7.toByte(), 0x0D, 0x01, 0x01, 0x0B,
                0x05, 0x00, // NULL parameters
            ),
        )
        val signatureBitString = derBitString(signatureBytes)

        return derSequence(certRequestInfo + signatureAlgorithm + signatureBitString)
    }

    private fun derSequence(content: ByteArray): ByteArray = derTag(0x30, content)

    private fun derSet(content: ByteArray): ByteArray = derTag(0x31, content)

    private fun derUtf8String(value: String): ByteArray = derTag(0x0C, value.toByteArray(Charsets.UTF_8))

    private fun derBitString(content: ByteArray): ByteArray {
        val payload = byteArrayOf(0x00) + content // 0 unused bits
        return derTag(0x03, payload)
    }

    private fun derTag(tag: Int, content: ByteArray): ByteArray {
        val lengthBytes = derLength(content.size)
        return byteArrayOf(tag.toByte()) + lengthBytes + content
    }

    private fun derLength(length: Int): ByteArray =
        when {
            length < 128 -> byteArrayOf(length.toByte())
            length < 256 -> byteArrayOf(0x81.toByte(), length.toByte())
            else -> byteArrayOf(
                0x82.toByte(),
                (length shr 8).toByte(),
                (length and 0xFF).toByte(),
            )
        }

    private fun derToPem(der: ByteArray, label: String): String {
        val base64 = Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(der)
        return "-----BEGIN $label-----\n$base64\n-----END $label-----\n"
    }
}
