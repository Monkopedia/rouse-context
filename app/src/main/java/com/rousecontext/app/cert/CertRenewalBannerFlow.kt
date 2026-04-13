package com.rousecontext.app.cert

import android.content.SharedPreferences
import com.rousecontext.app.ui.screens.CertBanner
import com.rousecontext.app.ui.screens.TerminalReason
import com.rousecontext.work.CertRenewalWorker
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Surfaces terminal [CertRenewalWorker] outcomes as a dashboard [CertBanner].
 *
 * The worker writes an outcome name to [CertRenewalWorker.KEY_LAST_OUTCOME] on every run
 * ([CertRenewalWorker.PREFS_NAME]). Only the terminal outcomes
 * ([CertRenewalWorker.Outcome.KEY_GEN_FAILED], [CertRenewalWorker.Outcome.CN_MISMATCH])
 * map to a banner — all other outcomes emit `null`. These two conditions are unrecoverable
 * from inside the app: the keystore is broken, or the relay is handing out a cert whose CN
 * doesn't match the device's subdomain. Surfacing them as a persistent banner is the honest
 * minimum we can do per issue #88.
 */
fun certRenewalBannerFlow(prefs: SharedPreferences): Flow<CertBanner?> = callbackFlow {
    fun emitCurrent() {
        trySend(readBanner(prefs))
    }

    val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == CertRenewalWorker.KEY_LAST_OUTCOME) {
            emitCurrent()
        }
    }
    prefs.registerOnSharedPreferenceChangeListener(listener)
    emitCurrent()
    awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
}.distinctUntilChanged()

private fun readBanner(prefs: SharedPreferences): CertBanner? {
    val outcome = prefs.getString(CertRenewalWorker.KEY_LAST_OUTCOME, null) ?: return null
    return when (outcome) {
        CertRenewalWorker.Outcome.KEY_GEN_FAILED.name ->
            CertBanner.TerminalFailure(TerminalReason.KeyGenerationFailed)
        CertRenewalWorker.Outcome.CN_MISMATCH.name ->
            CertBanner.TerminalFailure(TerminalReason.CnMismatch)
        else -> null
    }
}
