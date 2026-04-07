package com.rousecontext.app.state

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** User-selectable theme mode. */
enum class ThemeMode {
    LIGHT,
    DARK,
    AUTO
}

private val Context.themeDataStore: DataStore<Preferences>
    by preferencesDataStore(name = "theme_settings")

/**
 * DataStore-backed preference for the app theme.
 * Defaults to [ThemeMode.AUTO] (follow system).
 */
class ThemePreference(private val context: Context) {

    private val dataStore get() = context.themeDataStore

    val themeMode: Flow<ThemeMode> = dataStore.data.map { prefs ->
        val stored = prefs[THEME_KEY]
        stored?.let {
            try {
                ThemeMode.valueOf(it)
            } catch (_: IllegalArgumentException) {
                ThemeMode.AUTO
            }
        } ?: ThemeMode.AUTO
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        dataStore.edit { prefs ->
            prefs[THEME_KEY] = mode.name
        }
    }

    companion object {
        private val THEME_KEY = stringPreferencesKey("theme_mode")
    }
}
