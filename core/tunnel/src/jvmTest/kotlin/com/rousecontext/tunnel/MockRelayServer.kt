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
 * Embedded Ktor/Netty server that simulates the relay's two-round-trip
 * registration and renewal endpoints.
 */
class MockRelayServer {

    var requestSubdomainHandler:
        (suspend (RequestSubdomainRequest) -> MockRequestSubdomainResponse) = { _ ->
            MockRequestSubdomainResponse(
                status = 200,
                body = RequestSubdomainResponse(
                    subdomain = "test123",
                    baseDomain = "rousecontext.com",
                    fqdn = "test123.rousecontext.com",
                    reservationTtlSeconds = 600
                )
            )
        }

    var registerHandler: (suspend (RegisterRequest) -> MockRegisterResponse) = { _ ->
        MockRegisterResponse(
            status = 201,
            body = RegisterResponse(
                subdomain = "test123",
                relayHost = "relay.rousecontext.com"
            )
        )
    }

    var certHandler: (suspend (CertRequest) -> MockCertResponse) = { _ ->
        MockCertResponse(
            status = 201,
            body = CertResponse(
                subdomain = "test123",
                serverCert = MOCK_CERT_PEM,
                clientCert = MOCK_CLIENT_CERT_PEM,
                relayCaCert = MOCK_RELAY_CA_PEM,
                relayHost = "relay.rousecontext.com"
            )
        )
    }

    var renewHandler: (suspend (RenewRequest) -> MockRenewResponse) = { _ ->
        MockRenewResponse(
            status = 200,
            body = RenewResponse(
                serverCert = MOCK_CERT_PEM,
                clientCert = MOCK_CLIENT_CERT_PEM,
                relayCaCert = MOCK_RELAY_CA_PEM
            )
        )
    }

    var updateSecretsHandler: (suspend (UpdateSecretsRequest) -> MockUpdateSecretsResponse) = { _ ->
        MockUpdateSecretsResponse(
            status = 200,
            body = UpdateSecretsResponse(success = true, secrets = emptyMap())
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
                post("/request-subdomain") {
                    val request = call.receive<RequestSubdomainRequest>()
                    val response = requestSubdomainHandler(request)
                    respondMock(response.status, response.retryAfter, response.body)
                }
                post("/register") {
                    val request = call.receive<RegisterRequest>()
                    val response = registerHandler(request)
                    respondMock(response.status, response.retryAfter, response.body)
                }
                post("/register/certs") {
                    val request = call.receive<CertRequest>()
                    val response = certHandler(request)
                    respondMock(response.status, response.retryAfter, response.body)
                }
                post("/renew") {
                    val request = call.receive<RenewRequest>()
                    val response = renewHandler(request)
                    respondMock(response.status, response.retryAfter, response.body)
                }
                post("/rotate-secret") {
                    val request = call.receive<UpdateSecretsRequest>()
                    val response = updateSecretsHandler(request)
                    respondMock(response.status, retryAfter = null, response.body)
                }
            }
        }.start(wait = false)
    }

    private suspend fun io.ktor.server.routing.RoutingContext.respondMock(
        status: Int,
        retryAfter: Long?,
        body: Any?
    ) {
        if (retryAfter != null) {
            call.response.header("Retry-After", retryAfter.toString())
        }
        if (body != null) {
            call.respond(HttpStatusCode.fromValue(status), body)
        } else {
            call.respond(HttpStatusCode.fromValue(status), "")
        }
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

        const val MOCK_CLIENT_CERT_PEM = """-----BEGIN CERTIFICATE-----
MIIBkTCB+wIJANkE+TTJt1RdMA0GCSqGSIb3DQEBCwUAMBMxETAPBgNVBAMMCGNs
aWVudC1jZXJ0MB4XDTIwMDEwMTAwMDAwMFoXDTIxMDEwMTAwMDAwMFowEzERMA8G
A1UEAwwIY2xpZW50LWNlcnQwXDANBgkqhkiG9w0BAQEFAANLADBIAkEA2QT5NMm3
VF0wDQYJKoZIhvcNAQELBQAwETEPMA0GA1UEAwwGdGVzdDEyMzAeFw0yNTAxMDEw
MDAwMDBaFw0yNjAxMDEwMDAwMDBaMBExDzANBgNVBAMMBnRlc3QxMjMIDAZjbGll
bnQtY2VydAIDAQABMA0GCSqGSIb3DQEBCwUAA0EAhN+z/EvKbL4TiT3FHSGA
-----END CERTIFICATE-----"""

        const val MOCK_RELAY_CA_PEM = """-----BEGIN CERTIFICATE-----
MIIBkTCB+wIJANkE+TTJt1RdMA0GCSqGSIb3DQEBCwUAMBIxEDAOBgNVBAMMB3Jl
bGF5LWNhMB4XDTIwMDEwMTAwMDAwMFoXDTIxMDEwMTAwMDAwMFowEjEQMA4GA1UE
AwwHcmVsYXktY2EwXDANBgkqhkiG9w0BAQEFAANLADBIAkEA2QT5NMm3VF0wDQYJ
KoZIhvcNAQELBQAwETEPMA0GA1UEAwwGdGVzdDEyMzAeFw0yNTAxMDEwMDAwMDBa
Fw0yNjAxMDEwMDAwMDBaMBExDzANBgNVBAMMBnRlc3QxMjMIDAZyZWxheS1jYQID
AQABMA0GCSqGSIb3DQEBCwUAA0EAhN+z/EvKbL4TiT3FHSGA
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

data class MockRegisterResponse(
    val status: Int = 201,
    val body: RegisterResponse? = null,
    val retryAfter: Long? = null
)

data class MockCertResponse(
    val status: Int = 201,
    val body: CertResponse? = null,
    val retryAfter: Long? = null
)

data class MockRenewResponse(
    val status: Int = 200,
    val body: RenewResponse? = null,
    val retryAfter: Long? = null
)

data class MockUpdateSecretsResponse(val status: Int = 200, val body: UpdateSecretsResponse? = null)

data class MockRequestSubdomainResponse(
    val status: Int = 200,
    val body: RequestSubdomainResponse? = null,
    val retryAfter: Long? = null
)
