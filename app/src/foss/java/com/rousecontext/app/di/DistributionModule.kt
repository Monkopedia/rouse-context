package com.rousecontext.app.di

import com.rousecontext.api.CrashReporter
import com.rousecontext.app.auth.DeviceCredentialProvider
import com.rousecontext.app.auth.FcmTokenProvider
import com.rousecontext.app.auth.KeypairDeviceCredentialProvider
import com.rousecontext.app.auth.KeypairRenewalAuthProvider
import com.rousecontext.app.auth.NoOpFcmTokenProvider
import com.rousecontext.work.RenewalAuthProvider
import org.koin.dsl.module

/**
 * Flavor-specific Koin bindings for the `foss` distribution.
 *
 * Auth + cert-renewal are backed by the device's hardware keypair (issue #462):
 * registration and provisioning go through [KeypairDeviceCredentialProvider]
 * (signed registration proof, no Firebase), and expired-cert renewal goes
 * through [KeypairRenewalAuthProvider] (keypair-signed timestamp proof). Crash
 * reporting (#464) and push (#463) remain stubbed until their tickets land.
 */
val distributionModule = module {
    // Crash reporting — no-op until a FOSS crash backend lands (#464).
    single { CrashReporter.NoOp }

    // Push-token retrieval — stubbed until the UnifiedPush wake path lands (#463).
    single<FcmTokenProvider> { NoOpFcmTokenProvider() }

    // Device-auth seam — keypair credentials, zero Firebase. Issue #462.
    single<DeviceCredentialProvider> {
        KeypairDeviceCredentialProvider(deviceKeyManager = get(), signer = get())
    }

    // Cert-renewal auth — keypair proof + Keystore CSR signature. Issue #462.
    single<RenewalAuthProvider> { KeypairRenewalAuthProvider(signer = get()) }
}
