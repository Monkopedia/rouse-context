package com.rousecontext.app.cert

import java.net.Socket
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.X509Certificate
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedKeyManager

/**
 * A [X509ExtendedKeyManager] that directly exposes a single key + cert chain
 * to the TLS engine without going through [java.security.KeyStore].
 *
 * Required for Android Keystore (TEE/StrongBox) backed keys: those keys
 * return `null` from [PrivateKey.getEncoded], making them incompatible
 * with generic keystore serialization and Conscrypt's setEntry bridging.
 * This manager hands the SSL engine a direct `PrivateKey` reference; the
 * engine drives signing through the key's provider (AndroidKeyStore)
 * transparently.
 */
class DirectX509KeyManager(
    private val privateKey: PrivateKey,
    private val certChain: Array<X509Certificate>
) : X509ExtendedKeyManager() {

    override fun getClientAliases(keyType: String?, issuers: Array<Principal>?) = arrayOf(ALIAS)

    override fun chooseClientAlias(
        keyType: Array<String>?,
        issuers: Array<Principal>?,
        socket: Socket?
    ) = ALIAS

    override fun chooseEngineClientAlias(
        keyType: Array<String>?,
        issuers: Array<Principal>?,
        engine: SSLEngine?
    ) = ALIAS

    override fun getServerAliases(keyType: String?, issuers: Array<Principal>?) = arrayOf(ALIAS)

    override fun chooseServerAlias(keyType: String?, issuers: Array<Principal>?, socket: Socket?) =
        ALIAS

    override fun chooseEngineServerAlias(
        keyType: String?,
        issuers: Array<Principal>?,
        engine: SSLEngine?
    ) = ALIAS

    override fun getCertificateChain(alias: String?) = certChain

    override fun getPrivateKey(alias: String?) = privateKey

    companion object {
        private const val ALIAS = "device"
    }
}
