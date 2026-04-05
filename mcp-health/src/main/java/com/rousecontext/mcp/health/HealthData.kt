package com.rousecontext.mcp.health

/** Aggregated step count for a single day. */
data class StepsData(
    val totalSteps: Long,
    val date: String,
)

/** A single heart rate measurement. */
data class HeartRateSample(
    val bpm: Int,
    val time: String,
)

/** Heart rate samples for a single day. */
data class HeartRateData(
    val samples: List<HeartRateSample>,
    val date: String,
)

/** A single sleep session. */
data class SleepSession(
    val startTime: String,
    val endTime: String,
    val durationMinutes: Long,
)

/** Sleep sessions for a single day. */
data class SleepData(
    val sessions: List<SleepSession>,
    val date: String,
)
