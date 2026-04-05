package com.rousecontext.mcp.health

import java.time.Instant

/**
 * Abstraction over Health Connect data access.
 * The real implementation uses [HealthConnectClient]; tests use a fake.
 */
interface HealthConnectRepository {
    /**
     * Daily step counts for the given time range.
     */
    suspend fun getSteps(start: Instant, end: Instant): List<DailySteps>

    /**
     * Heart rate samples for the given time range.
     */
    suspend fun getHeartRate(start: Instant, end: Instant): List<HeartRateSample>

    /**
     * Sleep sessions for the given time range.
     */
    suspend fun getSleepSessions(start: Instant, end: Instant): List<SleepSession>
}

data class DailySteps(
    val date: String,
    val count: Long,
)

data class HeartRateSample(
    val time: Instant,
    val bpm: Long,
)

data class SleepSession(
    val startTime: Instant,
    val endTime: Instant,
    val durationMinutes: Long,
    val stages: List<SleepStage>,
)

data class SleepStage(
    val stage: String,
    val startTime: Instant,
    val endTime: Instant,
)
