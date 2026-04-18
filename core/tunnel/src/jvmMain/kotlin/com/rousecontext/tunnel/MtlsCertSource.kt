package com.rousecontext.tunnel

import java.security.PrivateKey
import java.security.cert.X509Certificate

/**
 * Snapshot of an mTLS client identity: private key + cert chain (leaf first).
 */
data class MtlsIdentity(val privateKey: PrivateKey, val certChain: List<X509Certificate>)

/**
 * Source of the device's current mTLS client identity, read on demand.
 *
 * Implementations MUST be cheap and safe to call on the TLS handshake thread:
 * the JSSE engine invokes this synchronously while choosing a client
 * certificate. File reads / Keystore lookups of the sort used on device today
 * are fine (tens of microseconds); long-running I/O is not.
 *
 * Returns `null` when no cert is provisioned yet. During onboarding the
 * device makes unauthenticated calls (`/register`, `/register/certs`,
 * `/request-subdomain`) before a cert has been minted. The relay's TLS
 * layer runs in `allow_unauthenticated` mode, so presenting no client cert
 * still completes the handshake; the handler rejects privileged calls
 * (`/rotate-secret`, `/renew`) that arrive without one. This chicken-and-egg
 * shape is the reason the same long-lived HTTP client must transparently
 * switch from "no cert" to "present cert" as soon as provisioning writes
 * a cert to disk (issue #237).
 */
fun interface MtlsCertSource {
    fun current(): MtlsIdentity?
}
