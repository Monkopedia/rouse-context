package com.rousecontext.app.session

import com.rousecontext.bridge.TlsCertProvider
import com.rousecontext.tunnel.CertificateStore
import com.rousecontext.tunnel.DeviceKeyManager
import java.io.ByteArrayInputStream
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Base64
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext

/**
 * Production [TlsCertProvider] implementation that builds a server-side [SSLContext]
 * from the PEM-encoded certificate chain held by [CertificateStore] and the hardware-backed
 * device private key held by [DeviceKeyManager].
 *
 * Each call to [serverSslContext] rebuilds the context from fresh PEM + the current
 * Keystore reference. This is cheap relative to the TLS handshake and avoids stale-cert
 * issues after renewal. If the certificate is missing, returns null so the caller can
 * surface a meaningful "device not onboarded" error.
 *
 * Issue #200: the private key is a Keystore-backed JCA handle -- its bytes never surface
 * in app memory. The SSLEngine drives signing through the Keystore provider at handshake
 * time.
 */
class CertStoreTlsCertProvider(
    private val certStore: CertificateStore,
    private val deviceKeyManager: DeviceKeyManager
) : TlsCertProvider {

    override suspend fun serverSslContext(): SSLContext? {
        val certPem = certStore.getCertificate() ?: return null

        val certs = parsePemCertificates(certPem)
        require(certs.isNotEmpty()) { "No certificates found in stored PEM" }

        // Load the hardware-backed keypair. The PrivateKey instance we obtain is a JCA
        // handle into the Android Keystore -- JSSE initialises signing through the
        // Keystore provider at TLS handshake time.
        val keyPair = deviceKeyManager.getOrCreateKeyPair()

        val keyStore = KeyStore.getInstance(KeyStore.getDefaultType())
        keyStore.load(null, null)
        keyStore.setKeyEntry(
            "device",
            keyPair.private,
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
}
