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
    fun `rotate secret returns new prefix`(): Unit = runBlocking {
        mockServer.rotateSecretHandler = {
            MockRotateSecretResponse(
                status = 200,
                body = RotateSecretResponse(secretPrefix = "swift-tiger")
            )
        }

        val result = client.rotateSecret()

        assertTrue(result is RelayApiResult.Success)
        assertEquals("swift-tiger", (result as RelayApiResult.Success).data.secretPrefix)
    }

    @Test
    fun `rotate secret stores new prefix in certificate store`(): Unit = runBlocking {
        store.storeSecretPrefix("old-prefix")
        assertEquals("old-prefix", store.getSecretPrefix())

        mockServer.rotateSecretHandler = {
            MockRotateSecretResponse(
                status = 200,
                body = RotateSecretResponse(secretPrefix = "new-prefix")
            )
        }

        val result = client.rotateSecret()
        assertTrue(result is RelayApiResult.Success)
        val newPrefix = (result as RelayApiResult.Success).data.secretPrefix
        store.storeSecretPrefix(newPrefix)

        assertEquals("new-prefix", store.getSecretPrefix())
    }

    @Test
    fun `certificate store secret prefix persists and clears`(): Unit = runBlocking {
        assertNull(store.getSecretPrefix())

        store.storeSecretPrefix("brave-falcon")
        assertEquals("brave-falcon", store.getSecretPrefix())

        store.clear()
        assertNull(store.getSecretPrefix())
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
