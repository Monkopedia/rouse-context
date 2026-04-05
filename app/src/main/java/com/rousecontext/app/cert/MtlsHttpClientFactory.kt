package com.rousecontext.app.cert

import android.content.Context
import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.network.tls.CertificateAndKey
import java.io.File
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

/**
 * Creates a Ktor [HttpClient] configured with the device's mTLS client certificate
 * for connecting to the relay WebSocket endpoint.
 *
 * The private key lives in the Android Keystore (hardware-backed HSM).
 * The cert chain is stored as PEM on disk.
 */
object MtlsHttpClientFactory {

    private const val TAG = "MtlsHttpClientFactory"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "rouse_device_key"
    private const val CERT_PEM_FILE = "rouse_cert.pem"

    /**
     * Build an [HttpClient] that presents the device client certificate during TLS handshake.
     *
     * Reads the cert from disk and the private key from Android Keystore synchronously.
     * Safe to call from Koin module initialization (runs on main thread during app startup).
     *
     * @param context Android context for accessing filesDir.
     * @return An [HttpClient] configured for mTLS, or a plain client if no cert is available.
     */
    fun create(context: Context): HttpClient {
        val certAndKey = loadCertAndKey(context)

        return if (certAndKey != null) {
            Log.i(TAG, "Creating mTLS-configured HttpClient")
            HttpClient(CIO) {
                engine {
                    https {
                        certificates.add(certAndKey)
                    }
                }
                install(WebSockets)
            }
        } else {
            Log.w(TAG, "No device cert available, creating plain HttpClient")
            HttpClient(CIO) {
                install(WebSockets)
            }
        }
    }

    private fun loadCertAndKey(context: Context): CertificateAndKey? {
        val certFile = File(context.filesDir, CERT_PEM_FILE)
        if (!certFile.exists()) {
            Log.w(TAG, "No certificate file found at ${certFile.absolutePath}")
            return null
        }

        val pemChain = certFile.readText()
        val certs = parsePemCertificates(pemChain)
        if (certs.isEmpty()) {
            Log.w(TAG, "Certificate chain is empty")
            return null
        }

        val androidKeyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
        androidKeyStore.load(null)

        if (!androidKeyStore.containsAlias(KEY_ALIAS)) {
            Log.w(TAG, "Private key alias '$KEY_ALIAS' not found in Android Keystore")
            return null
        }

        val privateKey = androidKeyStore.getKey(KEY_ALIAS, null) as? PrivateKey
        if (privateKey == null) {
            Log.w(TAG, "Key entry '$KEY_ALIAS' is not a PrivateKey")
            return null
        }

        Log.i(TAG, "Loaded device cert (${certs.size} certs in chain) and private key")
        return CertificateAndKey(certs.toTypedArray(), privateKey)
    }

    private fun parsePemCertificates(pem: String): List<X509Certificate> {
        val factory = CertificateFactory.getInstance("X.509")
        val certs = mutableListOf<X509Certificate>()
        val regex = Regex(
            "-----BEGIN CERTIFICATE-----(.+?)-----END CERTIFICATE-----",
            RegexOption.DOT_MATCHES_ALL
        )
        for (match in regex.findAll(pem)) {
            val base64 = match.groupValues[1].replace("\\s".toRegex(), "")
            val der = java.util.Base64.getDecoder().decode(base64)
            val cert = factory.generateCertificate(der.inputStream()) as X509Certificate
            certs.add(cert)
        }
        return certs
    }
}
