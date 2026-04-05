package com.rousecontext.mcp.health

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
        return sleepResult
    }
}
