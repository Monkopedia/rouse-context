package com.rousecontext.app.push

import android.util.Log
import org.json.JSONObject

/**
 * Pure parse logic for an incoming UnifiedPush message body.
 *
 * Separated from [UnifiedPushReceiver] for testability (issue #481) and serving
 * as the `foss` transport analogue of [com.rousecontext.work.FcmDispatch]:
 * Firebase hands `FcmReceiver` an already-parsed `Map<String, String>`, whereas
 * UnifiedPush delivers the relay's `{"type":"wake"}` / `{"type":"renew"}` payload
 * as a raw JSON POST body. This byte→map step is the foss-specific decoding that
 * precedes the shared [com.rousecontext.work.FcmDispatch.resolve] dispatch logic.
 */
object UnifiedPushPayload {

    private const val TAG = "UnifiedPushPayload"

    /**
     * Parse the wake payload into the flat string map [com.rousecontext.work.WakeDispatcher]
     * expects. The relay sends a small JSON object (e.g. `{"type":"wake"}`); be
     * lenient about malformed bodies and fall back to an empty map (ignored
     * downstream).
     */
    fun parse(message: ByteArray): Map<String, String> = try {
        val json = JSONObject(String(message, Charsets.UTF_8))
        buildMap {
            json.keys().forEach { key -> put(key, json.optString(key)) }
        }
    } catch (e: Exception) {
        Log.w(TAG, "Unparseable UnifiedPush payload; ignoring", e)
        emptyMap()
    }
}
