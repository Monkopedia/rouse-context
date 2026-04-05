package com.rousecontext.mcp.health

<<<<<<< HEAD
/**
 * Test fake for [HealthConnectRepository].
 *
 * Set [shouldFail] to true to simulate Health Connect errors.
 * Set individual result fields to control returned data.
 */
class FakeHealthConnectRepository : HealthConnectRepository {

    var shouldFail = false

    var stepsResult = StepsData(totalSteps = 0, date = "")
    var heartRateResult = HeartRateData(samples = emptyList(), date = "")
    var sleepResult = SleepData(sessions = emptyList(), date = "")

    override suspend fun getSteps(date: String): StepsData {
        if (shouldFail) throw HealthConnectUnavailableException("Health Connect not available")
        return stepsResult
    }

    override suspend fun getHeartRate(date: String): HeartRateData {
        if (shouldFail) throw HealthConnectUnavailableException("Health Connect not available")
        return heartRateResult
    }

    override suspend fun getSleep(date: String): SleepData {
        if (shouldFail) throw HealthConnectUnavailableException("Health Connect not available")
=======
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Fake repository for testing. Returns canned data and records
 * the time range parameters it was called with.
 */
class FakeHealthConnectRepository : HealthConnectRepository {

    var stepsResult: List<DailySteps> = emptyList()
    var heartRateResult: List<HeartRateSample> = emptyList()
    var sleepResult: List<SleepSession> = emptyList()

    /** The number of days derived from the last start/end range passed to any method. */
    var lastDaysRequested: Int = -1
        private set

    override suspend fun getSteps(start: Instant, end: Instant): List<DailySteps> {
        lastDaysRequested = ChronoUnit.DAYS.between(start, end).toInt()
        return stepsResult
    }

    override suspend fun getHeartRate(start: Instant, end: Instant): List<HeartRateSample> {
        lastDaysRequested = ChronoUnit.DAYS.between(start, end).toInt()
        return heartRateResult
    }

    override suspend fun getSleepSessions(start: Instant, end: Instant): List<SleepSession> {
        lastDaysRequested = ChronoUnit.DAYS.between(start, end).toInt()
>>>>>>> feat/health-connect-tools
        return sleepResult
    }
}
