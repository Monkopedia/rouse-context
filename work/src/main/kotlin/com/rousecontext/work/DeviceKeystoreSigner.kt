package com.rousecontext.work

/**
 * Signs arbitrary data with the device's Android Keystore private key.
 *
 * Extracted as an interface so [FirebaseRenewalAuthProvider] stays unit-testable without
 * Robolectric / a real Keystore: tests inject a fake signer and assert the flow, and a
 * separate Robolectric test exercises the real Keystore-backed implementation.
 */
interface DeviceKeystoreSigner {

    /**
     * Sign [data] using the device's Keystore-held EC private key.
     *
     * @return Base64-encoded (no-wrap) DER ECDSA signature.
     * @throws Exception if the key is missing, locked, or the signing operation fails.
     */
    fun sign(data: ByteArray): String
}
