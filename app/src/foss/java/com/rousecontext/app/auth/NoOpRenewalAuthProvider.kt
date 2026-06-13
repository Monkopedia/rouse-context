package com.rousecontext.app.auth

import com.rousecontext.work.FirebaseCredentials
import com.rousecontext.work.RenewalAuthProvider

/**
 * Placeholder [RenewalAuthProvider] for the `foss` flavor. Returns `null` from
 * both paths, which the cert-renewal worker treats as a transient condition and
 * defers to the next scheduled run. A real FOSS renewal-auth path (Keystore
 * signature + non-Firebase token) lands in a later ticket (#462–#464); #461 only
 * needs the Koin graph to compile Firebase-free.
 */
class NoOpRenewalAuthProvider : RenewalAuthProvider {
    override suspend fun signCsr(csrDer: ByteArray): String? = null

    override suspend fun acquireFirebaseCredentials(csrDer: ByteArray): FirebaseCredentials? = null
}
