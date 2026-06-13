package com.rousecontext.tunnel

import java.security.SecureRandom
import java.util.Base64

/**
 * Keypair-based device authentication primitives for the `foss` flavor
 * (issue #462). The device proves possession of its hardware-backed ECDSA
 * P-256 key by signing a short-lived, replay-bounded proof token instead of
 * presenting a Firebase ID token.
 *
 * The canonical signed message is mirrored byte-for-byte by the relay
 * (`relay/src/keypair_auth.rs`); the two MUST stay in lock-step. The layout is:
 *
 * ```text
 * rouse-context-keypair-auth:v1\n<purpose>\n<timestamp_secs>\n<nonce>
 * ```
 *
 * The signature is an `SHA256withECDSA` (ECDSA P-256 over SHA-256) signature
 * over those bytes, DER-encoded then Base64-encoded for transport — the same
 * mechanism the device already uses to sign CSR DER bytes on renewal.
 */
object KeypairAuth {
    /** Domain-separation prefix baked into every proof. */
    const val PROOF_DOMAIN = "rouse-context-keypair-auth:v1"

    /** Proof purpose for initial device registration (`POST /register`). */
    const val PURPOSE_REGISTER = "register"

    /** Proof purpose for expired-certificate renewal (`POST /renew`, Path B). */
    const val PURPOSE_RENEW = "renew"

    private val secureRandom = SecureRandom()

    /**
     * Build the canonical byte string the device signs for a proof token. The
     * relay reconstructs the identical bytes from the request fields and
     * verifies the signature over them.
     */
    fun canonicalMessage(purpose: String, timestampSecs: Long, nonce: String): ByteArray =
        "$PROOF_DOMAIN\n$purpose\n$timestampSecs\n$nonce".toByteArray(Charsets.UTF_8)

    /** Generate a fresh opaque nonce: Base64 of 16 random bytes. */
    fun randomNonce(): String {
        val bytes = ByteArray(NONCE_BYTES)
        secureRandom.nextBytes(bytes)
        return Base64.getEncoder().encodeToString(bytes)
    }

    private const val NONCE_BYTES = 16
}

/**
 * A signed keypair proof token: the timestamp + nonce that were signed, plus
 * the Base64 DER signature over [KeypairAuth.canonicalMessage].
 */
data class KeypairProof(val timestampSecs: Long, val nonce: String, val signature: String)

/**
 * Credentials presented at first-run registration, abstracting the two flavors.
 *
 * - [Firebase] (`google`): a Firebase ID token authenticates `/register`.
 * - [Keypair] (`foss`): the device public key plus a signed registration proof
 *   authenticate `/register`; identity is keyed on the key thumbprint.
 */
sealed interface DeviceCredential {
    data class Firebase(val idToken: String) : DeviceCredential

    data class Keypair(
        /** DER-encoded SubjectPublicKeyInfo of the device key. */
        val publicKeyDer: ByteArray,
        /** Proof over [KeypairAuth.PURPOSE_REGISTER]. */
        val registerProof: KeypairProof
    ) : DeviceCredential {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Keypair) return false
            return publicKeyDer.contentEquals(other.publicKeyDer) &&
                registerProof == other.registerProof
        }

        override fun hashCode(): Int =
            31 * publicKeyDer.contentHashCode() + registerProof.hashCode()
    }
}

/**
 * Credentials for the expired-certificate renewal path (`foss` flavor).
 *
 * - [csrSignature]: Base64 DER `SHA256withECDSA` signature over the renewal CSR
 *   DER bytes (the always-required proof-of-key-possession).
 * - [proof]: a fresh [KeypairAuth.PURPOSE_RENEW] proof, verified by the relay
 *   against the device's stored public key with a bounded replay window.
 */
data class KeypairRenewalCredentials(val csrSignature: String, val proof: KeypairProof)
