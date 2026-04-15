package com.rousecontext.work

/**
 * Records wake-cycle outcomes so the app can surface a diagnostic when the relay
 * appears to be issuing spurious wakes (wakes without any AI client actually
 * opening an MCP stream).
 *
 * A "wake cycle" spans from entering the CONNECTED state to returning to
 * DISCONNECTED. It is "spurious" when no ACTIVE transition was observed during
 * the cycle, meaning a wake fired but no stream ever opened.
 *
 * Production implementation is [DataStoreSpuriousWakeRecorder]. Tests pass a
 * simple in-memory fake.
 */
interface SpuriousWakeRecorder {

    /**
     * Called exactly once per completed wake cycle.
     *
     * @param hadActiveStream true if at least one ACTIVE state was observed
     *   during the cycle; false for a spurious wake.
     */
    suspend fun recordWakeCycle(hadActiveStream: Boolean)
}

/**
 * DataStore-backed [SpuriousWakeRecorder]. All writes happen inside a single
 * `edit` transaction, so the counter fields and timestamp ring buffer cannot
 * drift out of sync on a concurrent write.
 */
class DataStoreSpuriousWakeRecorder(
    private val prefs: SpuriousWakePreferences,
    private val clock: () -> Long = { System.currentTimeMillis() }
) : SpuriousWakeRecorder {

    override suspend fun recordWakeCycle(hadActiveStream: Boolean) {
        prefs.recordWake(hadActiveStream = hadActiveStream, now = clock())
    }
}
