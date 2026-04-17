package com.rousecontext.tunnel

import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec

/**
 * Test fake for [DeviceKeyManager] that generates a single software ECDSA P-256
 * keypair on first call and returns the same instance on every subsequent call.
 *
 * Production uses a hardware-backed [DeviceKeyManager] (see
 * `AndroidKeystoreDeviceKeyManager` in `:app`); this fake is only suitable for
 * JVM unit/integration tests where no Android runtime is available.
 */
class InMemoryDeviceKeyManager(seed: KeyPair? = null) : DeviceKeyManager {

    @Volatile
    private var keyPair: KeyPair? = seed

    override fun getOrCreateKeyPair(): KeyPair {
        val existing = keyPair
        if (existing != null) return existing
        synchronized(this) {
            val still = keyPair
            if (still != null) return still
            val generated = generateP256()
            keyPair = generated
            return generated
        }
    }

    private fun generateP256(): KeyPair {
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256r1"))
        return kpg.generateKeyPair()
    }
}
