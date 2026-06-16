package com.rousecontext.app.delivery

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import com.rousecontext.app.auth.DeviceCredentialProvider
import com.rousecontext.app.state.DeviceRegistrationStatus
import com.rousecontext.tunnel.CertificateStore
import com.rousecontext.tunnel.OnboardingFlow
import com.rousecontext.tunnel.OnboardingResult
import com.rousecontext.tunnel.TunnelClient
import com.rousecontext.tunnel.TunnelState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.unifiedpush.android.connector.UnifiedPush

/**
 * `foss` implementation of [BackgroundDelivery] backed by
 * [UnifiedPush](https://unifiedpush.org/) (issue #463). Wraps the
 * `org.unifiedpush.android:connector` library so all of its API surface stays
 * out of the shared (`main`) source set.
 *
 * ## Deferred activation (option A — see docs/ux-decisions.md, 2026-06-13)
 *
 * A foss device has no push endpoint until the user picks a distributor, and
 * the relay requires a push target to register. So registration is deferred:
 * onboarding completes with no distributor ([DeliveryActivation.NeedsSetup],
 * degraded Home + banner). When the user picks a delivery app, UnifiedPush
 * eventually delivers an endpoint to `UnifiedPushReceiver`, which calls
 * [onEndpoint] — that reports the endpoint to the relay (registering the device
 * on first endpoint, or refreshing it over the tunnel WS afterwards) and flips
 * activation to [DeliveryActivation.Active].
 */
class UnifiedPushBackgroundDelivery(
    private val appContext: Context,
    private val onboardingFlow: OnboardingFlow,
    private val credentialProvider: DeviceCredentialProvider,
    private val certificateStore: CertificateStore,
    private val registrationStatus: DeviceRegistrationStatus,
    private val tunnelClient: TunnelClient,
    private val appScope: CoroutineScope
) : BackgroundDelivery {

    override val isSupported: Boolean = true

    private val _activation = MutableStateFlow(DeliveryActivation.NeedsSetup)
    override val activation: StateFlow<DeliveryActivation> = _activation.asStateFlow()

    private val registerMutex = Mutex()
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    init {
        // Seed activation from persisted state: a device with a subdomain is
        // already registered and active; otherwise it needs a delivery app.
        appScope.launch {
            _activation.value = if (certificateStore.getSubdomain() != null) {
                DeliveryActivation.Active
            } else {
                DeliveryActivation.NeedsSetup
            }
        }
    }

    override fun distributorOptions(): List<DistributorOption> {
        val installed = UnifiedPush.getDistributors(appContext).map { id ->
            DistributorOptionsBuilder.Installed(id = id, name = resolveLabel(id))
        }
        val active = UnifiedPush.getAckDistributor(appContext)
            ?: UnifiedPush.getSavedDistributor(appContext)
        return DistributorOptionsBuilder.build(installed = installed, activeId = active)
    }

    override fun selectDistributor(id: String) {
        if (id.isBlank()) return
        UnifiedPush.saveDistributor(appContext, id)
        // Triggers the distributor to mint an endpoint, delivered async to
        // UnifiedPushReceiver.onNewEndpoint -> onEndpoint().
        UnifiedPush.registerApp(appContext)
    }

    override fun installIntent(option: DistributorOption): Intent? {
        val url = when (option.kind) {
            DistributorOption.Kind.INSTALL_NTFY ->
                "https://f-droid.org/packages/${DistributorOptionsBuilder.NTFY_PACKAGE}/"
            DistributorOption.Kind.INSTALL_OTHER ->
                "https://unifiedpush.org/users/distributors/"
            else -> return null
        }
        return Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    override fun activeDistributorName(): String? {
        val active = UnifiedPush.getAckDistributor(appContext)
            ?: UnifiedPush.getSavedDistributor(appContext)
        return active?.let(::resolveLabel)
    }

    /**
     * Called by `UnifiedPushReceiver.onNewEndpoint`. Persists the endpoint and
     * reports it to the relay: registers the device on the first endpoint (the
     * deferred registration), or refreshes it over the tunnel WS once already
     * registered. Idempotent under repeated/rotated endpoints.
     */
    fun onEndpoint(endpoint: String) {
        if (endpoint.isBlank()) return
        prefs.edit().putString(KEY_ENDPOINT, endpoint).apply()
        appScope.launch {
            registerMutex.withLock {
                if (certificateStore.getSubdomain() == null) {
                    registerWithEndpoint(endpoint)
                } else {
                    refreshEndpoint(endpoint)
                }
            }
        }
    }

    /** Called by `UnifiedPushReceiver.onRegistrationFailed`. */
    fun onRegistrationFailed() {
        Log.w(TAG, "UnifiedPush registration failed; on-demand wake stays off")
        appScope.launch {
            if (certificateStore.getSubdomain() == null) {
                _activation.value = DeliveryActivation.NeedsSetup
            }
        }
    }

    /** Called by `UnifiedPushReceiver.onUnregistered`. */
    fun onUnregistered() {
        Log.i(TAG, "UnifiedPush distributor unregistered")
        prefs.edit().remove(KEY_ENDPOINT).apply()
        appScope.launch {
            if (certificateStore.getSubdomain() == null) {
                _activation.value = DeliveryActivation.NeedsSetup
            }
        }
    }

    private suspend fun registerWithEndpoint(endpoint: String) {
        val credential = try {
            credentialProvider.forRegistration()
        } catch (e: Exception) {
            Log.e(TAG, "Couldn't obtain device credential for deferred registration", e)
            null
        } ?: return
        val result = onboardingFlow.execute(
            credential,
            fcmToken = "",
            pushEndpoint = endpoint
        )
        if (result is OnboardingResult.Success) {
            registrationStatus.markComplete()
        }
        // Wake-activation tracks "is there a registered push wake target?", which
        // is exactly "did we persist a subdomain?" — NOT "did the whole flow incl.
        // certs succeed" (#486). `execute` registers + persists the subdomain
        // before chaining ACME cert provisioning (#389), so a cert-stage failure
        // (Cert*) returns non-Success while the device is already registered with
        // its endpoint reported. Keying off the persisted subdomain (mirroring the
        // init seeding and refreshEndpoint) keeps the delivery banner Active in
        // that case; cert problems are surfaced separately by the cert-renewal
        // banner. Only pre-subdomain failures leave wake needing setup.
        _activation.value = if (certificateStore.getSubdomain() != null) {
            DeliveryActivation.Active
        } else {
            DeliveryActivation.NeedsSetup
        }
        Log.i(TAG, "Deferred foss registration result=$result; activation=${_activation.value}")
    }

    private suspend fun refreshEndpoint(endpoint: String) {
        _activation.value = DeliveryActivation.Active
        val state = tunnelClient.state.value
        if (state != TunnelState.CONNECTED && state != TunnelState.ACTIVE) {
            // Tunnel down: the relay still holds the endpoint from registration;
            // a future reconnect re-reports it. Defer rather than fail.
            Log.d(TAG, "Tunnel state=$state; endpoint refresh deferred")
            return
        }
        try {
            tunnelClient.sendPushEndpoint(kind = PUSH_KIND, value = endpoint)
            Log.i(TAG, "Refreshed UnifiedPush endpoint over tunnel")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to refresh endpoint; relay retains the registered value", e)
        }
    }

    private fun resolveLabel(packageId: String): String = try {
        val pm = appContext.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(packageId, 0)).toString()
    } catch (_: PackageManager.NameNotFoundException) {
        packageId
    }

    companion object {
        private const val TAG = "UnifiedPushDelivery"
        private const val PUSH_KIND = "unifiedpush"
        private const val PREFS = "unifiedpush_delivery"
        private const val KEY_ENDPOINT = "endpoint"
    }
}
