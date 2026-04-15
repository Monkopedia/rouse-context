package com.rousecontext.tunnel

/**
 * Result of a security monitoring check.
 *
 * [Verified] means everything looks normal.
 * [Warning] means a transient issue occurred (e.g. network unreachable) --
 *   the check could not be completed but there is no evidence of compromise.
 * [Alert] means a potential security issue was detected that requires attention.
 */
sealed class SecurityCheckResult {
    data object Verified : SecurityCheckResult()
    data class Warning(val reason: String) : SecurityCheckResult()
    data class Alert(val reason: String) : SecurityCheckResult()
}
