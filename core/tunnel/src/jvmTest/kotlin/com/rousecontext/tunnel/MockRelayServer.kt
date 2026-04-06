package com.rousecontext.tunnel

import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import java.net.ServerSocket
import kotlinx.serialization.json.Json

/**
 * Embedded Ktor/Netty server that simulates the relay's /register and /renew endpoints.
 * Configure response behavior per-test via the handler lambdas.
 */
class MockRelayServer {

    var registerHandler: (suspend (RegisterRequest) -> MockResponse) = { _ ->
        MockResponse(
            status = 201,
            body = RegisterResponse(
                subdomain = "test123.rousecontext.com",
                cert = MOCK_CERT_PEM,
                privateKey = MOCK_PRIVATE_KEY_PEM,
                relayHost = "relay.rousecontext.com"
            )
        )
    }

    var renewHandler: (suspend (RenewRequest) -> MockRenewResponse) = { _ ->
        MockRenewResponse(
            status = 200,
            body = RenewResponse(certificatePem = MOCK_CERT_PEM)
        )
    }

    private var server:
        EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>? = null
    var port: Int = 0
        private set

    fun start() {
        port = findFreePort()
        server = embeddedServer(Netty, port = port) {
            install(ContentNegotiation) {
                json(
                    Json {
                        ignoreUnknownKeys = true
                        encodeDefaults = true
                    }
                )
            }
            routing {
                post("/register") {
                    val request = call.receive<RegisterRequest>()
                    val response = registerHandler(request)
                    if (response.retryAfter != null) {
                        call.response.header("Retry-After", response.retryAfter.toString())
                    }
                    when (val body = response.body) {
                        is RegisterResponse -> call.respond(
                            HttpStatusCode.fromValue(response.status),
                            body
                        )
                        else -> call.respond(HttpStatusCode.fromValue(response.status), "")
                    }
                }
                post("/renew") {
                    val request = call.receive<RenewRequest>()
                    val response = renewHandler(request)
                    if (response.retryAfter != null) {
                        call.response.header("Retry-After", response.retryAfter.toString())
                    }
                    when (val body = response.body) {
                        is RenewResponse -> call.respond(
                            HttpStatusCode.fromValue(response.status),
                            body
                        )
                        else -> call.respond(HttpStatusCode.fromValue(response.status), "")
                    }
                }
            }
        }.start(wait = false)
    }

    fun stop() {
        server?.stop(gracePeriodMillis = 100, timeoutMillis = 500)
        server = null
    }

    val baseUrl: String get() = "http://127.0.0.1:$port"

    private fun findFreePort(): Int {
        ServerSocket(0).use { return it.localPort }
    }

    companion object {
        const val MOCK_CERT_PEM = """-----BEGIN CERTIFICATE-----
MIIBkTCB+wIJANkE+TTJt1RdMA0GCSqGSIb3DQEBCwUAMBExDzANBgNVBAMMBnRl
c3QxMjMwHhcNMjUwMTAxMDAwMDAwWhcNMjYwMTAxMDAwMDAwWjARMQ8wDQYDVQQD
DAZ0ZXN0MTIzMFwwDQYJKoZIhvcNAQEBBQADSwAwSAJBANkE+TTJt1RdMA0GCSqG
SIb3DQEBCwUAMBExDzANBgNVBAMMBnRlc3QxMjMwHhcNMjUwMTAxMDAwMDAwWhcN
MjYwMTAxMDAwMDAwWjARMQ8wDQYDVQQDDAZ0ZXN0MTIzCAwGdGVzdDEyMwIDAQAB
MA0GCSqGSIb3DQEBCwUAA0EAhN+z/EvKbL4TiT3FHSGA
-----END CERTIFICATE-----"""

        const val MOCK_PRIVATE_KEY_PEM = """-----BEGIN PRIVATE KEY-----
MIIEvgIBADANBgkqhkiG9w0BAQEFAASCBKgwggSkAgEAAoIBAQDZBPk0ybdUXTAN
-----END PRIVATE KEY-----"""

        const val MOCK_MISMATCHED_CERT_PEM = """-----BEGIN CERTIFICATE-----
MIIBkTCB+wIJANkE+TTJt1RdMA0GCSqGSIb3DQEBCwUAMBMxETAPBgNVBAMMCG90
aGVyLWNuMB4XDTIwMDEwMTAwMDAwMFoXDTIxMDEwMTAwMDAwMFowEzERMA8GA1UE
AwwIb3RoZXItY24wXDANBgkqhkiG9w0BAQEFAANLADBIAkEA2QT5NMm3VF0wDQYJ
KoZIhvcNAQELBQAwETEPMA0GA1UEAwwGdGVzdDEyMzAeFw0yNTAxMDEwMDAwMDBa
Fw0yNjAxMDEwMDAwMDBaMBExDzANBgNVBAMMBnRlc3QxMjMIDAZvdGhlci1jbgID
AQABMA0GCSqGSIb3DQEBCwUAA0EAhN+z/EvKbL4TiT3FHSGA
-----END CERTIFICATE-----"""
    }
}

data class MockResponse(
    val status: Int = 201,
    val body: RegisterResponse? = null,
    val retryAfter: Long? = null
)

data class MockRenewResponse(
    val status: Int = 200,
    val body: RenewResponse? = null,
    val retryAfter: Long? = null
)
