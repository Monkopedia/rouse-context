package com.rousecontext.app.auth

/**
 * Placeholder [FcmTokenProvider] for the `foss` flavor, which has no Firebase
 * Cloud Messaging. Returns an empty token. A real FOSS push/wake path lands in a
 * later ticket (#462–#464); #461 only needs the Koin graph to compile
 * Firebase-free.
 */
class NoOpFcmTokenProvider : FcmTokenProvider {
    override suspend fun currentToken(): String = ""
}
