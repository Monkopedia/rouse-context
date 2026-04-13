package com.rousecontext.app.state

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart

/**
 * Observes whether the user has granted permission to post notifications.
 *
 * On Android 13+ (API 33) the runtime [Manifest.permission.POST_NOTIFICATIONS]
 * permission must be granted before any notification is delivered. On older
 * Android versions the permission is implicit, but the user can still revoke
 * notifications from system settings, in which case
 * [NotificationManagerCompat.areNotificationsEnabled] reflects that.
 */
object NotificationPermissionMonitor {

    /**
     * Returns true when the app may currently post notifications: the runtime
     * permission is granted (on Android 13+) and the user has not disabled
     * notifications for the app via system settings.
     */
    fun areNotificationsEnabled(context: Context): Boolean {
        val runtimeGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        return runtimeGranted && NotificationManagerCompat.from(context).areNotificationsEnabled()
    }
}

/**
 * Maps a trigger flow (emit anything to re-check) into a flow of "are
 * notifications enabled". An initial value is emitted immediately on
 * collection. Callers should drive [triggers] from lifecycle events
 * (ON_RESUME) and after a permission-launcher result so the dashboard
 * banner reflects current state.
 */
fun notificationPermissionFlow(context: Context, triggers: Flow<Unit>): Flow<Boolean> = triggers
    .onStart { emit(Unit) }
    .map { NotificationPermissionMonitor.areNotificationsEnabled(context) }
    .distinctUntilChanged()

/**
 * Singleton fan-out for "re-check notification permission". Compose code
 * (lifecycle observers, permission launcher result callbacks) calls
 * [refresh] to push a tick; subscribers like the dashboard view-model map
 * each tick into a fresh permission read via [notificationPermissionFlow].
 */
class NotificationPermissionRefresher {
    private val _ticks = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val ticks: SharedFlow<Unit> = _ticks.asSharedFlow()
    fun refresh() {
        _ticks.tryEmit(Unit)
    }
}
