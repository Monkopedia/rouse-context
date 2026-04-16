package com.rousecontext.app.auth

import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.tasks.await

/** Production implementation backed by [FirebaseMessaging]. */
class FirebaseFcmTokenProvider : FcmTokenProvider {
    override suspend fun currentToken(): String =
        FirebaseMessaging.getInstance().token.await()
}
