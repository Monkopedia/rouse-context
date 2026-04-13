package com.rousecontext.work

import android.util.Log

/**
 * Default [RenewalAuthProvider] that would bridge [com.google.firebase.auth.FirebaseAuth]
 * (for the ID token) and the Android Keystore signing operation (for the CSR signature).
 *
 * NOT YET IMPLEMENTED. Returning `null` causes [CertRenewalWorker] to retry on the next
 * periodic tick, matching the design note in `docs/design/tunnel-client.md` that says expired
 * certs require Firebase + signature. See issue #84 follow-up.
 */
class FirebaseRenewalAuthProvider : RenewalAuthProvider {
    override suspend fun acquireFirebaseCredentials(): FirebaseCredentials? {
        Log.w(
            TAG,
            "Firebase-signature renewal path not yet wired; cert will stay expired until " +
                "this provider is implemented. See issue #84 follow-up."
        )
        return null
    }

    private companion object {
        const val TAG = "FirebaseRenewalAuth"
    }
}
