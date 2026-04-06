package com.rousecontext.tunnel

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.close
import io.ktor.websocket.readBytes
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * [WebSocketFactory] backed by Ktor's [HttpClient].
 *
 * Used for JVM tests. NOT suitable for Android mTLS because Ktor CIO engine
 * does not properly present client certificates during TLS handshake.
 */
class KtorWebSocketFactory(
    private val httpClient: HttpClient = HttpClient { install(WebSockets) },
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO)
) : WebSocketFactory {

    override fun connect(url: String, listener: WebSocketListener): WebSocketHandle {
        val handle = KtorWebSocketHandle()

        scope.launch {
            try {
                val session = httpClient.webSocketSession(url)
                handle.bind(session)
                listener.onOpen()

                for (frame in session.incoming) {
                    if (frame is Frame.Binary) {
                        listener.onBinaryMessage(frame.readBytes())
                    }
                }
                // Normal close
                listener.onClosing(1000, "WebSocket closed")
            } catch (e: Exception) {
                if (handle.isBound()) {
                    listener.onClosing(1006, e.message ?: "Connection lost")
                } else {
                    listener.onFailure(e)
                }
            }
        }

        return handle
    }
}

private class KtorWebSocketHandle : WebSocketHandle {
    private val sessionDeferred = CompletableDeferred<WebSocketSession>()

    fun isBound(): Boolean = sessionDeferred.isCompleted

    fun bind(session: WebSocketSession) {
        sessionDeferred.complete(session)
    }

    override suspend fun sendBinary(data: ByteArray): Boolean {
        val session = sessionDeferred.await()
        session.send(Frame.Binary(true, data))
        return true
    }

    override suspend fun close(code: Int, reason: String) {
        if (sessionDeferred.isCompleted) {
            sessionDeferred.await().close()
        }
    }
}
