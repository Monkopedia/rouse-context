package com.rousecontext.app.auth

/**
 * Abstracts Firebase anonymous sign-in so callers can be tested without
 * reaching for mockk-static on [com.google.firebase.auth.FirebaseAuth].
 */
interface AnonymousAuthClient {
    /**
     * Signs in anonymously and returns the resulting ID token,
     * or `null` if authentication fails.
     */
    suspend fun signInAnonymouslyAndGetIdToken(): String?
}
