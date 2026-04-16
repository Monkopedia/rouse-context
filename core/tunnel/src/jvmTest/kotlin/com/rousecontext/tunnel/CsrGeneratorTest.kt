package com.rousecontext.tunnel

import java.security.KeyFactory
import java.security.Signature
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Locks in the ECDSA P-256 + SHA256withECDSA wire contract the relay expects. The relay's
 * `/renew` endpoint verifies the renewal signature with `p256::ecdsa::VerifyingKey`, so any
 * drift here (e.g. a future change back to RSA) would break every device at the first
 * renewal attempt -- see issue #173.
 */
class CsrGeneratorTest {

    private val generator = CsrGenerator()

    @Test
    fun `generated private key is EC P-256`() {
        val result = generator.generate("test.rousecontext.com")

        val keyBytes = pemToDer(result.privateKeyPem, "PRIVATE KEY")
        val keyFactory = KeyFactory.getInstance("EC")
        val privateKey = keyFactory.generatePrivate(PKCS8EncodedKeySpec(keyBytes))

        assertTrue(
            privateKey is ECPrivateKey,
            "private key must be EC, got ${privateKey.javaClass.name}"
        )
        val ecPriv = privateKey as ECPrivateKey
        assertEquals(
            P256_FIELD_SIZE_BITS,
            ecPriv.params.curve.field.fieldSize,
            "private key must be P-256 (field size 256 bits)"
        )
    }

    @Test
    fun `CSR public key is EC P-256`() {
        val result = generator.generate("test.rousecontext.com")

        val spkiDer = extractSubjectPublicKeyInfo(result.csrDer)
        val keyFactory = KeyFactory.getInstance("EC")
        val publicKey = keyFactory.generatePublic(X509EncodedKeySpec(spkiDer))

        assertTrue(
            publicKey is ECPublicKey,
            "CSR public key must be EC, got ${publicKey.javaClass.name}"
        )
        val ecPub = publicKey as ECPublicKey
        assertEquals(
            P256_FIELD_SIZE_BITS,
            ecPub.params.curve.field.fieldSize,
            "CSR public key must be P-256 (field size 256 bits)"
        )
    }

    @Test
    fun `CSR carries ecdsa-with-SHA256 algorithm OID`() {
        val result = generator.generate("test.rousecontext.com")

        // OID 1.2.840.10045.4.3.2 (ecdsa-with-SHA256) DER-encoded: 06 08 2A 86 48 CE 3D 04 03 02
        val ecdsaSha256OidBytes = byteArrayOf(
            0x06, 0x08, 0x2A.toByte(), 0x86.toByte(), 0x48, 0xCE.toByte(),
            0x3D, 0x04, 0x03, 0x02
        )
        assertTrue(
            containsSubsequence(result.csrDer, ecdsaSha256OidBytes),
            "CSR DER must contain the ecdsa-with-SHA256 OID (1.2.840.10045.4.3.2)"
        )

        // And must NOT carry the RSA sha256WithRSAEncryption OID
        // 1.2.840.113549.1.1.11 DER-encoded: 06 09 2A 86 48 86 F7 0D 01 01 0B
        val rsaSha256OidBytes = byteArrayOf(
            0x06, 0x09, 0x2A.toByte(), 0x86.toByte(), 0x48, 0x86.toByte(),
            0xF7.toByte(), 0x0D, 0x01, 0x01, 0x0B
        )
        assertTrue(
            !containsSubsequence(result.csrDer, rsaSha256OidBytes),
            "CSR DER must not contain the sha256WithRSAEncryption OID"
        )
    }

    @Test
    fun `CSR signature verifies with embedded public key`() {
        val result = generator.generate("test.rousecontext.com")

        val certRequestInfo = extractCertificationRequestInfo(result.csrDer)
        val signatureBytes = extractSignatureBitString(result.csrDer)
        val spkiDer = extractSubjectPublicKeyInfo(result.csrDer)

        val publicKey = KeyFactory.getInstance("EC")
            .generatePublic(X509EncodedKeySpec(spkiDer))

        val verifier = Signature.getInstance("SHA256withECDSA")
        verifier.initVerify(publicKey)
        verifier.update(certRequestInfo)
        assertTrue(
            verifier.verify(signatureBytes),
            "CSR signature must verify under SHA256withECDSA with the embedded public key"
        )
    }

    @Test
    fun `PEM encoding uses CERTIFICATE REQUEST label`() {
        val result = generator.generate("test.rousecontext.com")

        assertTrue(result.csrPem.contains("-----BEGIN CERTIFICATE REQUEST-----"))
        assertTrue(result.csrPem.contains("-----END CERTIFICATE REQUEST-----"))
    }

    // --- DER helpers ---

    private fun pemToDer(pem: String, label: String): ByteArray {
        val header = "-----BEGIN $label-----"
        val footer = "-----END $label-----"
        val start = pem.indexOf(header) + header.length
        val end = pem.indexOf(footer)
        val base64 = pem.substring(start, end).replace("\\s".toRegex(), "")
        return Base64.getDecoder().decode(base64)
    }

    /**
     * PKCS#10 CertificationRequest structure:
     *
     *     SEQUENCE {
     *       CertificationRequestInfo ::= SEQUENCE {
     *         version INTEGER
     *         subject Name
     *         subjectPKInfo SubjectPublicKeyInfo
     *         attributes [0] IMPLICIT SET
     *       }
     *       signatureAlgorithm AlgorithmIdentifier
     *       signature BIT STRING
     *     }
     */
    private fun extractCertificationRequestInfo(csrDer: ByteArray): ByteArray {
        // Skip outer SEQUENCE, return the inner CertificationRequestInfo SEQUENCE as bytes
        val outerContent = derContent(csrDer, 0)
        // outerContent starts with the inner CertificationRequestInfo SEQUENCE
        return derTlv(outerContent.bytes, outerContent.offset)
    }

    private fun extractSubjectPublicKeyInfo(csrDer: ByteArray): ByteArray {
        // csrDer = SEQUENCE { certRequestInfo, signatureAlgorithm, signature }
        // certRequestInfo = SEQUENCE { version, subject, subjectPKInfo, attributes }
        val outer = derContent(csrDer, 0) // payload of outer SEQUENCE
        val certReqInfoContent = derContent(outer.bytes, outer.offset) // inside certRequestInfo

        // Walk: version (INTEGER), subject (SEQUENCE), subjectPKInfo (SEQUENCE), attributes
        var off = certReqInfoContent.offset
        off = skipTlv(certReqInfoContent.bytes, off) // version
        off = skipTlv(certReqInfoContent.bytes, off) // subject
        return derTlv(certReqInfoContent.bytes, off) // subjectPKInfo
    }

    private fun extractSignatureBitString(csrDer: ByteArray): ByteArray {
        val outer = derContent(csrDer, 0)
        var off = outer.offset
        off = skipTlv(outer.bytes, off) // certRequestInfo
        off = skipTlv(outer.bytes, off) // signatureAlgorithm
        // BIT STRING TLV. Read its content directly out of outer.bytes rather than copying to
        // a new buffer first so the `readLength` offsets line up with the tag byte.
        val (contentOff, contentLen) = readLength(outer.bytes, off)
        // First byte of BIT STRING content is "unused bits" count; discard it.
        return outer.bytes.copyOfRange(contentOff + 1, contentOff + contentLen)
    }

    /** Return the full TLV (tag + length + content) starting at [offset] in [bytes]. */
    private fun derTlv(bytes: ByteArray, offset: Int): ByteArray {
        val (contentOff, contentLen) = readLength(bytes, offset)
        val totalLen = (contentOff - offset) + contentLen
        return bytes.copyOfRange(offset, offset + totalLen)
    }

    /** Return a pointer (bytes + offset of content) for the TLV at [offset]. */
    private fun derContent(bytes: ByteArray, offset: Int): DerPointer {
        val (contentOff, _) = readLength(bytes, offset)
        return DerPointer(bytes, contentOff)
    }

    /** Skip the TLV at [offset] and return the offset of the next TLV. */
    private fun skipTlv(bytes: ByteArray, offset: Int): Int {
        val (contentOff, contentLen) = readLength(bytes, offset)
        return contentOff + contentLen
    }

    /**
     * Read the ASN.1 DER length encoded at [bytes[offset+1]] and return the pair
     * (offset-of-content, content-length).
     */
    private fun readLength(bytes: ByteArray, offset: Int): Pair<Int, Int> {
        // bytes[offset] is the tag; length starts at offset+1
        val lengthByte = bytes[offset + 1].toInt() and 0xFF
        return if (lengthByte < 0x80) {
            Pair(offset + 2, lengthByte)
        } else {
            val numBytes = lengthByte and 0x7F
            var length = 0
            for (i in 0 until numBytes) {
                length = (length shl 8) or (bytes[offset + 2 + i].toInt() and 0xFF)
            }
            Pair(offset + 2 + numBytes, length)
        }
    }

    private data class DerPointer(val bytes: ByteArray, val offset: Int)

    private fun containsSubsequence(haystack: ByteArray, needle: ByteArray): Boolean {
        if (needle.isEmpty()) return true
        outer@ for (i in 0..(haystack.size - needle.size)) {
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) continue@outer
            }
            return true
        }
        return false
    }

    private companion object {
        const val P256_FIELD_SIZE_BITS = 256
    }
}
