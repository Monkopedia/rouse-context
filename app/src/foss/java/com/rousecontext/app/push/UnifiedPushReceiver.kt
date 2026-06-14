package com.rousecontext.app.push

import android.content.Context
import android.util.Log
import com.rousecontext.app.delivery.UnifiedPushBackgroundDelivery
import com.rousecontext.mcp.core.ProviderRegistry
import com.rousecontext.work.WakeDispatcher
import org.json.JSONObject
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.unifiedpush.android.connector.MessagingReceiver

/**
 * UnifiedPush message receiver for the `foss` flavor (issue #463) — the
 * Firebase-free counterpart of the `google` flavor's
 * `com.rousecontext.app.push.FcmReceiver`.
 *
 * - [onMessage] routes the relay's `{"type":"wake"}` / `{"type":"renew"}`
 *   payload (delivered as the POST body by the relay's UnifiedPushClient)
 *   through the shared [WakeDispatcher], exactly as the FCM receiver does.
 * - [onNewEndpoint] reports the endpoint to the relay via
 *   [UnifiedPushBackgroundDelivery], which registers the device on the first
 *   endpoint (deferred activation) or refreshes it afterwards.
 * - [onRegistrationFailed] / [onUnregistered] reset activation so the degraded
 *   "set up a delivery app" Home banner reappears.
 */
class UnifiedPushReceiver :
    MessagingReceiver(),
    KoinComponent {

    private val providerRegistry: ProviderRegistry by inject()
    private val delivery: UnifiedPushBackgroundDelivery by inject()

    override fun onMessage(context: Context, message: ByteArray, instance: String) {
        val data = parsePayload(message)
        Log.d(TAG, "UnifiedPush message received: type=${data["type"]}")
        WakeDispatcher(context, providerRegistry).dispatch(data)
    }

    override fun onNewEndpoint(context: Context, endpoint: String, instance: String) {
        Log.i(TAG, "UnifiedPush endpoint received")
        delivery.onEndpoint(endpoint)
    }

    override fun onRegistrationFailed(context: Context, instance: String) {
        delivery.onRegistrationFailed()
    }

    override fun onUnregistered(context: Context, instance: String) {
        delivery.onUnregistered()
    }

    /**
     * Parse the wake payload into the flat string map [WakeDispatcher] expects.
     * The relay sends a small JSON object (e.g. `{"type":"wake"}`); be lenient
     * about malformed bodies and fall back to an empty map (ignored downstream).
     */
    private fun parsePayload(message: ByteArray): Map<String, String> = try {
        val json = JSONObject(String(message, Charsets.UTF_8))
        buildMap {
            json.keys().forEach { key -> put(key, json.optString(key)) }
        }
    } catch (e: Exception) {
        Log.w(TAG, "Unparseable UnifiedPush payload; ignoring", e)
        emptyMap()
    }

    companion object {
        private const val TAG = "UnifiedPushReceiver"
    }
}
