package com.rousecontext.app.di

import com.rousecontext.api.CrashReporter
import com.rousecontext.app.auth.DeviceCredentialProvider
import com.rousecontext.app.auth.FcmTokenProvider
import com.rousecontext.app.auth.KeypairDeviceCredentialProvider
import com.rousecontext.app.auth.KeypairRenewalAuthProvider
import com.rousecontext.app.auth.NoOpFcmTokenProvider
import com.rousecontext.app.delivery.BackgroundDelivery
import com.rousecontext.app.delivery.UnifiedPushBackgroundDelivery
import com.rousecontext.app.push.UnifiedPushConnectPushReporter
import com.rousecontext.app.state.DeviceRegistrationStatus
import com.rousecontext.app.support.AcraCrashReporter
import com.rousecontext.tunnel.CertificateStore
import com.rousecontext.tunnel.OnboardingFlow
import com.rousecontext.tunnel.TunnelClient
import com.rousecontext.work.ConnectPushReporter
import com.rousecontext.work.RenewalAuthProvider
import kotlinx.coroutines.CoroutineScope
import org.koin.android.ext.koin.androidContext
import org.koin.core.qualifier.named
import org.koin.dsl.bind
import org.koin.dsl.module

/**
 * Flavor-specific Koin bindings for the `foss` distribution.
 *
 * Auth + cert-renewal are backed by the device's hardware keypair (issue #462):
 * registration and provisioning go through [KeypairDeviceCredentialProvider]
 * (signed registration proof, no Firebase), and expired-cert renewal goes
 * through [KeypairRenewalAuthProvider] (keypair-signed timestamp proof). Crash
 * reporting is ACRA-backed (issue #464); push (#463) remains stubbed until its
 * ticket lands.
 */
val distributionModule = module {
    // Crash reporting — ACRA, the FOSS Crashlytics replacement (#464). ACRA is
    // initialized in CrashReporterInitializer (attachBaseContext); this binding
    // forwards caught-exception logging / collection toggling to it.
    single<CrashReporter> { AcraCrashReporter() }

    // Push-token retrieval — foss has no FCM token; its push target is the
    // UnifiedPush endpoint (reported via BackgroundDelivery), so the FCM-token
    // seam stays a no-op (empty token). Issue #463.
    single<FcmTokenProvider> { NoOpFcmTokenProvider() }

    // Per-connect push-target sync (issues #476, #485): re-report the persisted
    // UnifiedPush endpoint on every tunnel connect. UnifiedPushBackgroundDelivery
    // only pushes endpoint rotations while the tunnel is already up; if the
    // endpoint rotates while the tunnel is DOWN it persists locally and defers to
    // "a future reconnect re-reports it" — this reporter is that reconnect path,
    // mirroring the google flavor's FcmConnectPushReporter. No firebase linkage.
    single<ConnectPushReporter> {
        UnifiedPushConnectPushReporter(
            tunnelClient = get<TunnelClient>(),
            delivery = get<UnifiedPushBackgroundDelivery>()
        )
    }

    // Background delivery (UnifiedPush wake path). Bound as the concrete type so
    // UnifiedPushReceiver can invoke its endpoint callbacks, and exposed via the
    // flavor-agnostic BackgroundDelivery seam for the shared UI. Issue #463.
    single {
        UnifiedPushBackgroundDelivery(
            appContext = androidContext(),
            onboardingFlow = get<OnboardingFlow>(),
            credentialProvider = get<DeviceCredentialProvider>(),
            certificateStore = get<CertificateStore>(),
            registrationStatus = get<DeviceRegistrationStatus>(),
            tunnelClient = get<TunnelClient>(),
            appScope = get<CoroutineScope>(named("appScope"))
        )
    } bind BackgroundDelivery::class

    // Device-auth seam — keypair credentials, zero Firebase. Issue #462.
    single<DeviceCredentialProvider> {
        KeypairDeviceCredentialProvider(deviceKeyManager = get(), signer = get())
    }

    // Cert-renewal auth — keypair proof + Keystore CSR signature. Issue #462.
    single<RenewalAuthProvider> { KeypairRenewalAuthProvider(signer = get()) }
}
