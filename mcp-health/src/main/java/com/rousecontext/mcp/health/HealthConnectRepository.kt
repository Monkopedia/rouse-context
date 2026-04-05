package com.rousecontext.mcp.health

/**
 * Abstraction over Health Connect SDK queries.
 *
 * Production implementation wraps [androidx.health.connect.client.HealthConnectClient].
 * Tests use [FakeHealthConnectRepository].
 */
interface HealthConnectRepository {

    /** Read aggregated steps for the given date (yyyy-MM-dd). */
    suspend fun getSteps(date: String): StepsData

    /** Read heart rate samples for the given date (yyyy-MM-dd). */
    suspend fun getHeartRate(date: String): HeartRateData

    /** Read sleep sessions for the given date (yyyy-MM-dd). */
    suspend fun getSleep(date: String): SleepData
}
