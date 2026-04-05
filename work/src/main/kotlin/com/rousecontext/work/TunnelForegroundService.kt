package com.rousecontext.work

import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.rousecontext.notifications.NotificationChannels
import com.rousecontext.notifications.createForegroundNotification
import com.rousecontext.tunnel.TunnelClient
import com.rousecontext.tunnel.TunnelState
import kotlinx.coroutines.launch
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
    private val wakelockManager: WakelockManager by inject()
    private val idleTimeoutManager: IdleTimeoutManager by inject()
    private val relayUrl: String by inject(named("relayUrl"))

    override fun onCreate() {
        super.onCreate()
        NotificationChannels.createAll(this)

        val notification = createForegroundNotification(this, "Connecting...")
        startForeground(
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
        Log.i(TAG, "TunnelForegroundService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        lifecycleScope.launch {
            try {
                tunnelClient.connect(relayUrl)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to connect to relay", e)
            }
        }

        lifecycleScope.launch {
            wakelockManager.observe(tunnelClient.state)
        }

        lifecycleScope.launch {
            idleTimeoutManager.observe(tunnelClient.state)
        }

        lifecycleScope.launch {
            tunnelClient.state.collect { state ->
                updateNotification(state)
                if (state == TunnelState.DISCONNECTED) {
                    Log.i(TAG, "Tunnel disconnected, stopping service")
                    stopSelf()
                }
            }
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        Log.i(TAG, "TunnelForegroundService destroyed")
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
        val notification = createForegroundNotification(this, message)
        val manager = getSystemService(android.app.NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification)
    }

    companion object {
        private const val TAG = "TunnelForegroundService"
        const val NOTIFICATION_ID = 1

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
