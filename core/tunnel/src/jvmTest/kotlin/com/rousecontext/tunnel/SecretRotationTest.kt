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
    fun `rotate secret returns new integration secrets`(): Unit = runBlocking {
        mockServer.rotateSecretHandler = {
            MockRotateSecretResponse(
                status = 200,
                body = RotateSecretResponse(
                    integrationSecrets = mapOf("health" to "swift-health")
                )
            )
        }

        val result = client.rotateSecret()

        assertTrue(result is RelayApiResult.Success)
        assertEquals(
            mapOf("health" to "swift-health"),
            (result as RelayApiResult.Success).data.integrationSecrets
        )
    }

    @Test
    fun `rotate secret stores new secrets in certificate store`(): Unit = runBlocking {
        store.storeIntegrationSecrets(mapOf("health" to "old-health"))
        assertEquals("old-health", store.getSecretForIntegration("health"))

        mockServer.rotateSecretHandler = {
            MockRotateSecretResponse(
                status = 200,
                body = RotateSecretResponse(
                    integrationSecrets = mapOf("health" to "new-health")
                )
            )
        }

        val result = client.rotateSecret()
        assertTrue(result is RelayApiResult.Success)
        val newSecrets = (result as RelayApiResult.Success).data.integrationSecrets
        store.storeIntegrationSecrets(newSecrets)

        assertEquals("new-health", store.getSecretForIntegration("health"))
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
    fun `rotate secret handles server error`(): Unit = runBlocking {
        mockServer.rotateSecretHandler = {
            MockRotateSecretResponse(status = 500)
        }

        val result = client.rotateSecret()

        assertTrue(result is RelayApiResult.Error)
        assertEquals(500, (result as RelayApiResult.Error).statusCode)
    }
}
