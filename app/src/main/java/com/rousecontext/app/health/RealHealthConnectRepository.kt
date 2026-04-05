package com.rousecontext.app.health

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.rousecontext.mcp.health.DailySteps
import com.rousecontext.mcp.health.HealthConnectRepository
import com.rousecontext.mcp.health.HealthConnectUnavailableException
import com.rousecontext.mcp.health.HeartRateSample
import com.rousecontext.mcp.health.SleepSession
import com.rousecontext.mcp.health.SleepStage
import com.rousecontext.mcp.health.SleepStageType
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Production [HealthConnectRepository] backed by the Health Connect SDK.
 *
 * Lives in `:app` because it needs an Android [Context] to obtain the
 * [HealthConnectClient] instance.
 */
class RealHealthConnectRepository(
    private val context: Context
) : HealthConnectRepository {

    private val client: HealthConnectClient by lazy {
        val status = HealthConnectClient.getSdkStatus(context)
        if (status != HealthConnectClient.SDK_AVAILABLE) {
            throw HealthConnectUnavailableException()
        }
        HealthConnectClient.getOrCreate(context)
    }

    private val dateFormatter = DateTimeFormatter.ISO_LOCAL_DATE

    override suspend fun getSteps(from: Instant, to: Instant): List<DailySteps> {
        val response = client.readRecords(
            ReadRecordsRequest(
                recordType = StepsRecord::class,
                timeRangeFilter = TimeRangeFilter.between(from, to)
            )
        )

        return response.records
            .groupBy { record ->
                record.startTime
                    .atZone(ZoneId.systemDefault())
                    .toLocalDate()
            }
            .map { (date, records) ->
                DailySteps(
                    date = date.format(dateFormatter),
                    count = records.sumOf { it.count }
                )
            }
            .sortedBy { it.date }
    }

    override suspend fun getHeartRate(from: Instant, to: Instant): List<HeartRateSample> {
        val response = client.readRecords(
            ReadRecordsRequest(
                recordType = HeartRateRecord::class,
                timeRangeFilter = TimeRangeFilter.between(from, to)
            )
        )

        return response.records
            .flatMap { record ->
                record.samples.map { sample ->
                    HeartRateSample(
                        time = sample.time,
                        beatsPerMinute = sample.beatsPerMinute
                    )
                }
            }
            .sortedBy { it.time }
    }

    override suspend fun getSleepSessions(from: Instant, to: Instant): List<SleepSession> {
        val response = client.readRecords(
            ReadRecordsRequest(
                recordType = SleepSessionRecord::class,
                timeRangeFilter = TimeRangeFilter.between(from, to)
            )
        )

        return response.records.map { record ->
            SleepSession(
                startTime = record.startTime,
                endTime = record.endTime,
                stages = record.stages.map { stage ->
                    SleepStage(
                        startTime = stage.startTime,
                        endTime = stage.endTime,
                        type = mapSleepStageType(stage.stage)
                    )
                }
            )
        }
    }

    private fun mapSleepStageType(stage: Int): SleepStageType = when (stage) {
        SleepSessionRecord.STAGE_TYPE_AWAKE -> SleepStageType.AWAKE
        SleepSessionRecord.STAGE_TYPE_LIGHT -> SleepStageType.LIGHT
        SleepSessionRecord.STAGE_TYPE_DEEP -> SleepStageType.DEEP
        SleepSessionRecord.STAGE_TYPE_REM -> SleepStageType.REM
        SleepSessionRecord.STAGE_TYPE_OUT_OF_BED -> SleepStageType.OUT_OF_BED
        SleepSessionRecord.STAGE_TYPE_SLEEPING -> SleepStageType.SLEEPING
        else -> SleepStageType.UNKNOWN
    }
}
