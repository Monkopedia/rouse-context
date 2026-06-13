package com.rousecontext.app.auth

import com.rousecontext.tunnel.DeviceCredential

/**
 * `google`-flavor [DeviceCredentialProvider] backed by Firebase Anonymous Auth.
 *
 * Registration triggers an anonymous sign-in (creating the anon user whose UID
 * becomes the device's Firestore key); provisioning reuses the current user's
 * ID token without forcing a fresh sign-in. Both wrap the existing
 * [AnonymousAuthClient] / [DeviceAuthTokenProvider] seams. See issue #462.
 */
class FirebaseDeviceCredentialProvider(
    private val anonymousAuth: AnonymousAuthClient,
    private val deviceAuth: DeviceAuthTokenProvider
) : DeviceCredentialProvider {

    override suspend fun forRegistration(): DeviceCredential? =
        anonymousAuth.signInAnonymouslyAndGetIdToken()?.let { DeviceCredential.Firebase(it) }

    override suspend fun forProvisioning(): DeviceCredential? =
        deviceAuth.currentIdToken()?.let { DeviceCredential.Firebase(it) }
}
