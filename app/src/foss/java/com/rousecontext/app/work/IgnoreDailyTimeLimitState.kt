package com.rousecontext.app.work

import com.rousecontext.app.state.AppStatePreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * In-memory, synchronously-readable mirror of the persisted
 * [AppStatePreferences.ignoreDailyTimeLimit] flag.
 *
 * [TunnelForegroundService][com.rousecontext.work.TunnelForegroundService] must
 * decide its foreground-service type synchronously inside `onCreate`/
 * `startForeground` (the call must be the first non-trivial work, with no
 * suspension). DataStore reads are async, so we collect the flag on the
 * long-lived app scope into a [Volatile] field and expose a non-suspending
 * [isEnabled].
 *
 * The "ignore daily time limit" use case is a legitimately long, *active* day,
 * during which the process is warm and this cache is populated; the brief
 * cold-start window where the cache still holds its `false` default before the
 * first emission is harmless (the service simply starts as `dataSync`, as it
 * always did).
 */
class IgnoreDailyTimeLimitState(
    appStatePreferences: AppStatePreferences,
    appScope: CoroutineScope
) {

    @Volatile
    private var enabled: Boolean = false

    init {
        appScope.launch {
            appStatePreferences.observeIgnoreDailyTimeLimit().collect { value ->
                enabled = value
            }
        }
    }

    fun isEnabled(): Boolean = enabled
}
