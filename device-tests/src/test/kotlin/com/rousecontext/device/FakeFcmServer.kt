package com.rousecontext.device

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * A fake FCM HTTP v1 server that translates Firebase Cloud Messaging requests
 * into ADB broadcast commands delivered to a connected Android device.
 *
 * Listens on a random port and intercepts POST requests matching the FCM v1 API
 * pattern: /v1/projects/{project_id}/messages:send
 *
 * For each received message, it:
 * 1. Extracts the `data` fields from the JSON payload
 * 2. Executes an ADB broadcast to deliver the message to the device
 * 3. Returns a success response to the caller (relay)
 */
class FakeFcmServer(private val deviceController: DeviceController) {
    val port: Int = findFreePort()
    val receivedMessages = CopyOnWriteArrayList<Map<String, String>>()

    private var server: io.ktor.server.engine.EmbeddedServer<*, *>? = null

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Start the fake FCM server. Non-blocking.
     */
    fun start() {
        server = embeddedServer(CIO, port = port) {
            install(ContentNegotiation) {
                json(json)
            }
            routing {
                // FCM HTTP v1 API endpoint
                post("/v1/projects/{projectId}/messages:send") {
                    val body = call.receiveText()
                    val data = extractDataFields(body)
                    receivedMessages.add(data)

                    // Deliver via ADB broadcast
                    deliverViaAdb(data)

                    // Return FCM-like success response
                    call.respondText(
                        """{"name": "projects/test/messages/fake-${System.nanoTime()}"}""",
                        ContentType.Application.Json,
                        HttpStatusCode.OK
                    )
                }
            }
        }.start(wait = false)
    }

    /**
     * Stop the fake FCM server.
     */
    fun stop() {
        server?.stop(gracePeriodMillis = 500, timeoutMillis = 1000)
        server = null
    }

    /**
     * Extract the `data` map from an FCM v1 message JSON body.
     *
     * Expected structure:
     * ```json
     * {
     *   "message": {
     *     "token": "...",
     *     "data": { "type": "wake" },
     *     "android": { "priority": "high" }
     *   }
     * }
     * ```
     */
    private fun extractDataFields(body: String): Map<String, String> {
        return try {
            val root = json.parseToJsonElement(body).jsonObject
            val message = root["message"]?.jsonObject ?: return emptyMap()
            val data = message["data"]?.jsonObject ?: return emptyMap()
            data.mapValues { (_, v) -> v.jsonPrimitive.content }
        } catch (_: Exception) {
            emptyMap()
        }
    }

    /**
     * Deliver an FCM data message to the device via ADB broadcast.
     *
     * This simulates what Firebase would do: deliver a data-only message
     * to the app's FirebaseMessagingService via the Firebase IID receiver.
     */
    private fun deliverViaAdb(data: Map<String, String>) {
        deviceController.executeAdb(
            "shell",
            "am",
            "broadcast",
            "-n",
            "com.rousecontext.debug/com.rousecontext.app.debug.TestWakeReceiver",
            "-a",
            "com.rousecontext.action.TEST_WAKE",
            *data.flatMap { (k, v) -> listOf("--es", k, v) }.toTypedArray()
        )
    }

    private fun findFreePort(): Int {
        val socket = ServerSocket(0)
        val p = socket.localPort
        socket.close()
        return p
    }
}
