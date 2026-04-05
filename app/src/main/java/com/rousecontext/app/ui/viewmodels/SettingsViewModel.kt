package com.rousecontext.app.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rousecontext.api.NotificationSettingsProvider
import com.rousecontext.api.PostSessionMode
import com.rousecontext.app.ui.screens.SettingsState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Reads and writes settings: idle timeout, notification mode,
 * battery optimization status.
 */
class SettingsViewModel(
    private val notificationSettingsProvider: NotificationSettingsProvider
) : ViewModel() {

    private val refreshTrigger = MutableStateFlow(0)

    val state: StateFlow<SettingsState> = refreshTrigger
        .map {
            val settings = notificationSettingsProvider.settings
            SettingsState(
                postSessionMode = settings.postSessionMode.toDisplayString()
            )
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = SettingsState()
        )

    fun setIdleTimeout(minutes: Int) {
        // TODO: persist to DataStore when idle timeout DataStore is added
        refresh()
    }

    fun setPostSessionMode(mode: String) {
        // TODO: persist to notification settings DataStore
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            refreshTrigger.value++
        }
    }

    companion object {
        private const val STOP_TIMEOUT_MS = 5_000L

        private fun PostSessionMode.toDisplayString(): String = when (this) {
            PostSessionMode.SUMMARY -> "Summary"
            PostSessionMode.EACH_USAGE -> "Each usage"
            PostSessionMode.SUPPRESS -> "Suppress"
        }
    }
}
