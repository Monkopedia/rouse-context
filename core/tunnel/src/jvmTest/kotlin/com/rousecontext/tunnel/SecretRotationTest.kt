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
    fun `update secrets sends valid_secrets to relay`(): Unit = runBlocking {
        var capturedRequest: UpdateSecretsRequest? = null
        mockServer.updateSecretsHandler = { request ->
            capturedRequest = request
            MockUpdateSecretsResponse(
                status = 200,
                body = UpdateSecretsResponse(status = "ok")
            )
        }

        val result = client.updateSecrets(listOf("brave-health", "swift-notifications"))

        assertTrue(result is RelayApiResult.Success)
        assertEquals(
            listOf("brave-health", "swift-notifications"),
            capturedRequest?.validSecrets
        )
    }

    @Test
    fun `update secrets stores new secrets locally on success`(): Unit = runBlocking {
        store.storeIntegrationSecrets(mapOf("health" to "old-health"))
        assertEquals("old-health", store.getSecretForIntegration("health"))

        mockServer.updateSecretsHandler = { _ ->
            MockUpdateSecretsResponse(
                status = 200,
                body = UpdateSecretsResponse(status = "ok")
            )
        }

        val newSecrets = SecretGenerator.generateAll(listOf("health"))
        val result = client.updateSecrets(newSecrets.values.toList())
        assertTrue(result is RelayApiResult.Success)
        store.storeIntegrationSecrets(newSecrets)

        val stored = store.getSecretForIntegration("health")
        assertTrue(stored!!.endsWith("-health"))
        assertTrue(stored != "old-health")
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

        val result = client.updateSecrets(listOf("brave-health"))

        assertTrue(result is RelayApiResult.Error)
        assertEquals(500, (result as RelayApiResult.Error).statusCode)
    }
}
