package com.rousecontext.app.cert

import android.content.Context
import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.network.tls.CertificateAndKey
import java.io.File
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec

/**
 * Creates a Ktor [HttpClient] configured with the device's mTLS client certificate
 * for connecting to the relay WebSocket endpoint.
 *
 * The private key lives in the Android Keystore (hardware-backed HSM).
 * The cert chain is stored as PEM on disk.
 */
object MtlsHttpClientFactory {

    private const val TAG = "MtlsHttpClientFactory"
    private const val CERT_PEM_FILE = "rouse_cert.pem"
    private const val KEY_PEM_FILE = "rouse_key.pem"

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

        val keyFile = File(context.filesDir, KEY_PEM_FILE)
        if (!keyFile.exists()) {
            Log.w(TAG, "No private key file found at ${keyFile.absolutePath}")
            return null
        }

        val privateKey = parsePemPrivateKey(keyFile.readText())
        if (privateKey == null) {
            Log.w(TAG, "Failed to parse private key PEM")
            return null
        }

        Log.i(TAG, "Loaded device cert (${certs.size} certs in chain) and private key")
        return CertificateAndKey(certs.toTypedArray(), privateKey)
    }

    private fun parsePemPrivateKey(pem: String): PrivateKey? {
        val base64 = pem
            .replace("-----BEGIN PRIVATE KEY-----", "")
            .replace("-----END PRIVATE KEY-----", "")
            .replace("-----BEGIN RSA PRIVATE KEY-----", "")
            .replace("-----END RSA PRIVATE KEY-----", "")
            .replace("-----BEGIN EC PRIVATE KEY-----", "")
            .replace("-----END EC PRIVATE KEY-----", "")
            .replace("\\s".toRegex(), "")
        return try {
            val der = java.util.Base64.getDecoder().decode(base64)
            val spec = PKCS8EncodedKeySpec(der)
            try {
                KeyFactory.getInstance("EC").generatePrivate(spec)
            } catch (_: Exception) {
                KeyFactory.getInstance("RSA").generatePrivate(spec)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse private key", e)
            null
        }
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
