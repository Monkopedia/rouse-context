package com.rousecontext.app.auth

/**
 * Placeholder [AnonymousAuthClient] for the `foss` flavor, which has no Firebase
 * auth. Returns `null` (no token). A real FOSS auth path lands in a later ticket
 * (#462–#464); #461 only needs the Koin graph to compile Firebase-free.
 */
class NoOpAnonymousAuthClient : AnonymousAuthClient {
    override suspend fun signInAnonymouslyAndGetIdToken(): String? = null
}
