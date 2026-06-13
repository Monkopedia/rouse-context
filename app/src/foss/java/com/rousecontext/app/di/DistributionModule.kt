package com.rousecontext.app.di

import com.rousecontext.api.CrashReporter
import com.rousecontext.app.auth.AnonymousAuthClient
import com.rousecontext.app.auth.DeviceAuthTokenProvider
import com.rousecontext.app.auth.FcmTokenProvider
import com.rousecontext.app.auth.NoOpAnonymousAuthClient
import com.rousecontext.app.auth.NoOpDeviceAuthTokenProvider
import com.rousecontext.app.auth.NoOpFcmTokenProvider
import com.rousecontext.app.auth.NoOpRenewalAuthProvider
import com.rousecontext.work.RenewalAuthProvider
import org.koin.dsl.module

/**
 * Flavor-specific Koin bindings for the `foss` distribution.
 *
 * Binds NoOp/stub implementations of the cross-flavor seams (crash reporting,
 * push-token retrieval, device auth, cert-renewal auth) so the graph compiles
 * with zero Firebase / google-services / Play-Services dependencies. These are
 * temporary placeholders — real FOSS implementations land in #462–#464. See
 * issue #461.
 */
val distributionModule = module {
    // Crash reporting — no-op until a FOSS crash backend lands (#462–#464).
    single { CrashReporter.NoOp }

    // Auth / push seams — stubs with no Firebase backing.
    single<AnonymousAuthClient> { NoOpAnonymousAuthClient() }
    single<FcmTokenProvider> { NoOpFcmTokenProvider() }
    single<DeviceAuthTokenProvider> { NoOpDeviceAuthTokenProvider() }

    // Cert-renewal auth provider — defers renewal until a FOSS path lands.
    single<RenewalAuthProvider> { NoOpRenewalAuthProvider() }
}
