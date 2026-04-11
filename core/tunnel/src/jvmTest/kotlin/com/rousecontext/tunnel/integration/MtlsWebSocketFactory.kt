package com.rousecontext.tunnel.integration

import com.rousecontext.tunnel.WebSocketFactory
import com.rousecontext.tunnel.WebSocketHandle
import com.rousecontext.tunnel.WebSocketListener
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.nio.ByteBuffer
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import kotlinx.coroutines.CompletableDeferred

/**
 * [WebSocketFactory] backed by Java's [HttpClient] with mTLS support.
 *
 * Unlike the Ktor CIO engine, Java's HTTP client properly presents client
 * certificates during TLS handshake, making it suitable for connecting
 * to the relay as a device.
 */
class MtlsWebSocketFactory(private val sslContext: SSLContext) : WebSocketFactory {

    override fun connect(url: String, listener: WebSocketListener): WebSocketHandle {
        val handle = JavaWebSocketHandle()

        val javaListener = object : WebSocket.Listener {
            override fun onOpen(webSocket: WebSocket) {
                webSocket.request(1)
                handle.bind(webSocket)
                listener.onOpen()
            }

            override fun onBinary(
                webSocket: WebSocket,
                data: ByteBuffer,
                last: Boolean
            ): CompletionStage<*> {
                val bytes = ByteArray(data.remaining())
                data.get(bytes)
                listener.onBinaryMessage(bytes)
                webSocket.request(1)
                return CompletableFuture.completedFuture(null)
            }

            override fun onClose(
                webSocket: WebSocket,
                statusCode: Int,
                reason: String
            ): CompletionStage<*> {
                listener.onClosing(statusCode, reason)
                return CompletableFuture.completedFuture(null)
            }

            override fun onError(webSocket: WebSocket, error: Throwable) {
                if (!handle.isBound()) {
                    listener.onFailure(error)
                } else {
                    listener.onClosing(1006, error.message ?: "Connection lost")
                }
            }
        }

        val client = HttpClient.newBuilder()
            .sslContext(sslContext)
            .build()

        try {
            client.newWebSocketBuilder()
                .buildAsync(URI.create(url), javaListener)
        } catch (e: Exception) {
            listener.onFailure(e)
        }

        return handle
    }
}

private class JavaWebSocketHandle : WebSocketHandle {
    private val wsDeferred = CompletableDeferred<WebSocket>()

    fun isBound(): Boolean = wsDeferred.isCompleted

    fun bind(ws: WebSocket) {
        wsDeferred.complete(ws)
    }

    override suspend fun sendBinary(data: ByteArray): Boolean {
        val ws = wsDeferred.await()
        ws.sendBinary(ByteBuffer.wrap(data), true)
            .get(TIMEOUT_SECS, TimeUnit.SECONDS)
        return true
    }

    override suspend fun sendText(text: String): Boolean {
        val ws = wsDeferred.await()
        ws.sendText(text, true)
            .get(TIMEOUT_SECS, TimeUnit.SECONDS)
        return true
    }

    override suspend fun close(code: Int, reason: String) {
        if (wsDeferred.isCompleted) {
            val ws = wsDeferred.await()
            ws.sendClose(code, reason)
                .get(TIMEOUT_SECS, TimeUnit.SECONDS)
        }
    }

    companion object {
        private const val TIMEOUT_SECS = 10L
    }
}
