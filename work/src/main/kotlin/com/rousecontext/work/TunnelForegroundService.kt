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

/**
 * Foreground service that keeps the tunnel alive during active MCP sessions.
 *
 * Responsibilities:
 * - Shows an ongoing notification (required by Android for foreground services)
 * - Manages wakelocks via [WakelockManager]
 * - Manages idle timeout via [IdleTimeoutManager]
 * - Connects and disconnects the [TunnelClient]
 *
 * The [tunnelClient], [wakelockManager], and [idleTimeoutManager] are injected
 * by the :app module (via Koin or manual DI in onCreate).
 */
class TunnelForegroundService : LifecycleService() {

    /** Injected by :app module. */
    lateinit var tunnelClient: TunnelClient

    /** Injected by :app module. */
    lateinit var wakelockManager: WakelockManager

    /** Injected by :app module. */
    lateinit var idleTimeoutManager: IdleTimeoutManager

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
            tunnelClient.connect()
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
