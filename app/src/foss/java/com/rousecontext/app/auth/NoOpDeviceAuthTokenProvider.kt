package com.rousecontext.app.auth

/**
 * Placeholder [DeviceAuthTokenProvider] for the `foss` flavor, which has no
 * Firebase auth. Returns `null` (no token). A real FOSS device-auth path lands
 * in a later ticket (#462–#464); #461 only needs the Koin graph to compile
 * Firebase-free.
 */
class NoOpDeviceAuthTokenProvider : DeviceAuthTokenProvider {
    override suspend fun currentIdToken(): String? = null
}
