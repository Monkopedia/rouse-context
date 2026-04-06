package com.rousecontext.tunnel

/**
 * Abstraction over WebSocket client implementations.
 *
 * The tunnel module uses this to stay decoupled from any specific WebSocket library.
 * On Android, the app module provides an OkHttp-backed implementation that properly
 * supports mTLS via JSSE. For JVM tests, a Ktor-based implementation is used.
 */
interface WebSocketFactory {

    /**
     * Open a WebSocket connection to the given URL and deliver events to [listener].
     *
     * The returned [WebSocketHandle] can be used to send binary frames or close
     * the connection.
     *
     * Implementations MUST call [WebSocketListener.onOpen] exactly once on success,
     * or [WebSocketListener.onFailure] exactly once if the connection cannot be
     * established.
     */
    fun connect(url: String, listener: WebSocketListener): WebSocketHandle
}

/**
 * Handle for an open WebSocket connection.
 */
interface WebSocketHandle {

    /**
     * Send a binary frame. Returns true if enqueued successfully, false if the
     * connection is closing/closed.
     */
    suspend fun sendBinary(data: ByteArray): Boolean

    /**
     * Send a text frame. Returns true if enqueued successfully, false if the
     * connection is closing/closed.
     */
    suspend fun sendText(text: String): Boolean

    /**
     * Initiate a graceful close.
     */
    suspend fun close(code: Int = 1000, reason: String = "")
}

/**
 * Callback interface for WebSocket events.
 */
interface WebSocketListener {

    /** Connection established. */
    fun onOpen()

    /** Binary message received. */
    fun onBinaryMessage(data: ByteArray)

    /**
     * Connection closed (normal or abnormal).
     *
     * @param code WebSocket close code
     * @param reason WebSocket close reason
     */
    fun onClosing(code: Int, reason: String)

    /**
     * Connection failed or was lost.
     *
     * @param error the cause of the failure
     */
    fun onFailure(error: Throwable)
}
