package com.rousecontext.app.auth

import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.tasks.await

/** Production implementation backed by [FirebaseAuth]. */
class FirebaseAnonymousAuthClient : AnonymousAuthClient {
    override suspend fun signInAnonymouslyAndGetIdToken(): String? {
        val user = FirebaseAuth.getInstance().signInAnonymously().await().user ?: return null
        return user.getIdToken(false).await().token
    }
}
