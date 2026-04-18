package com.rousecontext.work

import android.app.ForegroundServiceStartNotAllowedException
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.google.firebase.messaging.FirebaseMessaging
import com.rousecontext.bridge.SessionHandler
import com.rousecontext.mcp.core.ProviderRegistry
import com.rousecontext.notifications.FgsLimitNotifier
import com.rousecontext.notifications.ForegroundNotifier
import com.rousecontext.notifications.NotificationChannels
import com.rousecontext.notifications.SessionSummaryNotifier
import com.rousecontext.tunnel.TunnelClient
import com.rousecontext.tunnel.TunnelState
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.koin.android.ext.android.inject
import org.koin.core.qualifier.named

/**
 * Foreground service that keeps the tunnel alive during active MCP sessions.
 *
 * Responsibilities:
 * - Shows an ongoing notification (required by Android for foreground services)
 * - Manages wakelocks via [WakelockManager]
 * - Manages idle timeout via [IdleTimeoutManager]
 * - Connects and disconnects the [TunnelClient]
 *
 * Dependencies are injected via Koin. The :app module registers [TunnelClient],
 * [WakelockManager], and [IdleTimeoutManager] in its Koin module.
 */
class TunnelForegroundService : LifecycleService() {

    private val tunnelClient: TunnelClient by inject()
    private val sessionHandler: SessionHandler by inject()
    private val wakelockManager: WakelockManager by inject()
    private val idleTimeoutManager: IdleTimeoutManager by inject()
    private val providerRegistry: ProviderRegistry by inject()
    private val sessionSummaryNotifier: SessionSummaryNotifier by inject()
    private val securityCheckPreferences: SecurityCheckPreferences by inject()
    private val relayUrl: String by inject(named("relayUrl"))

    /** Set true when idle timeout fires or user explicitly stops - suppresses reconnect. */
    @Volatile
    var intentionalDisconnect = false

    private var reconnectJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        NotificationChannels.createAll(this)

        if (!startForegroundSafely()) {
            // FGS daily time-limit exhausted. User notification already posted;
            // stop the service. The next FCM wake will naturally retry
            // startForeground when the 24h rolling budget gives us time back.
            stopSelf()
            return
        }

        // Launch observer coroutines exactly once. These collect StateFlows and
        // SharedFlows, so they run for the lifetime of the service. Launching them
        // in onStartCommand caused duplicates on every wake broadcast, leading to
        // conflicting reconnect attempts and spurious disconnects.
        lifecycleScope.launch { wakelockManager.observe(tunnelClient.state) }
        lifecycleScope.launch { idleTimeoutManager.observe(tunnelClient.state) }
        lifecycleScope.launch { sessionSummaryNotifier.observe(tunnelClient.state) }
        lifecycleScope.launch { collectIncomingSessions() }
        lifecycleScope.launch { observeStateChanges() }

        Log.i(TAG, "TunnelForegroundService created")
    }

    /**
     * Attempts to enter the foreground. Returns true on success, false if the
     * Android 6-hour daily dataSync FGS budget is exhausted (API 31+). On
     * failure, a user-visible notification is posted explaining the situation.
     */
    private fun startForegroundSafely(): Boolean {
        val notification = ForegroundNotifier.build(this, "Connecting...")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return startForegroundApi31OrHigher(notification)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        return true
    }

    private fun startForegroundApi31OrHigher(notification: android.app.Notification): Boolean =
        try {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
            true
        } catch (e: ForegroundServiceStartNotAllowedException) {
            Log.w(
                TAG,
                "startForeground blocked — FGS daily time limit exhausted. " +
                    "Posting user notification and stopping service.",
                e
            )
            FgsLimitNotifier.postLimitReachedNotification(this)
            false
        }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        if (providerRegistry.enabledPaths().isEmpty()) {
            Log.i(TAG, "No integrations enabled, stopping service")
            stopSelf()
            return START_NOT_STICKY
        }

        // Reset flags from any previous service instance — Koin singletons
        // and this service's own state may carry over from an idle-timeout stop.
        intentionalDisconnect = false
        idleTimeoutManager.resetTimeout()

        lifecycleScope.launch { connectToRelay() }

        return START_NOT_STICKY
    }

    private suspend fun connectToRelay() {
        when (val action = WakeReconnectDecider.decide(tunnelClient, HEALTH_CHECK_TIMEOUT)) {
            WakeAction.AlreadyConnecting -> {
                Log.i(TAG, "Already connecting, skipping")
                return
            }
            is WakeAction.Reconnect -> {
                val currentState = tunnelClient.state.value
                if (action.wasStale) {
                    Log.w(TAG, "Tunnel reports ACTIVE but failed health check, reconnecting")
                } else if (currentState == TunnelState.ACTIVE) {
                    // Health check passed but relay sent FCM anyway — relay lost
                    // its WebSocket mapping (idle timeout, restart, etc.). See #243.
                    Log.w(
                        TAG,
                        "Tunnel health check passed but relay requested wake, reconnecting"
                    )
                }
                if (currentState == TunnelState.ACTIVE || currentState == TunnelState.CONNECTED) {
                    if (currentState == TunnelState.CONNECTED) {
                        Log.i(TAG, "Already connected, disconnecting first to refresh")
                    }
                    // Mark intentional so the state observer doesn't trigger reconnect
                    // during the brief DISCONNECTED window before we call connect() below.
                    intentionalDisconnect = true
                    try {
                        tunnelClient.disconnect()
                    } catch (_: Exception) {
                        // Best-effort
                    }
                }
            }
        }
        // Clear the flag before connecting so future unexpected disconnects
        // will be handled normally.
        intentionalDisconnect = false
        try {
            tunnelClient.connect(relayUrl)
            sendFcmToken()
            triggerOpportunisticSecurityCheck()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to connect to relay", e)
        }
    }

    private suspend fun triggerOpportunisticSecurityCheck() {
        val lastCheck = securityCheckPreferences.lastCheckAt()
        val elapsed = System.currentTimeMillis() - lastCheck
        if (elapsed > STALE_CHECK_THRESHOLD_MS) {
            Log.i(TAG, "Last security check was ${elapsed / 3_600_000}h ago, triggering check")
            val request = OneTimeWorkRequestBuilder<SecurityCheckWorker>().build()
            WorkManager.getInstance(this).enqueueUniqueWork(
                SecurityCheckWorker.WORK_NAME + "_opportunistic",
                ExistingWorkPolicy.REPLACE,
                request
            )
        }
    }

    private suspend fun sendFcmToken() {
        try {
            val token = suspendCancellableCoroutine { cont ->
                FirebaseMessaging.getInstance().token
                    .addOnSuccessListener { cont.resume(it) }
                    .addOnFailureListener { cont.resumeWithException(it) }
            }
            tunnelClient.sendFcmToken(token)
            Log.i(TAG, "Sent FCM token to relay")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to send FCM token to relay", e)
        }
    }

    private suspend fun collectIncomingSessions() {
        tunnelClient.incomingSessions.collect { stream ->
            lifecycleScope.launch {
                Log.i(TAG, "New mux stream ${stream.id}, starting session handler")
                try {
                    sessionHandler.handleStream(stream)
                } catch (e: Exception) {
                    Log.e(TAG, "Session handler failed for stream ${stream.id}", e)
                } finally {
                    Log.i(TAG, "Session ended for stream ${stream.id}")
                }
            }
        }
    }

    private suspend fun observeStateChanges() {
        tunnelClient.state.collect { state ->
            updateNotification(state)
            when (state) {
                TunnelState.CONNECTED -> {
                    reconnectJob?.cancel()
                    reconnectJob = null
                }
                TunnelState.DISCONNECTED -> {
                    if (idleTimeoutManager.timeoutFired) {
                        intentionalDisconnect = true
                    }
                    if (intentionalDisconnect) {
                        Log.i(TAG, "Intentional disconnect, stopping service")
                        stopSelf()
                    } else {
                        Log.w(TAG, "Unexpected disconnect, attempting reconnect")
                        launchReconnect()
                    }
                }
                else -> { /* no action */ }
            }
        }
    }

    override fun onDestroy() {
        Log.i(TAG, "TunnelForegroundService destroyed")
        intentionalDisconnect = true
        reconnectJob?.cancel()
        reconnectJob = null
        lifecycleScope.launch {
            tunnelClient.disconnect()
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    private fun updateNotification(state: TunnelState) {
        val message = when (state) {
            TunnelState.DISCONNECTED -> "Disconnected"
            TunnelState.CONNECTING -> "Connecting..."
            TunnelState.CONNECTED -> "Connected"
            TunnelState.ACTIVE -> "Active session"
            TunnelState.DISCONNECTING -> "Disconnecting..."
        }
        // Update the foreground notification in place via the same id passed to
        // startForeground(). Android treats a NotificationManager.notify() with
        // the FGS id as an update to the ongoing notification.
        val notification = ForegroundNotifier.build(this, message)
        val manager = getSystemService(android.app.NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun launchReconnect() {
        if (reconnectJob?.isActive == true) return
        reconnectJob = lifecycleScope.launch {
            val startTime = SystemClock.elapsedRealtime()
            var delayMs = INITIAL_RECONNECT_DELAY_MS
            while (true) {
                val elapsed = SystemClock.elapsedRealtime() - startTime
                if (elapsed >= RECONNECT_GIVE_UP_MS) {
                    Log.w(TAG, "Reconnect attempts exceeded ${RECONNECT_GIVE_UP_MS}ms, giving up")
                    intentionalDisconnect = true
                    stopSelf()
                    return@launch
                }
                Log.i(TAG, "Reconnecting in ${delayMs}ms")
                delay(delayMs)
                try {
                    tunnelClient.connect(relayUrl)
                    Log.i(TAG, "Reconnect succeeded")
                    sendFcmToken()
                    return@launch
                } catch (e: Exception) {
                    Log.w(TAG, "Reconnect attempt failed", e)
                }
                delayMs = (delayMs * 2).coerceAtMost(MAX_RECONNECT_DELAY_MS)
            }
        }
    }

    companion object {
        private const val TAG = "TunnelForegroundService"
        const val NOTIFICATION_ID = 1

        private const val INITIAL_RECONNECT_DELAY_MS = 1_000L
        private const val MAX_RECONNECT_DELAY_MS = 30_000L
        private const val RECONNECT_GIVE_UP_MS = 5 * 60 * 1000L
        private const val STALE_CHECK_THRESHOLD_MS = 6 * 60 * 60 * 1000L

        /**
         * How long to wait for a mux-level Pong before declaring the tunnel
         * dead on an FCM wake. Kept short because the user-facing Claude
         * request is already stalled; we'd rather reconnect than wait.
         */
        private val HEALTH_CHECK_TIMEOUT = 2.seconds

        fun createWakeLock(service: TunnelForegroundService): WakeLockHandle {
            val pm = service.getSystemService(PowerManager::class.java)
            val lock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "rousecontext:tunnel"
            )
            return RealWakeLockHandle(lock)
        }
    }
}
