package com.rousecontext.tunnel

import java.security.KeyPair

/**
 * Device identity keypair manager.
 *
 * On Android, backed by the hardware-backed Android Keystore (StrongBox when
 * available, TEE otherwise). Once generated, the private key material never
 * leaves the secure element: all signing operations happen inside the
 * hardware. The returned [KeyPair] carries a [java.security.PrivateKey] that
 * is a JCA handle to the Keystore entry, not exportable bytes.
 *
 * On the JVM (tests), backed by an in-memory software EC keypair generated
 * via [java.security.KeyPairGenerator].
 *
 * Implementations MUST be idempotent: the first call generates (or provisions)
 * the keypair, every subsequent call returns the same underlying keypair so
 * that CSR renewal reuses the registration public key.
 */
interface DeviceKeyManager {

    /**
     * Returns the device identity keypair, generating it on first call.
     *
     * The [KeyPair.public] carries the DER-encoded `SubjectPublicKeyInfo`
     * suitable for embedding in a PKCS#10 CSR. The [KeyPair.private] can be
     * initialized into a [java.security.Signature] instance for
     * `SHA256withECDSA` signing — on Android the sign operation executes
     * inside the Keystore and the raw key bytes are never accessible.
     *
     * Synchronous by design: the Android Keystore lookup / generation is a
     * millisecond-scale JCA call, and the interface is consumed from both
     * suspending (CertProvisioningFlow / CertRenewalFlow) and non-suspending
     * (WebSocketFactory.connect / SSLContext construction) code paths. Making
     * this suspending would force every call site to hoist a coroutine, which
     * is worse than accepting the short blocking window.
     */
    fun getOrCreateKeyPair(): KeyPair
}
