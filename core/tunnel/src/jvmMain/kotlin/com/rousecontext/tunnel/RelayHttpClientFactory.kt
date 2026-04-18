package com.rousecontext.tunnel

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import kotlinx.serialization.json.Json
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient

/**
 * Builds a Ktor [HttpClient] for [RelayApiClient] that presents the device's
 * mTLS client certificate on handshakes via [LazyMtlsKeyManager].
 *
 * The OkHttp engine is used because its JSSE-backed TLS stack correctly
 * presents client certificates — mirroring the #226 fix on the WebSocket
 * path (`MtlsWebSocketFactory`). Ktor's CIO client has historically dropped
 * client certs silently, so REST mTLS lands on the same engine the WS path
 * already relies on.
 *
 * Connection pooling is disabled (`maxIdleConnections = 0`) so that every
 * relay REST call performs a fresh TLS handshake. This closes the issue
 * #237 window: if the first handshake happened before `/register/certs`
 * wrote a cert to disk, no pooled connection can linger with a "no client
 * cert" session identity. Onboarding issues a handful of REST calls, so
 * the per-call handshake cost is not a concern.
 *
 * @param certSource Lazy source for the client identity; returns `null`
 *   before provisioning completes.
 * @param trustManagers Server-auth trust managers. Defaults to the JVM's
 *   system trust store, which on Android contains the public CAs that
 *   chain up to real relay certs (GTS / Let's Encrypt). Tests override
 *   this to trust a fake CA.
 * @param configureOkHttp Optional hook for additional OkHttp customization
 *   (integration tests use this to wire a DNS override + loose hostname
 *   verifier against the loopback test relay). Production callers should
 *   not pass this.
 */
fun createMtlsRelayHttpClient(
    certSource: MtlsCertSource,
    trustManagers: Array<out TrustManager> = defaultSystemTrustManagers(),
    configureOkHttp: (OkHttpClient.Builder.() -> Unit)? = null
): HttpClient {
    val keyManagers = arrayOf(LazyMtlsKeyManager(certSource))
    val sslContext = SSLContext.getInstance("TLS").apply {
        init(keyManagers, trustManagers, null)
        // Disable TLS session caching so every request re-runs the full
        // handshake and re-consults the LazyMtlsKeyManager. Without this,
        // TLS 1.3 PSK / 1.2 session resumption can reuse a session
        // negotiated before provisioning wrote a cert, and the resumed
        // connection presents no client cert — the exact issue #237
        // failure mode we are fixing.
        clientSessionContext?.sessionCacheSize = 1
        clientSessionContext?.sessionTimeout = 1
    }
    val trustManager = trustManagers.firstOrNull { it is X509TrustManager } as? X509TrustManager
        ?: error("createMtlsRelayHttpClient requires at least one X509TrustManager")

    return HttpClient(OkHttp) {
        engine {
            config {
                sslSocketFactory(sslContext.socketFactory, trustManager)
                // Disable idle-connection reuse so each request's handshake
                // re-consults the LazyMtlsKeyManager; see the kdoc above.
                connectionPool(
                    ConnectionPool(
                        /* maxIdleConnections = */
                        0,
                        /* keepAliveDuration = */
                        1L,
                        /* timeUnit = */
                        TimeUnit.SECONDS
                    )
                )
                if (configureOkHttp != null) configureOkHttp()
            }
        }
        install(ContentNegotiation) {
            json(
                Json {
                    ignoreUnknownKeys = true
                    encodeDefaults = true
                }
            )
        }
        install(HttpTimeout) {
            requestTimeoutMillis = RELAY_REQUEST_TIMEOUT_MS
            connectTimeoutMillis = RELAY_CONNECT_TIMEOUT_MS
            socketTimeoutMillis = RELAY_SOCKET_TIMEOUT_MS
        }
    }
}

private const val RELAY_REQUEST_TIMEOUT_MS = 120_000L
private const val RELAY_CONNECT_TIMEOUT_MS = 30_000L
private const val RELAY_SOCKET_TIMEOUT_MS = 120_000L

private fun defaultSystemTrustManagers(): Array<TrustManager> =
    TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        .apply { init(null as java.security.KeyStore?) }
        .trustManagers
