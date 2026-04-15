package com.rousecontext.bridge

import com.rousecontext.tunnel.TunnelClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Collects incoming sessions from [TunnelClient.incomingSessions] and handles each
 * one using a [SessionHandler].
 *
 * Each incoming mux stream is handled in its own child coroutine, allowing concurrent
 * MCP sessions. If one session fails, others continue independently.
 *
 * Usage:
 * ```
 * val manager = TunnelSessionManager(tunnelClient, sessionHandler, lifecycleScope)
 * manager.start()
 * // Sessions are handled automatically until the scope is cancelled
 * ```
 */
class TunnelSessionManager(
    private val tunnelClient: TunnelClient,
    private val sessionHandler: SessionHandler,
    private val scope: CoroutineScope
) {

    private var collectionJob: Job? = null

    /**
     * Starts collecting incoming sessions. Safe to call multiple times;
     * subsequent calls are no-ops if already running.
     */
    fun start() {
        if (collectionJob?.isActive == true) return

        collectionJob = scope.launch {
            tunnelClient.incomingSessions.collect { stream ->
                launch {
                    try {
                        sessionHandler.handleStream(stream)
                    } catch (_: Exception) {
                        // Individual session failure should not crash the manager.
                        // Errors are logged at the session level.
                    }
                }
            }
        }
    }

    /**
     * Stops collecting incoming sessions. Active sessions continue until they
     * finish or the parent scope is cancelled.
     */
    fun stop() {
        collectionJob?.cancel()
        collectionJob = null
    }
}
