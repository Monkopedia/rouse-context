package com.rousecontext.tunnel

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.close
import io.ktor.websocket.readBytes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * [WebSocketFactory] backed by Ktor's [HttpClient].
 *
 * Used for JVM tests. NOT suitable for Android mTLS because Ktor CIO engine
 * does not properly present client certificates during TLS handshake.
 *
 * [scope] must be a structured scope owned by the caller (e.g. the surrounding
 * `runBlocking` in tests). The factory does not own a scope of its own — see
 * `.claude/rules/coroutines.md`.
 */
class KtorWebSocketFactory(
    private val scope: CoroutineScope,
    private val httpClient: HttpClient = HttpClient { install(WebSockets) }
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
            } catch (e: CancellationException) {
                // MUST stay above the broad catch (issue #646): a torn-down
                // scope is not a transport failure, so the listener is told
                // nothing -- the mirror of what #644 fixed one layer up in
                // TunnelClientImpl.connect.
                //
                // Gated on the job, not on the exception type. A cancellation
                // that arrives while this coroutine is still active did not come
                // from our teardown -- it came from the session -- and that IS a
                // transport failure the listener has to hear about, or the tunnel
                // sits CONNECTED over a dead socket. Only our own cancellation
                // takes the silent path.
                if (currentCoroutineContext().isActive) {
                    reportFailure(handle, listener, e)
                } else {
                    // The bind is still failed: that is the whole point of
                    // failBind (#420 finding #4), since an awaiter parked in
                    // sendBinary has no other way to learn the session will
                    // never arrive. Completing it with the cancellation releases
                    // the awaiter and tells it the truth.
                    if (!handle.isBound()) handle.failBind(e)
                    throw e
                }
            } catch (e: Exception) {
                reportFailure(handle, listener, e)
            }
        }

        return handle
    }

    /**
     * Tell [listener] the session is gone. Split out so the cancellation clause
     * can reuse it for a session-side cancellation without duplicating the
     * bound / not-yet-bound distinction.
     */
    private fun reportFailure(
        handle: KtorWebSocketHandle,
        listener: WebSocketListener,
        e: Throwable
    ) {
        if (handle.isBound()) {
            listener.onClosing(1006, e.message ?: "Connection lost")
        } else {
            // Awaiters of sendBinary/sendText would otherwise hang forever
            // waiting for sessionDeferred. Propagate the failure so they
            // observe the connect error.
            handle.failBind(e)
            listener.onFailure(e)
        }
    }
}

private class KtorWebSocketHandle : WebSocketHandle {
    private val sessionDeferred = CompletableDeferred<WebSocketSession>()

    fun isBound(): Boolean = sessionDeferred.isCompleted

    fun bind(session: WebSocketSession) {
        sessionDeferred.complete(session)
    }

    fun failBind(error: Throwable) {
        sessionDeferred.completeExceptionally(error)
    }

    override suspend fun sendBinary(data: ByteArray): Boolean {
        val session = sessionDeferred.await()
        session.send(Frame.Binary(true, data))
        return true
    }

    override suspend fun sendText(text: String): Boolean {
        val session = sessionDeferred.await()
        session.send(Frame.Text(text))
        return true
    }

    override suspend fun close(code: Int, reason: String) {
        if (sessionDeferred.isCompleted) {
            sessionDeferred.await().close()
        }
    }
}
