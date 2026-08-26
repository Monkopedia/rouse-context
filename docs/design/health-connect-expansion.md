# Health Connect Expansion

**Status:** Implemented. Reference doc rather than a proposal — the
generic query interface described below is shipped in `:integrations`
(`com.rousecontext.integrations.health`). This document is kept as a
high-level reference; the source of truth for record types is
[`RecordTypeRegistry.kt`](../../integrations/src/main/java/com/rousecontext/integrations/health/RecordTypeRegistry.kt).

## Overview
Generic Health Connect query surface that supports all the record types
the app holds permissions for, replacing the original hardcoded
`get_steps` / `get_heart_rate` / `get_sleep` tools.

## MCP Tools

### `list_record_types`
Returns all available Health Connect record types and whether the app has permission.
- **Params**: none
- **Returns**: Array of `{type, display_name, category, has_permission, description}`
- Categories: `activity`, `body`, `sleep`, `vitals`, `nutrition`, `reproductive`, `mindfulness`

### `query_health_data`
Generic query for any record type.
- **Params**: `record_type` (e.g. "Steps", "HeartRate", "SleepSession", "Weight", "BloodPressure"), `since` (ISO datetime), `until` (ISO datetime), `limit` (optional), `bucket` (optional, e.g. `5m`, `1h`, `1d`)
- **Returns**: `{record_type, count, total_count, downsampled, records}` where `records` is a
  type-specific array. Each record has at minimum `{start_time, end_time}` plus type-specific fields.
- Examples:
  - Steps: `{start_time, end_time, count}` (per-day rollup)
  - HeartRate: `{time, bpm}`
  - SleepSession: `{start_time, end_time, stages: [{stage, start, end}]}`
  - Weight: `{time, kg}`
  - BloodPressure: `{time, systolic, diastolic}`

**Caps and spanning ranges.** The read is bounded by the cap (`limit`, else 500) and the bound
reaches the Health Connect request itself, so a small `limit` costs a small read. When the range
holds more than the cap, the records that fit would be only its *earliest slice* — not an answer
about the range — so the query is instead answered with per-window aggregates spanning the whole
range: `{record_type, bucket, total_count, buckets: [{start, count, min, max, avg}], note}`. Record
types that cannot be aggregated (sessions, multi-value, cumulative) fall back to records evenly
spread across the range with `downsampled: true`.

Passing `bucket` asks for those per-window aggregates directly. Bucketing is supported for
instantaneous single-scalar types only (BloodGlucose, HeartRate, HRV, SpO2, respiratory rate,
temperatures, weight, height, body fat, bone mass, lean body mass, VO2 max) and rejects a too-fine
bucket over a wide range (max 1000 buckets). Aggregation streams records page by page and folds
them as they arrive, so a wide range is never materialised.

A single aggregation folds at most `MAX_RECORDS` (50,000) samples. A range denser than that is
reported with `truncated: true` and a `note` naming how far the buckets actually reach — the
buckets then cover only the earliest part of the range, and nothing claims otherwise.

Health Connect's own `aggregateGroupByDuration` is deliberately not used: as of
`connect-client:1.1.0` it has no `AggregateMetric` for BloodGlucose, HRV, SpO2, respiratory rate, or
either body temperature — six of the eight bucketable types, including the CGM case that motivated
this. A metric-based path would therefore cover two types while the streaming fold has to exist
anyway for the rest.

### `get_health_summary`
High-level health summary across multiple data types.
- **Params**: `period` ("today", "week", "month")
- **Returns**: `{steps_total, avg_heart_rate, sleep_hours, weight_latest, active_minutes, ...}` — includes whatever data types have permission and data

## Record Type Registry

All supported record types are declared in `RecordTypeRegistry.kt` as
`RecordTypeInfo` entries (machine `name`, `displayName`,
`RecordCategory`, `description`, Health Connect read permission). The
shipped registry covers seven categories:

| Category       | Examples                                                                                                  |
|----------------|-----------------------------------------------------------------------------------------------------------|
| `activity`     | Steps, ActiveCaloriesBurned, TotalCaloriesBurned, BasalMetabolicRate, Distance, ElevationGained, FloorsClimbed, ExerciseSession, Speed, Power, CyclingPedalingCadence, StepsCadence, WheelchairPushes |
| `body`         | Weight, Height, BodyFat, BoneMass, LeanBodyMass, Vo2Max                                                   |
| `sleep`        | SleepSession                                                                                              |
| `vitals`       | HeartRate, RestingHeartRate, HeartRateVariabilityRmssd, BloodPressure, BloodGlucose, OxygenSaturation, RespiratoryRate, BodyTemperature, BasalBodyTemperature, SkinTemperature |
| `nutrition`    | Hydration, Nutrition                                                                                      |
| `reproductive` | MenstruationFlow, MenstruationPeriod, CervicalMucus, OvulationTest, IntermenstrualBleeding, SexualActivity |
| `mindfulness`  | MindfulnessSession                                                                                        |

See `RecordTypeRegistry.kt` for the authoritative list, exact display
names, descriptions, and Health Connect read-permission strings.

## Permissions
- Each record type requires its own Health Connect read permission (see `RecordTypeInfo.readPermission`).
- `list_record_types` reports which ones are currently granted.
- Setup flow requests the common permissions upfront, with the option to add more later.

## Architecture

- Lives in the `:integrations` module
  (`com.rousecontext.integrations.health`).
- `HealthConnectMcpServer` registers the three tools above.
- Dispatch is by record type via `repository.queryRecords(type, …)` —
  `HealthConnectRepository` (interface) /
  `RealHealthConnectRepository` (production) delegate the actual SDK
  calls to category-grouped query files under
  `com.rousecontext.integrations.health.query`:
  - `ActivityQueries.kt`
  - `BodyQueries.kt`
  - `SleepQueries.kt`
  - `VitalsQueries.kt`
  - `NutritionQueries.kt`
  - `ReproductiveQueries.kt`
  - `MindfulnessQueries.kt`
  - `CategoryQueries.kt` (cross-category aggregation for `get_health_summary`)
  - `RecordReader.kt` (shared SDK read helpers)
- There is no per-type `RecordTypeHandler` class — the original
  proposal anticipated one, but the shipped implementation groups
  query logic by category for less surface area per record type.
