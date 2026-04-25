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
- **Params**: `record_type` (e.g. "Steps", "HeartRate", "SleepSession", "Weight", "BloodPressure"), `since` (ISO datetime), `until` (ISO datetime), `limit` (optional)
- **Returns**: Type-specific array of records. Each record has at minimum `{start_time, end_time}` plus type-specific fields.
- Examples:
  - Steps: `{start_time, end_time, count}`
  - HeartRate: `{time, bpm}`
  - SleepSession: `{start_time, end_time, stages: [{stage, start, end}]}`
  - Weight: `{time, kg}`
  - BloodPressure: `{time, systolic, diastolic}`

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
