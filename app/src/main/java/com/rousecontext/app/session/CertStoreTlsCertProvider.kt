package com.rousecontext.app.session

import com.rousecontext.bridge.TlsCertProvider
import com.rousecontext.tunnel.CertificateStore
import java.io.ByteArrayInputStream
import java.security.KeyFactory
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext

/**
 * Production [TlsCertProvider] implementation that builds a server-side [SSLContext]
 * from the PEM-encoded certificate and private key held by [CertificateStore].
 *
 * Each call to [serverSslContext] rebuilds the context from fresh PEM. This is
 * cheap relative to the TLS handshake and avoids stale-cert issues after renewal.
 * If either the certificate or key is missing, returns null so the caller can
 * surface a meaningful "device not onboarded" error.
 */
class CertStoreTlsCertProvider(private val certStore: CertificateStore) : TlsCertProvider {

    override suspend fun serverSslContext(): SSLContext? {
        val certPem = certStore.getCertificate() ?: return null
        val keyPem = certStore.getPrivateKey() ?: return null

        val certs = parsePemCertificates(certPem)
        require(certs.isNotEmpty()) { "No certificates found in stored PEM" }
        val privateKey = parsePemPrivateKey(keyPem)

        val keyStore = KeyStore.getInstance(KeyStore.getDefaultType())
        keyStore.load(null, null)
        keyStore.setKeyEntry(
            "device",
            privateKey,
            charArrayOf(),
            certs.toTypedArray()
        )

        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(keyStore, charArrayOf())

        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(kmf.keyManagers, null, null)
        return sslContext
    }

    private fun parsePemCertificates(pem: String): List<X509Certificate> {
        val factory = CertificateFactory.getInstance("X.509")
        val regex = Regex(
            "-----BEGIN CERTIFICATE-----(.+?)-----END CERTIFICATE-----",
            RegexOption.DOT_MATCHES_ALL
        )
        return regex.findAll(pem).map { match ->
            val base64 = match.groupValues[1].replace("\\s".toRegex(), "")
            val der = Base64.getDecoder().decode(base64)
            factory.generateCertificate(ByteArrayInputStream(der)) as X509Certificate
        }.toList()
    }

    private fun parsePemPrivateKey(pem: String): PrivateKey {
        val body = pem
            .replace("-----BEGIN PRIVATE KEY-----", "")
            .replace("-----END PRIVATE KEY-----", "")
            .replace("\\s".toRegex(), "")
        val der = Base64.getDecoder().decode(body)
        val spec = PKCS8EncodedKeySpec(der)
        // Try EC first — post-#173 CsrGenerator emits ECDSA P-256. RSA kept for
        // devices that still have pre-#173 keys stored.
        return try {
            KeyFactory.getInstance("EC").generatePrivate(spec)
        } catch (_: Exception) {
            KeyFactory.getInstance("RSA").generatePrivate(spec)
        }
    }
}
