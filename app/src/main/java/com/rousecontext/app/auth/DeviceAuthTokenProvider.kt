package com.rousecontext.app.auth

/**
 * Abstracts retrieval of the current device-auth ID token (without triggering a
 * fresh sign-in). Lets cert-provisioning callers obtain a token without
 * referencing Firebase directly, so the `foss` flavor can bind a stub that
 * returns `null`. The `google` flavor's implementation reads the current
 * `FirebaseAuth` user.
 */
interface DeviceAuthTokenProvider {
    /** Returns the current device-auth ID token, or `null` if unavailable. */
    suspend fun currentIdToken(): String?
}
