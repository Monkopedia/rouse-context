package com.rousecontext.integrations.usage

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Wire models for [UsageMcpProvider] tool results.
 *
 * Built as `@Serializable` data classes so user-supplied content (resolved app
 * labels, package names) is JSON-escaped by `kotlinx.serialization` rather
 * than concatenated into a string template. See issue #417.
 *
 * Field names and order match the previous handcrafted JSON exactly — this is
 * a behavior-preserving migration of the encoding step, not a wire format change.
 */

@Serializable
internal data class UsageError(val success: Boolean = false, val error: String)

@Serializable
internal data class AppUsageEntry(
    val `package`: String,
    val name: String,
    @SerialName("foreground_minutes") val foregroundMinutes: Long
)

@Serializable
internal data class UsageSummary(
    val period: String,
    @SerialName("total_minutes") val totalMinutes: Long,
    val apps: List<AppUsageEntry>
)

@Serializable
internal data class DailyUsage(
    val date: String,
    @SerialName("foreground_minutes") val foregroundMinutes: Long
)

@Serializable
internal data class AppUsage(
    val `package`: String,
    val name: String,
    val period: String,
    @SerialName("total_minutes") val totalMinutes: Long,
    val daily: List<DailyUsage>
)

@Serializable
internal data class UsageEvent(
    val `package`: String,
    val name: String,
    val type: String,
    val timestamp: String
)

@Serializable
internal data class UsageEventList(val events: List<UsageEvent>)

@Serializable
internal data class UsageDelta(
    val `package`: String,
    val name: String,
    @SerialName("period1_minutes") val period1Minutes: Long,
    @SerialName("period2_minutes") val period2Minutes: Long,
    val change: String
)

@Serializable
internal data class UsageComparison(
    val period1: String,
    val period2: String,
    @SerialName("total1_minutes") val total1Minutes: Long,
    @SerialName("total2_minutes") val total2Minutes: Long,
    val apps: List<UsageDelta>
)

/**
 * Single shared [Json] instance. `encodeDefaults = true` so the constant
 * `success` defaults serialize, matching the previous string-template output
 * where these were always present.
 */
internal val UsageJson: Json = Json { encodeDefaults = true }
