package com.rousecontext.tunnel.integration

import com.rousecontext.tunnel.WebSocketFactory
import com.rousecontext.tunnel.WebSocketHandle
import com.rousecontext.tunnel.WebSocketListener
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.CompletableDeferred
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener as OkWebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString

/**
 * [WebSocketFactory] backed by OkHttp with mTLS support and IPv4-only DNS.
 *
 * Unlike [MtlsWebSocketFactory] (which uses Java's [java.net.http.HttpClient]),
 * this factory resolves every hostname to 127.0.0.1 so non-literal hostnames like
 * `relay.test.internal` work in integration tests without a real DNS entry.
 *
 * OkHttp also avoids the SNI-suppression bug some JDK versions exhibit for
 * `localhost`, keeping the relay's SNI router happy.
 */
class Ipv4OnlyMtlsWebSocketFactory(private val sslContext: SSLContext) : WebSocketFactory {

    override fun connect(url: String, listener: WebSocketListener): WebSocketHandle {
        val handle = OkHttpWebSocketHandle()

        // Build an OkHttpClient with mTLS and IPv4 DNS
        val client = OkHttpClient.Builder()
            .sslSocketFactory(
                sslContext.socketFactory,
                extractTrustManager(sslContext)
            )
            .hostnameVerifier { _, _ -> true }
            .dns(LoopbackDns)
            .connectTimeout(TIMEOUT_SECS, TimeUnit.SECONDS)
            .readTimeout(TIMEOUT_SECS, TimeUnit.SECONDS)
            .writeTimeout(TIMEOUT_SECS, TimeUnit.SECONDS)
            .build()

        val request = Request.Builder().url(url).build()
        val ws = client.newWebSocket(
            request,
            object : OkWebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    handle.bind(webSocket)
                    listener.onOpen()
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    listener.onBinaryMessage(bytes.toByteArray())
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    listener.onClosing(code, reason)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (!handle.isBound()) {
                        listener.onFailure(t)
                    } else {
                        listener.onClosing(1006, t.message ?: "Connection lost")
                    }
                }
            }
        )

        return handle
    }

    companion object {
        private const val TIMEOUT_SECS = 10L

        /**
         * Extract the first [X509TrustManager] from the [SSLContext]'s default
         * [javax.net.ssl.TrustManagerFactory] re-initialized with null (system
         * default). We need a TM instance for OkHttp; our SSLContext already has
         * the right TMs baked in, so we just need a placeholder that OkHttp
         * accepts.
         *
         * In practice the SSLContext we receive was built with an explicit trust
         * store, so the socket factory it produces already enforces the desired
         * trust policy regardless of what TM object we hand OkHttp here.
         */
        private fun extractTrustManager(ctx: SSLContext): X509TrustManager {
            val tmf = javax.net.ssl.TrustManagerFactory.getInstance(
                javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm()
            )
            tmf.init(null as java.security.KeyStore?)
            return tmf.trustManagers.filterIsInstance<X509TrustManager>().first()
        }
    }

    private object LoopbackDns : Dns {
        override fun lookup(hostname: String): List<InetAddress> =
            listOf(InetAddress.getByName("127.0.0.1"))
    }
}

private class OkHttpWebSocketHandle : WebSocketHandle {
    private val wsDeferred = CompletableDeferred<WebSocket>()

    fun isBound(): Boolean = wsDeferred.isCompleted

    fun bind(ws: WebSocket) {
        wsDeferred.complete(ws)
    }

    override suspend fun sendBinary(data: ByteArray): Boolean {
        val ws = wsDeferred.await()
        return ws.send(data.toByteString())
    }

    override suspend fun sendText(text: String): Boolean {
        val ws = wsDeferred.await()
        return ws.send(text)
    }

    override suspend fun close(code: Int, reason: String) {
        if (wsDeferred.isCompleted) {
            val ws = wsDeferred.await()
            ws.close(code, reason)
        }
    }
}
