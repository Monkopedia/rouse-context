package com.rousecontext.tunnel

import java.net.Socket
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.X509Certificate
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedKeyManager

/**
 * [X509ExtendedKeyManager] that resolves the device's client certificate lazily
 * on every TLS handshake via [MtlsCertSource.current].
 *
 * This solves the chicken-and-egg between onboarding (`/register`,
 * `/register/certs`, `/request-subdomain`) and the mTLS-required endpoints
 * (`/rotate-secret`, `/renew`) described in issue #237. A single long-lived
 * HTTP client can front all of them: when the source returns `null` we hand
 * the SSL engine no alias (presenting no client cert, which the relay
 * accepts on unauthenticated paths), and as soon as provisioning writes a
 * cert to disk the next handshake picks it up without any client rebuild.
 *
 * Hardware-backed keys (Android Keystore / StrongBox) work transparently:
 * the [PrivateKey] we return is a JCA handle into the Keystore provider, and
 * the SSL engine routes signing through that provider at handshake time. The
 * raw key bytes never surface in app memory. See [DirectX509KeyManager]-style
 * construction used elsewhere in the project for the same reason.
 */
class LazyMtlsKeyManager(private val source: MtlsCertSource) : X509ExtendedKeyManager() {

    override fun getClientAliases(keyType: String?, issuers: Array<Principal>?): Array<String>? =
        if (source.current() != null) arrayOf(ALIAS) else null

    override fun chooseClientAlias(
        keyType: Array<String>?,
        issuers: Array<Principal>?,
        socket: Socket?
    ): String? = if (source.current() != null) ALIAS else null

    override fun chooseEngineClientAlias(
        keyType: Array<String>?,
        issuers: Array<Principal>?,
        engine: SSLEngine?
    ): String? = if (source.current() != null) ALIAS else null

    // Server-side aliases are not used by an HTTP client. Return null so that
    // any accidental server-side use fails loudly instead of presenting the
    // client identity as a server cert.
    override fun getServerAliases(keyType: String?, issuers: Array<Principal>?): Array<String>? =
        null

    override fun chooseServerAlias(
        keyType: String?,
        issuers: Array<Principal>?,
        socket: Socket?
    ): String? = null

    override fun chooseEngineServerAlias(
        keyType: String?,
        issuers: Array<Principal>?,
        engine: SSLEngine?
    ): String? = null

    override fun getCertificateChain(alias: String?): Array<X509Certificate>? {
        if (alias != ALIAS) return null
        val identity = source.current() ?: return null
        return identity.certChain.toTypedArray()
    }

    override fun getPrivateKey(alias: String?): PrivateKey? {
        if (alias != ALIAS) return null
        return source.current()?.privateKey
    }

    companion object {
        /**
         * Arbitrary alias string handed to the SSL engine. Must be stable
         * across all `choose*Alias` / `getCertificateChain` / `getPrivateKey`
         * calls within a single handshake — JSSE uses it as a lookup key.
         */
        const val ALIAS: String = "device"
    }
}
