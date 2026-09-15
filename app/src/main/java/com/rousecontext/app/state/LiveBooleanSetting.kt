package com.rousecontext.app.state

import android.util.Log
import com.rousecontext.mcp.core.ReadinessGate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/**
 * A single boolean user setting from [IntegrationSettingsStore], held as a
 * live value that MCP tool calls can consult per invocation.
 *
 * Three properties matter for consent controls. The first two come from the
 * `direct_launch_enabled` opt-in that this generalises (see issue #419
 * finding #2):
 *
 * 1. **Live, not captured.** [current] reads the latest emission, so revoking
 *    consent applies to the next tool call rather than the next app start.
 * 2. **Readiness-gated.** The value is loaded asynchronously from DataStore.
 *    Until the first emission lands the in-memory value is [default], so a
 *    tool call racing process spawn would otherwise read a value the user
 *    never chose. [current] suspends on [awaitReady] first.
 * 3. **Bounded on failure.** A read that fails resolves to [default] and
 *    releases the gate, so a caller gets a refusal it can return rather than a
 *    call that never completes (issue #751). [default] is therefore the deny
 *    value, and every consent use of this type passes `false`.
 *
 * Reads that must not suspend (Compose state, `isDirty` checks) should keep
 * going through [PreferencesSnapshotHolder]; this type is for the tool path.
 */
class LiveBooleanSetting(
    settingsStore: IntegrationSettingsStore,
    integrationId: String,
    key: String,
    private val default: Boolean = false,
    appScope: CoroutineScope
) {

    private val _value = MutableStateFlow(default)

    /** Latest loaded value, or [default] before the first emission lands. */
    val value: StateFlow<Boolean> = _value.asStateFlow()

    private val readinessGate = ReadinessGate()

    init {
        appScope.launch {
            settingsStore.observeBoolean(integrationId, key, default)
                // A read that FAILS must deny by returning, not by never
                // returning (issue #751). Preferences DataStore surfaces a
                // corrupt or unreadable file as an exception on `data`, and no
                // corruption handler is configured; without this the collector
                // died before signalling and every gated tool call hung.
                // `Flow.catch` rethrows cancellation of the collecting scope,
                // so this cannot swallow a shutdown.
                .catch { e ->
                    Log.w(TAG, "settings read failed for $integrationId/$key; denying", e)
                    emit(default)
                }
                .onEach { _value.value = it }
                // Idempotent: later emissions no-op on an already-ready gate.
                .collect { readinessGate.signalReady() }
        }
    }

    /** Suspends until the setting has been loaded from disk at least once. */
    suspend fun awaitReady() {
        readinessGate.awaitReady()
    }

    /** Thread-blocking variant of [awaitReady], for non-coroutine callers. */
    fun awaitReadyBlocking(timeoutMs: Long): Boolean = readinessGate.awaitReadyBlocking(timeoutMs)

    /**
     * Returns the user's stored choice, suspending until it has been loaded
     * at least once. This is the call tool gating should use.
     */
    suspend fun current(): Boolean {
        awaitReady()
        return _value.value
    }

    private companion object {
        const val TAG = "LiveBooleanSetting"
    }
}
