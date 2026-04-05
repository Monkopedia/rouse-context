package com.rousecontext.mcp.health

import java.time.Instant

/**
 * Test fake for [HealthConnectRepository].
 *
 * Populate [steps], [heartRateSamples], and [sleepSessions] before calling
 * the query methods. Set [shouldThrow] to simulate Health Connect being unavailable.
 */
class FakeHealthConnectRepository : HealthConnectRepository {

    var steps: List<DailySteps> = emptyList()
    var heartRateSamples: List<HeartRateSample> = emptyList()
    var sleepSessions: List<SleepSession> = emptyList()

    /** When non-null, all methods throw this exception. */
    var shouldThrow: Exception? = null

    override suspend fun getSteps(from: Instant, to: Instant): List<DailySteps> {
        shouldThrow?.let { throw it }
        return steps.filter { entry ->
            // Simple filtering: include all pre-loaded data (caller controls the fixture)
            true
        }
    }

    override suspend fun getHeartRate(from: Instant, to: Instant): List<HeartRateSample> {
        shouldThrow?.let { throw it }
        return heartRateSamples.filter { it.time in from..to }
    }

    override suspend fun getSleepSessions(from: Instant, to: Instant): List<SleepSession> {
        shouldThrow?.let { throw it }
        return sleepSessions.filter { session ->
            session.startTime < to && session.endTime > from
        }
    }
}
