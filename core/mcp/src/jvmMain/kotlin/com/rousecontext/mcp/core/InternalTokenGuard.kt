package com.rousecontext.mcp.core

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.install
import io.ktor.server.request.ApplicationRequest
import io.ktor.server.response.respondText
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import kotlinx.serialization.json.Json

/**
 * Header carrying the shared secret that proves an inbound HTTP request was
 * routed through the in-process bridge rather than dialed by some other
 * process on the device's loopback interface. See issue #177.
 *
 * Public so the bridge module can reference it when injecting the header on
 * forwarded requests.
 */
const val INTERNAL_TOKEN_HEADER: String = "X-Internal-Token"

/**
 * Size of the random secret generated per [McpSession.start]. 32 bytes gives
 * 256 bits of entropy; base64url-encoded that is a 43-character ASCII string.
 */
private const val INTERNAL_TOKEN_BYTES = 32

private val secureRandom = SecureRandom()
private val base64UrlEncoder = Base64.getUrlEncoder().withoutPadding()

/**
 * Generate a cryptographically random internal token. Not persisted anywhere;
 * the caller MUST keep it in memory only and pass it to the bridge out-of-band.
 */
fun generateInternalToken(): String {
    val bytes = ByteArray(INTERNAL_TOKEN_BYTES)
    secureRandom.nextBytes(bytes)
    return base64UrlEncoder.encodeToString(bytes)
}

/**
 * Ktor plugin that rejects every request which does not carry a valid
 * [INTERNAL_TOKEN_HEADER] matching the configured expected token. The check
 * runs via Ktor's `onCall` hook so it fires before the `ContentNegotiation`
 * plugin, the rate limiter, and any route handler.
 *
 * Compares tokens in constant time via [MessageDigest.isEqual] to prevent
 * timing oracles on the shared secret.
 *
 * Used by [configureMcpRouting] when an `internalToken` is supplied.
 */
internal val InternalTokenGuard = createApplicationPlugin(
    name = "InternalTokenGuard",
    createConfiguration = ::InternalTokenGuardConfig
) {
    val expected = pluginConfig.expectedToken
        ?: error("InternalTokenGuard installed without an expected token")
    val expectedBytes = expected.toByteArray(Charsets.UTF_8)
    val rejectionJson = Json.encodeToString(
        OAuthError.serializer(),
        OAuthError(error = "invalid_internal_token")
    )

    onCall { call ->
        if (!hasValidInternalToken(call.request, expectedBytes)) {
            // Committing the response here causes any downstream route handler
            // that later attempts respond() to throw ResponseAlreadySent, which
            // Ktor swallows. The client sees our 403.
            call.respondText(
                text = rejectionJson,
                contentType = ContentType.Application.Json,
                status = HttpStatusCode.Forbidden
            )
        }
    }
}

/** Configuration object for [InternalTokenGuard]. */
internal class InternalTokenGuardConfig {
    var expectedToken: String? = null
}

/**
 * Constant-time compare of the request's [INTERNAL_TOKEN_HEADER] value against
 * [expectedBytes]. Returns false for missing/short/mismatched values without
 * leaking length via early return.
 */
private fun hasValidInternalToken(request: ApplicationRequest, expectedBytes: ByteArray): Boolean {
    val actual = request.headers[INTERNAL_TOKEN_HEADER] ?: return false
    val actualBytes = actual.toByteArray(Charsets.UTF_8)
    return MessageDigest.isEqual(expectedBytes, actualBytes)
}

/**
 * Install the [InternalTokenGuard] with the given [expectedToken]. Callers
 * should only invoke this when the token is non-null; the no-op case is the
 * caller's responsibility.
 */
internal fun Application.installInternalTokenGuard(expectedToken: String) {
    install(InternalTokenGuard) {
        this.expectedToken = expectedToken
    }
}
