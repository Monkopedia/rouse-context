package com.rousecontext.app.state

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.rousecontext.api.NotificationSettings
import com.rousecontext.api.NotificationSettingsProvider
import com.rousecontext.api.PostSessionMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

private val Context.notificationDataStore: DataStore<Preferences>
    by preferencesDataStore(name = "notification_settings")

/**
 * [NotificationSettingsProvider] backed by Preferences DataStore.
 */
class DataStoreNotificationSettingsProvider(private val context: Context) :
    NotificationSettingsProvider {

    private val dataStore get() = context.notificationDataStore

    override val settings: NotificationSettings
        get() {
            val mode = runBlocking {
                val stored = dataStore.data.first()[POST_SESSION_MODE_KEY]
                stored?.let {
                    try {
                        PostSessionMode.valueOf(it)
                    } catch (_: IllegalArgumentException) {
                        PostSessionMode.SUMMARY
                    }
                } ?: PostSessionMode.SUMMARY
            }

            val permissionGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
            } else {
                true
            }

            return NotificationSettings(
                postSessionMode = mode,
                notificationPermissionGranted = permissionGranted
            )
        }

    companion object {
        private val POST_SESSION_MODE_KEY = stringPreferencesKey("post_session_mode")
    }
}
