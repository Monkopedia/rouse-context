# Health Connect Expansion

## Overview
Replace hardcoded `get_steps`, `get_heart_rate`, `get_sleep` tools with a generic query interface that supports all Health Connect record types.

## MCP Tools

### `list_record_types`
Returns all available Health Connect record types and whether the app has permission.
- **Params**: none
- **Returns**: Array of `{type, display_name, category, has_permission, description}`
- Categories: "activity", "body", "sleep", "vitals", "nutrition", "cycle"

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
Internal mapping from string names to Health Connect SDK classes:
```kotlin
val RECORD_TYPES = mapOf(
    "Steps" to StepsRecord::class,
    "HeartRate" to HeartRateRecord::class,
    "SleepSession" to SleepSessionRecord::class,
    "Weight" to WeightRecord::class,
    "BloodPressure" to BloodPressureRecord::class,
    "ActiveCaloriesBurned" to ActiveCaloriesBurnedRecord::class,
    "Distance" to DistanceRecord::class,
    "ExerciseSession" to ExerciseSessionRecord::class,
    "Hydration" to HydrationRecord::class,
    "Nutrition" to NutritionRecord::class,
    "OxygenSaturation" to OxygenSaturationRecord::class,
    "RespiratoryRate" to RespiratoryRateRecord::class,
    // ... etc
)
```

## Permissions
- Each record type requires its own Health Connect read permission
- `list_record_types` shows which ones are granted
- Setup flow should request all common permissions upfront, with option to add more later

## Architecture
- Modify existing `:health` module
- Replace hardcoded tool handlers with generic `RecordTypeRegistry`
- Each record type has a `RecordTypeHandler` that knows how to query and serialize results
- `query_health_data` dispatches to the appropriate handler by type string
