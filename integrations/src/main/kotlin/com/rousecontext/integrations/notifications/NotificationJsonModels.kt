package com.rousecontext.integrations.notifications

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Wire models for [NotificationMcpProvider] tool results.
 *
 * Built as `@Serializable` data classes so user-supplied content (notification
 * keys, action labels, error messages) is JSON-escaped by `kotlinx.serialization`
 * rather than concatenated into a string template. See issue #417.
 *
 * Field names and order match the previous handcrafted JSON exactly — this is
 * a behavior-preserving migration of the encoding step, not a wire format change.
 */

/**
 * Wraps `success` + `message` (e.g. action errors and confirmations).
 * Preserves the exact wire shape `{"success":<bool>,"message":"..."}`.
 */
@Serializable
internal data class NotificationStatusMessage(val success: Boolean, val message: String)

/**
 * Wraps just `success` (used by dismiss_notification's success path).
 */
@Serializable
internal data class NotificationSuccess(val success: Boolean)

/**
 * Single shared [Json] instance. `encodeDefaults = true` so the constant
 * `success` defaults serialize, matching the previous string-template output.
 */
internal val NotificationJson: Json = Json { encodeDefaults = true }
