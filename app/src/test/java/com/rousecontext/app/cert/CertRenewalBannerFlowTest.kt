package com.rousecontext.app.cert

import com.rousecontext.app.ui.screens.CertBanner
import com.rousecontext.app.ui.screens.TerminalReason
import com.rousecontext.work.CertRenewalPreferences
import com.rousecontext.work.CertRenewalWorker
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for [certRenewalBannerFlow].
 *
 * The flow is a thin mapping layer: only the two terminal [CertRenewalWorker.Outcome]
 * values surface as a [CertBanner]; everything else emits `null`. These tests hold
 * the mapping table down so a future rename of an outcome constant or reordering of
 * the `when` branches cannot silently drop a banner in production.
 */
class CertRenewalBannerFlowTest {

    @Test
    fun `KEY_GEN_FAILED outcome maps to KeyGenerationFailed banner`() = runBlocking {
        val preferences = mockk<CertRenewalPreferences>()
        every { preferences.observeLastOutcome() } returns
            flowOf(CertRenewalWorker.Outcome.KEY_GEN_FAILED.name)

        val emissions = certRenewalBannerFlow(preferences).toList()

        assertEquals(
            listOf(CertBanner.TerminalFailure(TerminalReason.KeyGenerationFailed)),
            emissions
        )
    }

    @Test
    fun `CN_MISMATCH outcome maps to CnMismatch banner`() = runBlocking {
        val preferences = mockk<CertRenewalPreferences>()
        every { preferences.observeLastOutcome() } returns
            flowOf(CertRenewalWorker.Outcome.CN_MISMATCH.name)

        val emissions = certRenewalBannerFlow(preferences).toList()

        assertEquals(
            listOf(CertBanner.TerminalFailure(TerminalReason.CnMismatch)),
            emissions
        )
    }

    @Test
    fun `non-terminal outcome maps to null banner`() = runBlocking {
        // SUCCESS / SKIP_* / retryable-failure outcomes are recoverable and must
        // NOT surface a banner. Spot-check a representative handful to guard the
        // `else -> null` branch against accidental fall-through if a new terminal
        // case is added without updating this mapping.
        val preferences = mockk<CertRenewalPreferences>()
        every { preferences.observeLastOutcome() } returns flowOf(
            CertRenewalWorker.Outcome.SUCCESS.name,
            CertRenewalWorker.Outcome.SKIP_NO_CERT.name,
            CertRenewalWorker.Outcome.SKIP_VALID.name,
            CertRenewalWorker.Outcome.NETWORK_ERROR.name,
            CertRenewalWorker.Outcome.RELAY_ERROR.name
        )

        val emissions = certRenewalBannerFlow(preferences).toList()

        // distinctUntilChanged collapses the run of identical `null` emissions.
        assertEquals(listOf<CertBanner?>(null), emissions)
    }

    @Test
    fun `null outcome (never recorded) maps to null banner`() = runBlocking {
        // Fresh-install state: the worker has not yet run, so the DataStore value is
        // absent. The flow must emit `null` rather than crash on an NPE lookup.
        val preferences = mockk<CertRenewalPreferences>()
        every { preferences.observeLastOutcome() } returns flowOf(null)

        val emissions = certRenewalBannerFlow(preferences).toList()

        assertEquals(listOf<CertBanner?>(null), emissions)
    }

    @Test
    fun `unknown outcome string maps to null banner`() = runBlocking {
        // If the worker writes a string that doesn't match any known outcome (e.g.
        // a forward-compatible new enum value persisted by a newer app version),
        // we must degrade gracefully rather than crash: no banner is safer than
        // a spurious one.
        val preferences = mockk<CertRenewalPreferences>()
        every { preferences.observeLastOutcome() } returns flowOf("TOTALLY_MADE_UP")

        val emissions = certRenewalBannerFlow(preferences).toList()

        assertEquals(listOf<CertBanner?>(null), emissions)
    }

    @Test
    fun `distinctUntilChanged collapses repeated terminal emissions`() = runBlocking {
        // If the worker re-records the same terminal outcome on every run, the UI
        // should not recompose the banner repeatedly. Confirm that consecutive
        // duplicates collapse to a single emission.
        val preferences = mockk<CertRenewalPreferences>()
        every { preferences.observeLastOutcome() } returns flowOf(
            CertRenewalWorker.Outcome.KEY_GEN_FAILED.name,
            CertRenewalWorker.Outcome.KEY_GEN_FAILED.name,
            CertRenewalWorker.Outcome.KEY_GEN_FAILED.name
        )

        val emissions = certRenewalBannerFlow(preferences).toList()

        assertEquals(
            listOf(CertBanner.TerminalFailure(TerminalReason.KeyGenerationFailed)),
            emissions
        )
    }

    @Test
    fun `transitions between terminal and null emit both values`() = runBlocking {
        val preferences = mockk<CertRenewalPreferences>()
        every { preferences.observeLastOutcome() } returns flowOf(
            CertRenewalWorker.Outcome.SUCCESS.name,
            CertRenewalWorker.Outcome.KEY_GEN_FAILED.name,
            CertRenewalWorker.Outcome.SUCCESS.name,
            CertRenewalWorker.Outcome.CN_MISMATCH.name
        )

        val emissions = certRenewalBannerFlow(preferences).toList()

        assertEquals(
            listOf<CertBanner?>(
                null,
                CertBanner.TerminalFailure(TerminalReason.KeyGenerationFailed),
                null,
                CertBanner.TerminalFailure(TerminalReason.CnMismatch)
            ),
            emissions
        )
    }
}
