package com.rousecontext.app.auth

/**
 * Abstracts FCM token retrieval so callers can be tested without
 * reaching for mockk-static on [com.google.firebase.messaging.FirebaseMessaging].
 */
interface FcmTokenProvider {
    /** Returns the current FCM registration token. */
    suspend fun currentToken(): String
}
