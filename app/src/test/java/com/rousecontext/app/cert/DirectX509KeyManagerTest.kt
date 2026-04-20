package com.rousecontext.app.cert

import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import javax.net.ssl.SSLContext
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [DirectX509KeyManager].
 *
 * The class is a thin [javax.net.ssl.X509ExtendedKeyManager] that hands back a
 * single (private-key, cert-chain) pair regardless of which alias / keyType /
 * issuer the TLS engine requests. The SSL engine invokes these methods during
 * the mTLS handshake; we prove every overridden method returns the fixed values
 * so a future accidental NPE / wrong-alias bug surfaces here instead of as a
 * handshake failure on a real device.
 *
 * No real TLS handshake: the [SSLContext] wiring is covered by the app's
 * integration-tier tests (and by the production relay). These unit tests hold
 * the per-method contract, not the TLS behaviour.
 */
class DirectX509KeyManagerTest {

    private lateinit var privateKey: PrivateKey
    private lateinit var leafCert: X509Certificate
    private lateinit var intermediateCert: X509Certificate
    private lateinit var manager: DirectX509KeyManager

    @Before
    fun setUp() {
        // Software EC keypair: the manager itself does not care whether the key
        // lives in hardware — it just holds a reference — so a plain JDK keypair
        // is enough to exercise getPrivateKey().
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256r1"))
        val kp = kpg.generateKeyPair()
        privateKey = kp.private

        leafCert = CertTestUtil.generateSelfSignedCert("direct-km-leaf")
        intermediateCert = CertTestUtil.generateSelfSignedCert("direct-km-intermediate")
        manager = DirectX509KeyManager(privateKey, arrayOf(leafCert, intermediateCert))
    }

    @Test
    fun `getClientAliases returns the fixed alias for any keyType and issuers`() {
        val aliases = manager.getClientAliases("EC", null)
        assertArrayEquals(arrayOf("device"), aliases)

        // Null keyType and non-null issuers must behave identically: the alias
        // is fixed irrespective of the TLS engine's hint.
        val aliases2 = manager.getClientAliases(null, emptyArray())
        assertArrayEquals(arrayOf("device"), aliases2)
    }

    @Test
    fun `chooseClientAlias returns the fixed alias for any socket`() {
        val chosen = manager.chooseClientAlias(arrayOf("EC", "RSA"), null, null)
        assertEquals("device", chosen)
        // Second call with a different (non-null) socket must still resolve to the
        // same alias — the manager MUST NOT remember per-socket state (the engine
        // may ask multiple times during renegotiation).
        val chosenAgain = manager.chooseClientAlias(null, null, java.net.Socket())
        assertEquals(chosen, chosenAgain)
    }

    @Test
    fun `chooseEngineClientAlias returns the fixed alias when engine is null`() {
        // [javax.net.ssl.X509ExtendedKeyManager] is called with a non-null
        // [javax.net.ssl.SSLEngine] in production. A null here should not NPE.
        val chosen = manager.chooseEngineClientAlias(arrayOf("EC"), null, null)
        assertTrue(chosen == "device")
    }

    @Test
    fun `getServerAliases returns the fixed alias`() {
        val aliases = manager.getServerAliases("EC", null)
        assertArrayEquals(arrayOf("device"), aliases)
    }

    @Test
    fun `chooseServerAlias returns the fixed alias`() {
        val chosen = manager.chooseServerAlias("EC", null, null)
        assertTrue(chosen == "device")
    }

    @Test
    fun `chooseEngineServerAlias returns the fixed alias`() {
        // Server-side engine path — reachable from `SSLEngine.getNeedClientAuth`
        // flows when the same [DirectX509KeyManager] instance is reused as a
        // server-side key source (e.g. for tests that spin up a TLS server on
        // loopback). Covered for completeness.
        val chosen = manager.chooseEngineServerAlias("EC", null, null)
        assertTrue(chosen == "device")
    }

    @Test
    fun `getCertificateChain returns the configured chain for any alias`() {
        val chain = manager.getCertificateChain("device")
        assertNotNull(chain)
        assertArrayEquals(arrayOf(leafCert, intermediateCert), chain)

        // Crucially, the chain lookup does not validate the alias string: JSSE
        // may pass any non-null string (even the empty one), and the manager
        // must still answer rather than return null.
        val chainForOther = manager.getCertificateChain("other")
        assertArrayEquals(arrayOf(leafCert, intermediateCert), chainForOther)

        // Null alias is defensive — JSSE should never pass it in practice, but
        // the override signature accepts nullable.
        val chainForNull = manager.getCertificateChain(null)
        assertNotNull(chainForNull)
    }

    @Test
    fun `getPrivateKey returns the configured key by reference`() {
        // Reference identity matters for hardware-backed keys: the returned
        // PrivateKey may be a `AndroidKeyStoreECKey` whose signing is routed
        // through the TEE provider. Wrapping or copying would break signing.
        val returned = manager.getPrivateKey("device")
        assertSame(privateKey, returned)

        // Same reference regardless of alias (see the chain test).
        assertSame(privateKey, manager.getPrivateKey("other"))
        assertSame(privateKey, manager.getPrivateKey(null))
    }

    @Test
    fun `installed into SSLContext as a KeyManager does not throw`() {
        // The manager must satisfy SSLContext.init()'s expectations on the
        // KeyManager contract. If any return were null where JSSE expects a
        // non-null response, init() itself may still accept, but the first
        // handshake would NPE. This smoke-test at least proves init() succeeds
        // against a real SSLContext so we catch blatant contract violations.
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(arrayOf(manager), null, null)
        assertNotNull(ctx.socketFactory)
    }
}
