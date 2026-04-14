package com.rousecontext.tunnel

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class SecretRotationTest {

    private val mockServer = MockRelayServer()
    private val store = InMemoryCertificateStore()
    private lateinit var client: RelayApiClient

    @BeforeTest
    fun setUp() {
        mockServer.start()
        client = RelayApiClient(baseUrl = mockServer.baseUrl)
    }

    @AfterTest
    fun tearDown() {
        mockServer.stop()
    }

    @Test
    fun `update secrets sends integration ids to relay`(): Unit = runBlocking {
        var capturedRequest: UpdateSecretsRequest? = null
        mockServer.updateSecretsHandler = { request ->
            capturedRequest = request
            MockUpdateSecretsResponse(
                status = 200,
                body = UpdateSecretsResponse(
                    success = true,
                    secrets = mapOf(
                        "health" to "brave-health",
                        "notifications" to "swift-notifications"
                    )
                )
            )
        }

        val result = client.updateSecrets("test-sub", listOf("health", "notifications"))

        assertTrue(result is RelayApiResult.Success)
        assertEquals(
            listOf("health", "notifications"),
            capturedRequest?.integrations
        )
    }

    @Test
    fun `update secrets stores relay-returned secrets locally on success`(): Unit = runBlocking {
        store.storeIntegrationSecrets(mapOf("health" to "old-health"))
        assertEquals("old-health", store.getSecretForIntegration("health"))

        mockServer.updateSecretsHandler = { _ ->
            MockUpdateSecretsResponse(
                status = 200,
                body = UpdateSecretsResponse(
                    success = true,
                    secrets = mapOf("health" to "fresh-health")
                )
            )
        }

        val result = client.updateSecrets("test-sub", listOf("health"))
        assertTrue(result is RelayApiResult.Success)
        val newSecrets = (result as RelayApiResult.Success).data.secrets
        store.storeIntegrationSecrets(newSecrets)

        val stored = store.getSecretForIntegration("health")
        assertEquals("fresh-health", stored)
    }

    @Test
    fun `certificate store integration secrets persist and clear`(): Unit = runBlocking {
        assertNull(store.getIntegrationSecrets())

        store.storeIntegrationSecrets(mapOf("health" to "brave-health"))
        assertEquals("brave-health", store.getSecretForIntegration("health"))

        store.clear()
        assertNull(store.getIntegrationSecrets())
    }

    @Test
    fun `update secrets handles server error`(): Unit = runBlocking {
        mockServer.updateSecretsHandler = { _ ->
            MockUpdateSecretsResponse(status = 500)
        }

        val result = client.updateSecrets("test-sub", listOf("health"))

        assertTrue(result is RelayApiResult.Error)
        assertEquals(500, (result as RelayApiResult.Error).statusCode)
    }
}
