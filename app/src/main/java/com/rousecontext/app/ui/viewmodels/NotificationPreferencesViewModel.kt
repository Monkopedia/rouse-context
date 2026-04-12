package com.rousecontext.app.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rousecontext.api.NotificationSettingsProvider
import com.rousecontext.api.PostSessionMode
import com.rousecontext.app.ui.screens.NotificationMode
import com.rousecontext.app.ui.screens.NotificationPreferencesState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Backs the one-time onboarding [NotificationPreferencesScreen]. Reads the
 * persisted post-session notification mode so the previously-selected
 * option is pre-checked if the user revisits the screen, and writes the
 * selected mode to the shared [NotificationSettingsProvider] used by the
 * notifications module.
 *
 * The setting is also editable from the global Settings screen; both
 * surfaces read from and write to the same provider.
 */
class NotificationPreferencesViewModel(
    private val notificationSettingsProvider: NotificationSettingsProvider
) : ViewModel() {

    private val _state = MutableStateFlow(NotificationPreferencesState())
    val state: StateFlow<NotificationPreferencesState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val current = notificationSettingsProvider.settings.postSessionMode
            _state.value = NotificationPreferencesState(selectedMode = current.toUiMode())
        }
    }

    /** Update local selection (does not persist until [persistSelection]). */
    fun select(mode: NotificationMode) {
        _state.value = NotificationPreferencesState(selectedMode = mode)
    }

    /**
     * Persist the currently-selected mode. Called when the user taps
     * Continue on the onboarding preferences screen.
     */
    fun persistSelection() {
        val mode = _state.value.selectedMode.toDomain()
        viewModelScope.launch {
            notificationSettingsProvider.setPostSessionMode(mode)
        }
    }

    companion object {
        internal fun PostSessionMode.toUiMode(): NotificationMode = when (this) {
            PostSessionMode.SUMMARY -> NotificationMode.SUMMARY
            PostSessionMode.EACH_USAGE -> NotificationMode.EACH_USAGE
            PostSessionMode.SUPPRESS -> NotificationMode.SUPPRESS
        }

        internal fun NotificationMode.toDomain(): PostSessionMode = when (this) {
            NotificationMode.SUMMARY -> PostSessionMode.SUMMARY
            NotificationMode.EACH_USAGE -> PostSessionMode.EACH_USAGE
            NotificationMode.SUPPRESS -> PostSessionMode.SUPPRESS
        }
    }
}
