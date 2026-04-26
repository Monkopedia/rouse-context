package com.rousecontext.integrations.outreach

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Wire models for [OutreachMcpProvider] tool results.
 *
 * Built as `@Serializable` data classes so user-supplied content (exception
 * messages, app names, channel descriptions) is JSON-escaped by
 * `kotlinx.serialization` rather than concatenated into a string template.
 *
 * The field names and order match the previous handcrafted JSON exactly —
 * these classes are a behavior-preserving migration of the encoding step,
 * not a wire format change. See issue #417.
 */

@Serializable
internal data class OutreachError(val success: Boolean = false, val error: String)

@Serializable
internal data class OutreachOk(val success: Boolean = true, val message: String)

@Serializable
internal data class SendNotificationResult(
    val success: Boolean = true,
    @SerialName("notification_id") val notificationId: Int
)

@Serializable
internal data class DndState(val enabled: Boolean, val mode: String)

@Serializable
internal data class SetDndStateResult(
    val success: Boolean = true,
    @SerialName("previous_state") val previousState: DndState
)

@Serializable
internal data class NotificationChannelDto(
    val id: String,
    val name: String,
    val description: String,
    val importance: String,
    val vibration: Boolean,
    val sound: Boolean,
    @SerialName("show_badge") val showBadge: Boolean
)

@Serializable
internal data class InstalledApp(val `package`: String, val name: String, val system: Boolean)

/**
 * Single shared [Json] instance. `encodeDefaults = true` so the constant
 * `success` defaults serialize to wire alongside dynamic fields, matching the
 * previous string-template output where these were always present.
 */
internal val OutreachJson: Json = Json { encodeDefaults = true }
