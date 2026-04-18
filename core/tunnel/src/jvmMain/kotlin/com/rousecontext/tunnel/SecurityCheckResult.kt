package com.rousecontext.tunnel

/**
 * Result of a security monitoring check.
 *
 * [Verified] means everything looks normal.
 * [Skipped] means the check is not applicable yet (e.g. pre-onboarding: no
 *   cert stored, no subdomain provisioned). This is NOT an error and MUST NOT
 *   surface a user-facing notification; it only exists so the settings / audit
 *   UI can render "waiting for onboarding" instead of claiming verified.
 * [Warning] means a transient issue occurred (e.g. network unreachable) --
 *   the check could not be completed but there is no evidence of compromise.
 * [Alert] means a potential security issue was detected that requires attention.
 */
sealed class SecurityCheckResult {
    data object Verified : SecurityCheckResult()
    data class Skipped(val reason: String) : SecurityCheckResult()
    data class Warning(val reason: String) : SecurityCheckResult()
    data class Alert(val reason: String) : SecurityCheckResult()
}
