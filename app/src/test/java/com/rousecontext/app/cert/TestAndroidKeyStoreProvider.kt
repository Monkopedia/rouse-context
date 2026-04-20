package com.rousecontext.app.cert

import java.security.Provider
import java.security.Security

/**
 * Test-only JCA provider that fakes out the `AndroidKeyStore` keystore type so
 * Robolectric unit tests can call [java.security.KeyStore.getInstance] without
 * the real Android Keystore provider on the classpath.
 *
 * The backing implementation is delegated to the default JKS; no hardware
 * semantics are preserved. That's deliberately enough for the few code paths
 * that merely need a loadable KeyStore handle (e.g. [FileCertificateStore.clear]
 * deleting stale aliases on a factory reset). Any test that needs TEE / StrongBox
 * behaviour must run on-device.
 *
 * Call [install] once at the top of a test class (or in `@Before`); [uninstall]
 * is idempotent and safe to call from `@After` even if [install] was never
 * invoked.
 */
internal object TestAndroidKeyStoreProvider {

    private const val NAME = "AndroidKeyStore"
    private var installed: Provider? = null

    fun install() {
        if (Security.getProvider(NAME) != null) return
        val provider = object : Provider(NAME, 1.0, "Test AndroidKeyStore fake") {
            init {
                // Map the "AndroidKeyStore" type onto the stock JKS implementation
                // from sun.security.provider. The class name is stable across JDK
                // vendors (OpenJDK, Temurin) used in CI; on non-standard JVMs this
                // test would need extension.
                put("KeyStore.AndroidKeyStore", "sun.security.provider.JavaKeyStore\$JKS")
            }
        }
        Security.insertProviderAt(provider, 1)
        installed = provider
    }

    fun uninstall() {
        installed?.let { Security.removeProvider(it.name) }
        installed = null
    }
}
