package com.rousecontext.app.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rousecontext.app.support.CrashReportingPreference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Drives the first-run crash-reporting consent sheet (issue #546).
 *
 * The sheet is shown **exactly once**, and every exit counts: "Turn on", "Not
 * now", a swipe-dismiss and the back gesture all record that the question was
 * asked, so there is deliberately no second prompt. Settings › Support is the
 * only other route in, and dismissing leaves reporting off.
 *
 * On the google distribution [CrashReportingPreference.shouldAskForConsent]
 * is always false, so the sheet never composes.
 */
class CrashReportConsentViewModel(private val crashReportingPreference: CrashReportingPreference) :
    ViewModel() {

    private val _visible = MutableStateFlow(false)

    /** Whether the consent sheet should currently be on screen. */
    val visible: StateFlow<Boolean> = _visible.asStateFlow()

    init {
        viewModelScope.launch {
            _visible.value = crashReportingPreference.shouldAskForConsent()
        }
    }

    /** "Turn on": enable collection, persist it, and never ask again. */
    fun turnOn() {
        _visible.value = false
        viewModelScope.launch {
            crashReportingPreference.setOptIn(true)
            crashReportingPreference.markConsentAsked()
        }
    }

    /**
     * Any exit that is not "Turn on" — the "Not now" button, a swipe-dismiss,
     * or back. Reporting stays off; the question is not asked again.
     */
    fun dismiss() {
        _visible.value = false
        viewModelScope.launch {
            crashReportingPreference.markConsentAsked()
        }
    }
}
