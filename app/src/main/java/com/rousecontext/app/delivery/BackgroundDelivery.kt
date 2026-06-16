package com.rousecontext.app.delivery

import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Flavor seam for the background-delivery (on-demand wake) transport.
 *
 * The `google` flavor wakes via Firebase Cloud Messaging and binds
 * [NoOpBackgroundDelivery] — [isSupported] is `false`, so none of the
 * UnifiedPush onboarding/Settings/banner surfaces appear. The `foss` flavor
 * wakes via [UnifiedPush](https://unifiedpush.org/) and binds a real
 * implementation backed by the `org.unifiedpush.android:connector` library
 * (issue #463).
 *
 * Because that library is a `fossImplementation` dependency, no shared (`main`)
 * code may import it. All UnifiedPush API calls therefore live behind this
 * interface, which lets the picker screen, the degraded-Home banner, and the
 * Settings row stay in the shared source set, driven by flavor-agnostic state.
 *
 * ## Deferred activation (the foss model, option A — see docs/ux-decisions.md)
 *
 * A foss device has no push endpoint until the user picks a distributor, and
 * the relay requires a push target to register. So foss registration is
 * **deferred**: onboarding can complete with no distributor (a skipped device
 * reaches a degraded Home, [DeliveryActivation.NeedsSetup]); choosing a
 * delivery app later reports its endpoint, registers the device, and activates
 * it ([DeliveryActivation.Active]).
 */
interface BackgroundDelivery {
    /**
     * Whether this flavor uses an explicit background-delivery picker (foss).
     * Drives picker visibility, the Welcome → picker onboarding step, the
     * Settings row, the degraded banner, and deferred-registration timing.
     */
    val isSupported: Boolean

    /** Activation state for the degraded-Home banner and Settings subtitle. */
    val activation: StateFlow<DeliveryActivation>

    /**
     * Whether a push endpoint has arrived from the active distributor (issue
     * #480). The picker waits on this after the user selects a distributor; if
     * it stays `false` past a short timeout — the freshly-installed distributor
     * "stopped state" case, where Android drops our `REGISTER` broadcast until
     * the app is launched once — the picker surfaces a nudge to open the chosen
     * distributor. Flips to `true` when the endpoint arrives, clearing the
     * nudge. Seeded `true` for already-set-up devices.
     */
    val endpointArrived: StateFlow<Boolean>

    /**
     * Build the picker rows from the currently-installed UnifiedPush
     * distributors. Suggests ntfy + "install another app" only when **no**
     * distributor is installed; otherwise lists exactly what's installed plus
     * "install another app" (never injects an "install ntfy" row). Re-invoked
     * on `ON_RESUME` so returning from a store install refreshes the list.
     */
    fun distributorOptions(): List<DistributorOption>

    /**
     * User picked an installed distributor: persist the choice and register
     * with UnifiedPush. The endpoint arrives asynchronously via the flavor's
     * push receiver, which reports it to the relay and triggers (or refreshes)
     * registration. No-op for the `google` flavor.
     */
    fun selectDistributor(id: String)

    /**
     * Intent that opens the store page (F-Droid/Play) to install the
     * distributor an [DistributorOption.Kind.INSTALL_NTFY] /
     * [DistributorOption.Kind.INSTALL_OTHER] row points at, or `null` if none.
     */
    fun installIntent(option: DistributorOption): Intent?

    /** Display name of the active distributor for the Settings subtitle, or null. */
    fun activeDistributorName(): String?

    /**
     * Launch intent for an installed distributor (issue #480), or `null` if it
     * has no launcher entry / isn't installed. Used by the picker nudge to open
     * the chosen distributor, which clears Android's "stopped" state so the
     * distributor finally processes our UnifiedPush `REGISTER` and mints an
     * endpoint. No-op (null) for the `google` flavor.
     */
    fun launchIntent(id: String): Intent?
}

/**
 * No-op [BackgroundDelivery] for flavors that wake via FCM (google) and as the
 * default for view-models in tests. Reports [DeliveryActivation.NotApplicable]
 * and offers no distributors, so the picker/banner/Settings surfaces never show.
 */
object NoOpBackgroundDelivery : BackgroundDelivery {
    override val isSupported: Boolean = false
    override val activation: StateFlow<DeliveryActivation> =
        MutableStateFlow(DeliveryActivation.NotApplicable).asStateFlow()
    override val endpointArrived: StateFlow<Boolean> =
        MutableStateFlow(false).asStateFlow()
    override fun distributorOptions(): List<DistributorOption> = emptyList()
    override fun selectDistributor(id: String) = Unit
    override fun installIntent(option: DistributorOption): Intent? = null
    override fun activeDistributorName(): String? = null
    override fun launchIntent(id: String): Intent? = null
}

/** On-demand wake activation state, surfaced to the shared Home/Settings UI. */
enum class DeliveryActivation {
    /** Flavor doesn't use UnifiedPush (google/FCM): no banner, no picker, no row. */
    NotApplicable,

    /** Registered with a reporting push endpoint — on-demand wake works. */
    Active,

    /** Onboarding finished but no distributor/endpoint yet — degraded; show banner. */
    NeedsSetup
}

/**
 * One row in the "Background delivery" picker. An installed distributor is a
 * one-tap [Kind.INSTALLED] target; the install-suggestion rows deep-link to a
 * store via [BackgroundDelivery.installIntent].
 */
data class DistributorOption(
    /** UnifiedPush distributor package id, or "" for install-suggestion rows. */
    val id: String,
    /** Display name ("ntfy", "NextPush", "Install another app", …). */
    val name: String,
    /** Sub-label ("Installed", "Active", "Recommended · not installed", …). */
    val subtitle: String,
    val kind: Kind
) {
    enum class Kind {
        /** An installed distributor the user can tap to use. */
        INSTALLED,

        /** The currently-active distributor (Settings mode). */
        ACTIVE,

        /** Cold-start ntfy suggestion (shown only when nothing is installed). */
        INSTALL_NTFY,

        /** Generic "install another app" escape hatch. */
        INSTALL_OTHER
    }
}
