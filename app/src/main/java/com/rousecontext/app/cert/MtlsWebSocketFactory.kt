package com.rousecontext.app.cert

import android.content.Context
import android.util.Log
import com.rousecontext.tunnel.WebSocketFactory
import com.rousecontext.tunnel.WebSocketHandle
import com.rousecontext.tunnel.WebSocketListener
import java.io.File
import java.security.KeyFactory
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okio.ByteString
import okio.ByteString.Companion.toByteString

/**
 * Creates a [WebSocketFactory] backed by OkHttp, configured with the device's mTLS
 * client certificate for connecting to the relay WebSocket endpoint.
 *
 * OkHttp uses JSSE (Java's TLS stack) which properly presents client certificates
 * during the TLS handshake, unlike Ktor CIO which silently drops them.
 */
object MtlsWebSocketFactory {

    private const val TAG = "MtlsWebSocketFactory"
    private const val PING_INTERVAL_SECONDS = 15L

    // Use the CLIENT cert (relay CA, clientAuth) for mTLS to the relay,
    // NOT the server cert (ACME, serverAuth) which is for AI clients.
    private const val CERT_PEM_FILE = "rouse_client_cert.pem"
    private const val RELAY_CA_PEM_FILE = "rouse_relay_ca.pem"
    private const val KEY_PEM_FILE = "rouse_key.pem"

    /**
     * Build a [WebSocketFactory] that presents the device client certificate during
     * TLS handshake.
     *
     * @param context Android context for accessing filesDir.
     * @return A [WebSocketFactory] configured for mTLS, or a plain factory if no cert
     *         is available.
     */
    fun create(context: Context): WebSocketFactory {
        val okHttpClient = buildOkHttpClient(context)
        return OkHttpWebSocketFactory(okHttpClient)
    }

    private fun buildOkHttpClient(context: Context): OkHttpClient {
        val certAndKey = loadCertAndKey(context)

        return if (certAndKey != null) {
            Log.i(TAG, "Creating mTLS-configured OkHttpClient")
            val (clientCerts, privateKey, caCert) = certAndKey

            // Build cert chain: leaf first, then issuer (relay CA)
            val fullChain = if (caCert != null) {
                clientCerts + caCert
            } else {
                clientCerts
            }

            val keyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                load(null, null)
                setKeyEntry("client", privateKey, charArrayOf(), fullChain.toTypedArray())
            }

            val keyManagerFactory = KeyManagerFactory.getInstance(
                KeyManagerFactory.getDefaultAlgorithm()
            ).apply {
                init(keyStore, charArrayOf())
            }

            // Trust both system CAs (for Let's Encrypt server cert) and the relay CA
            // (so the KeyManager can match against the relay's CertificateRequest)
            val trustStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                load(null, null)
                if (caCert != null) {
                    setCertificateEntry("relay-ca", caCert)
                }
            }
            // Load system CAs into the same trust store
            val systemTmf = TrustManagerFactory.getInstance(
                TrustManagerFactory.getDefaultAlgorithm()
            ).apply {
                init(null as KeyStore?)
            }
            val systemTm = systemTmf.trustManagers
                .first { it is X509TrustManager } as X509TrustManager
            for ((i, cert) in systemTm.acceptedIssuers.withIndex()) {
                trustStore.setCertificateEntry("system-ca-$i", cert)
            }

            val trustManagerFactory = TrustManagerFactory.getInstance(
                TrustManagerFactory.getDefaultAlgorithm()
            ).apply {
                init(trustStore)
            }

            val trustManager = trustManagerFactory.trustManagers
                .first { it is X509TrustManager } as X509TrustManager

            val sslContext = SSLContext.getInstance("TLS").apply {
                init(keyManagerFactory.keyManagers, trustManagerFactory.trustManagers, null)
            }

            OkHttpClient.Builder()
                .sslSocketFactory(sslContext.socketFactory, trustManager)
                .pingInterval(PING_INTERVAL_SECONDS, TimeUnit.SECONDS)
                .build()
        } else {
            Log.w(TAG, "No device cert available, creating plain OkHttpClient")
            OkHttpClient.Builder()
                .pingInterval(PING_INTERVAL_SECONDS, TimeUnit.SECONDS)
                .build()
        }
    }

    private fun loadCertAndKey(
        context: Context
    ): Triple<List<X509Certificate>, PrivateKey, X509Certificate?>? {
        val certs = loadCertChain(context)
        val privateKey = loadPrivateKey(context)
        if (certs == null || privateKey == null) return null
        val caCert = loadRelayCaCert(context)
        Log.i(
            TAG,
            "Loaded device cert (${certs.size} certs in chain) and private key" +
                if (caCert != null) ", plus relay CA" else ", no relay CA"
        )
        return Triple(certs, privateKey, caCert)
    }

    private fun loadCertChain(context: Context): List<X509Certificate>? {
        val certFile = File(context.filesDir, CERT_PEM_FILE)
        if (!certFile.exists()) {
            Log.w(TAG, "No certificate file found at ${certFile.absolutePath}")
            return null
        }
        val certs = parsePemCertificates(certFile.readText())
        if (certs.isEmpty()) {
            Log.w(TAG, "Certificate chain is empty")
            return null
        }
        return certs
    }

    private fun loadRelayCaCert(context: Context): X509Certificate? {
        val caFile = File(context.filesDir, RELAY_CA_PEM_FILE)
        if (!caFile.exists()) {
            Log.w(TAG, "No relay CA cert file found at ${caFile.absolutePath}")
            return null
        }
        val certs = parsePemCertificates(caFile.readText())
        if (certs.isEmpty()) {
            Log.w(TAG, "Relay CA cert file is empty")
            return null
        }
        return certs.first()
    }

    private fun loadPrivateKey(context: Context): PrivateKey? {
        val keyFile = File(context.filesDir, KEY_PEM_FILE)
        if (!keyFile.exists()) {
            Log.w(TAG, "No private key file found at ${keyFile.absolutePath}")
            return null
        }
        val key = parsePemPrivateKey(keyFile.readText())
        if (key == null) {
            Log.w(TAG, "Failed to parse private key PEM")
        }
        return key
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

/**
 * [WebSocketFactory] backed by OkHttp.
 *
 * OkHttp's WebSocket uses JSSE for TLS, which correctly presents client certificates
 * configured via [SSLContext]/[javax.net.ssl.KeyManager] during the mTLS handshake.
 */
internal class OkHttpWebSocketFactory(
    private val client: OkHttpClient
) : WebSocketFactory {

    override fun connect(url: String, listener: WebSocketListener): WebSocketHandle {
        val request = Request.Builder()
            .url(url)
            .build()

        val ws = client.newWebSocket(
            request,
            object : okhttp3.WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    listener.onOpen()
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    listener.onBinaryMessage(bytes.toByteArray())
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    listener.onClosing(code, reason)
                    webSocket.close(code, reason)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    listener.onFailure(t)
                }
            }
        )

        return OkHttpWebSocketHandle(ws)
    }
}

private class OkHttpWebSocketHandle(private val ws: WebSocket) : WebSocketHandle {

    override suspend fun sendBinary(data: ByteArray): Boolean {
        return ws.send(data.toByteString())
    }

    override suspend fun sendText(text: String): Boolean {
        return ws.send(text)
    }

    override suspend fun close(code: Int, reason: String) {
        ws.close(code, reason)
    }
}
