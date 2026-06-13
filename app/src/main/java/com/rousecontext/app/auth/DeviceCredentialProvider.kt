package com.rousecontext.app.auth

import com.rousecontext.tunnel.DeviceCredential

/**
 * Produces the [DeviceCredential] used to authenticate device registration and
 * cert provisioning/renewal with the relay. This is the single per-flavor auth
 * seam (issue #462):
 *
 * - `google` binds a Firebase-backed provider returning
 *   [DeviceCredential.Firebase] (anonymous sign-in for registration, the current
 *   ID token for provisioning).
 * - `foss` binds a keypair-backed provider returning [DeviceCredential.Keypair]
 *   (a freshly-signed registration proof from the hardware-backed device key).
 *
 * Returning `null` signals a transient auth failure; callers treat it as a
 * recoverable condition (fail onboarding with a retry, or defer provisioning).
 */
interface DeviceCredentialProvider {
    /**
     * Credential for first-run registration. On `google` this triggers an
     * anonymous sign-in; on `foss` it builds a registration proof.
     */
    suspend fun forRegistration(): DeviceCredential?

    /**
     * Credential for cert provisioning/renewal of an already-registered device.
     * On `google` this reads the current Firebase ID token; on `foss` it builds
     * a fresh keypair credential from the existing device key.
     */
    suspend fun forProvisioning(): DeviceCredential?
}
