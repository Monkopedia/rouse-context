package com.rousecontext.app.auth

import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.tasks.await

/**
 * Production [DeviceAuthTokenProvider] backed by [FirebaseAuth]. Returns the
 * current user's ID token without forcing an interactive sign-in; `null` when
 * no user is signed in.
 */
class FirebaseDeviceAuthTokenProvider : DeviceAuthTokenProvider {
    override suspend fun currentIdToken(): String? =
        FirebaseAuth.getInstance().currentUser?.getIdToken(false)?.await()?.token
}
