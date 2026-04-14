package com.rousecontext.mcp.core

import io.modelcontextprotocol.kotlin.sdk.shared.Transport
import io.modelcontextprotocol.kotlin.sdk.shared.TransportSendOptions
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCMessage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

/**
 * A Transport implementation for HTTP Streamable MCP.
 *
 * Each HTTP POST delivers a JSON-RPC request via [handleRequest], which feeds it
 * to the SDK Server's message handler. The server's response is captured and
 * returned to the HTTP handler.
 *
 * This transport is long-lived per integration, persisting across HTTP requests.
 * The SDK Server is connected once and processes all subsequent requests.
 */
internal class HttpTransport : Transport {

    private var messageHandler: (suspend (JSONRPCMessage) -> Unit)? = null
    private var closeHandler: (() -> Unit)? = null
    private var errorHandler: ((Throwable) -> Unit)? = null

    private var currentDeferred: CompletableDeferred<JSONRPCMessage>? = null

    override suspend fun start() {
        // Nothing to do -- messages arrive via handleRequest
    }

    override suspend fun send(message: JSONRPCMessage, options: TransportSendOptions?) {
        // Server is sending a response. Complete the pending deferred.
        currentDeferred?.complete(message)
    }

    override suspend fun close() {
        closeHandler?.invoke()
    }

    override fun onClose(block: () -> Unit) {
        closeHandler = block
    }

    override fun onError(block: (Throwable) -> Unit) {
        errorHandler = block
    }

    override fun onMessage(block: suspend (JSONRPCMessage) -> Unit) {
        messageHandler = block
    }

    /**
     * Handles an incoming HTTP JSON-RPC request. Feeds the message to the SDK Server
     * and returns the response.
     *
     * Must be called from a single thread/coroutine at a time per transport
     * (serialized by the Ktor request handler).
     *
     * @throws TimeoutCancellationException if the SDK server
     *   does not respond within [REQUEST_TIMEOUT_MS].
     */
    suspend fun handleRequest(request: JSONRPCMessage): JSONRPCMessage {
        val deferred = CompletableDeferred<JSONRPCMessage>()
        currentDeferred = deferred

        val handler = checkNotNull(messageHandler) {
            "Transport not started: no message handler"
        }

        try {
            handler(request)
        } catch (e: Exception) {
            deferred.completeExceptionally(e)
        }

        return withTimeout(REQUEST_TIMEOUT_MS) {
            deferred.await()
        }
    }

    /**
     * Handles an incoming JSON-RPC notification (no response expected).
     * Feeds the message to the SDK Server without waiting for a response.
     */
    suspend fun handleNotification(notification: JSONRPCMessage) {
        val handler = messageHandler ?: return
        handler(notification)
    }

    companion object {
        /** Timeout for SDK server to produce a response (30 seconds). */
        const val REQUEST_TIMEOUT_MS = 30_000L
    }
}
