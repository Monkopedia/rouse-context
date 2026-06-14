package com.rousecontext.app.di

import com.rousecontext.api.CrashReporter
import com.rousecontext.app.auth.AnonymousAuthClient
import com.rousecontext.app.auth.DeviceAuthTokenProvider
import com.rousecontext.app.auth.DeviceCredentialProvider
import com.rousecontext.app.auth.FcmTokenProvider
import com.rousecontext.app.auth.FirebaseAnonymousAuthClient
import com.rousecontext.app.auth.FirebaseDeviceAuthTokenProvider
import com.rousecontext.app.auth.FirebaseDeviceCredentialProvider
import com.rousecontext.app.auth.FirebaseFcmTokenProvider
import com.rousecontext.app.auth.FirebaseRenewalAuthProvider
import com.rousecontext.app.delivery.BackgroundDelivery
import com.rousecontext.app.delivery.NoOpBackgroundDelivery
import com.rousecontext.app.push.FcmConnectPushReporter
import com.rousecontext.app.support.FirebaseCrashReporter
import com.rousecontext.work.ConnectPushReporter
import com.rousecontext.work.RenewalAuthProvider
import org.koin.dsl.module

/**
 * Flavor-specific Koin bindings for the `google` distribution.
 *
 * Wires the Firebase-backed implementations of the three cross-flavor seams
 * (crash reporting, push-token retrieval, device auth) plus the cert-renewal
 * auth provider. The parallel `foss` flavor binds NoOp/stub equivalents so the
 * graph compiles without any Firebase dependency. See issue #461.
 */
val distributionModule = module {
    // Crash reporting (Firebase Crashlytics). Issue #233.
    single<CrashReporter> { FirebaseCrashReporter() }

    // Background delivery: google wakes via FCM, so no UnifiedPush picker /
    // banner / Settings row. Issue #463.
    single<BackgroundDelivery> { NoOpBackgroundDelivery }

    // Firebase auth / FCM abstractions.
    single<AnonymousAuthClient> { FirebaseAnonymousAuthClient() }
    single<FcmTokenProvider> { FirebaseFcmTokenProvider() }
    single<DeviceAuthTokenProvider> { FirebaseDeviceAuthTokenProvider() }

    // Per-connect push-target sync (issue #476): fetch the current FCM token and
    // forward it to the relay each time the tunnel connects. The foss flavor
    // binds a no-op since it reports a UnifiedPush endpoint instead.
    single<ConnectPushReporter> {
        FcmConnectPushReporter(tunnelClient = get(), tokenProvider = get())
    }

    // Device-auth seam (issue #462): wraps the Firebase anon/ID-token clients.
    single<DeviceCredentialProvider> {
        FirebaseDeviceCredentialProvider(anonymousAuth = get(), deviceAuth = get())
    }

    // Cert-renewal auth provider (Firebase ID token + Keystore signature).
    // Lives in the `google` source set (issue #476) so :work links no firebase-auth.
    single<RenewalAuthProvider> { FirebaseRenewalAuthProvider(signer = get()) }
}
