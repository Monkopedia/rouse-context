package com.rousecontext.app.state

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
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
            val (mode, showAll) = runBlocking {
                val prefs = dataStore.data.first()
                val modeStored = prefs[POST_SESSION_MODE_KEY]
                val parsedMode = modeStored?.let {
                    try {
                        PostSessionMode.valueOf(it)
                    } catch (_: IllegalArgumentException) {
                        PostSessionMode.SUMMARY
                    }
                } ?: PostSessionMode.SUMMARY
                val showAllStored = prefs[SHOW_ALL_MCP_MESSAGES_KEY] ?: false
                parsedMode to showAllStored
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
                notificationPermissionGranted = permissionGranted,
                showAllMcpMessages = showAll
            )
        }

    override suspend fun setPostSessionMode(mode: PostSessionMode) {
        dataStore.edit { prefs ->
            prefs[POST_SESSION_MODE_KEY] = mode.name
        }
    }

    override suspend fun setShowAllMcpMessages(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[SHOW_ALL_MCP_MESSAGES_KEY] = enabled
        }
    }

    companion object {
        private val POST_SESSION_MODE_KEY = stringPreferencesKey("post_session_mode")
        private val SHOW_ALL_MCP_MESSAGES_KEY = booleanPreferencesKey("show_all_mcp_messages")
    }
}
