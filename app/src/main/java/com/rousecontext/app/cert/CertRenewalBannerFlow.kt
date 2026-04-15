package com.rousecontext.app.cert

import com.rousecontext.app.ui.screens.CertBanner
import com.rousecontext.app.ui.screens.TerminalReason
import com.rousecontext.work.CertRenewalPreferences
import com.rousecontext.work.CertRenewalWorker
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Surfaces terminal [CertRenewalWorker] outcomes as a dashboard [CertBanner].
 *
 * The worker writes an outcome name through [CertRenewalPreferences] on every
 * run. Only the terminal outcomes
 * ([CertRenewalWorker.Outcome.KEY_GEN_FAILED], [CertRenewalWorker.Outcome.CN_MISMATCH])
 * map to a banner — all other outcomes emit `null`. These two conditions are
 * unrecoverable from inside the app: the keystore is broken, or the relay is
 * handing out a cert whose CN doesn't match the device's subdomain. Surfacing
 * them as a persistent banner is the honest minimum we can do per issue #88.
 */
fun certRenewalBannerFlow(preferences: CertRenewalPreferences): Flow<CertBanner?> =
    preferences.observeLastOutcome()
        .map { outcome -> outcome?.let(::outcomeToBanner) }
        .distinctUntilChanged()

private fun outcomeToBanner(outcome: String): CertBanner? = when (outcome) {
    CertRenewalWorker.Outcome.KEY_GEN_FAILED.name ->
        CertBanner.TerminalFailure(TerminalReason.KeyGenerationFailed)
    CertRenewalWorker.Outcome.CN_MISMATCH.name ->
        CertBanner.TerminalFailure(TerminalReason.CnMismatch)
    else -> null
}
