package com.rousecontext.mcp.health

import java.time.Instant

/**
 * Abstraction over the Health Connect SDK for reading health data.
 *
 * The production implementation lives in `:app` (requires Android Context).
 * Tests use a fake that returns canned data.
 */
interface HealthConnectRepository {

    /**
     * Daily step counts within the given time range.
     * Each entry maps a day (as an ISO date string, e.g. "2026-04-03") to the total steps.
     */
    suspend fun getSteps(from: Instant, to: Instant): List<DailySteps>

    /**
     * Heart rate samples within the given time range, ordered chronologically.
     */
    suspend fun getHeartRate(from: Instant, to: Instant): List<HeartRateSample>

    /**
     * Sleep sessions (with stages) that overlap the given time range.
     */
    suspend fun getSleepSessions(from: Instant, to: Instant): List<SleepSession>
}

data class DailySteps(
    val date: String,
    val count: Long
)

data class HeartRateSample(
    val time: Instant,
    val beatsPerMinute: Long
)

data class SleepSession(
    val startTime: Instant,
    val endTime: Instant,
    val stages: List<SleepStage>
)

data class SleepStage(
    val startTime: Instant,
    val endTime: Instant,
    val type: SleepStageType
)

enum class SleepStageType {
    AWAKE,
    LIGHT,
    DEEP,
    REM,
    OUT_OF_BED,
    SLEEPING,
    UNKNOWN
}

/**
 * Thrown when the Health Connect SDK is not available on this device.
 */
class HealthConnectUnavailableException(
    message: String = "Health Connect is not available on this device"
) : RuntimeException(message)
