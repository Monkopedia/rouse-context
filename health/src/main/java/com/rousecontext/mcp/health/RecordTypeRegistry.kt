package com.rousecontext.mcp.health

/**
 * Metadata for a Health Connect record type.
 *
 * Each entry describes a record type that can be queried via the generic
 * `query_health_data` tool. The [name] is the key used in tool arguments.
 */
data class RecordTypeInfo(
    /** Machine name used in tool args, e.g. "Steps", "HeartRate". */
    val name: String,
    /** Human-readable display name. */
    val displayName: String,
    /** Category grouping for UI/listing. */
    val category: RecordCategory,
    /** Short description of what this record type contains. */
    val description: String,
    /** Health Connect read permission string for this type. */
    val readPermission: String
)

enum class RecordCategory(val value: String) {
    ACTIVITY("activity"),
    BODY("body"),
    SLEEP("sleep"),
    VITALS("vitals"),
    NUTRITION("nutrition")
}

/**
 * Registry of all supported Health Connect record types.
 *
 * Maps string names to [RecordTypeInfo] metadata. The production
 * [HealthConnectRepository] uses these names to dispatch queries.
 */
object RecordTypeRegistry {

    private val types: Map<String, RecordTypeInfo> = listOf(
        RecordTypeInfo(
            name = "Steps",
            displayName = "Steps",
            category = RecordCategory.ACTIVITY,
            description = "Step count records",
            readPermission = "android.permission.health.READ_STEPS"
        ),
        RecordTypeInfo(
            name = "HeartRate",
            displayName = "Heart Rate",
            category = RecordCategory.VITALS,
            description = "Heart rate samples in beats per minute",
            readPermission = "android.permission.health.READ_HEART_RATE"
        ),
        RecordTypeInfo(
            name = "SleepSession",
            displayName = "Sleep",
            category = RecordCategory.SLEEP,
            description = "Sleep sessions with stage breakdowns",
            readPermission = "android.permission.health.READ_SLEEP"
        ),
        RecordTypeInfo(
            name = "Weight",
            displayName = "Weight",
            category = RecordCategory.BODY,
            description = "Body weight measurements in kilograms",
            readPermission = "android.permission.health.READ_WEIGHT"
        ),
        RecordTypeInfo(
            name = "BloodPressure",
            displayName = "Blood Pressure",
            category = RecordCategory.VITALS,
            description = "Systolic and diastolic blood pressure readings",
            readPermission = "android.permission.health.READ_BLOOD_PRESSURE"
        ),
        RecordTypeInfo(
            name = "ActiveCaloriesBurned",
            displayName = "Active Calories",
            category = RecordCategory.ACTIVITY,
            description = "Calories burned during activity",
            readPermission = "android.permission.health.READ_ACTIVE_CALORIES_BURNED"
        ),
        RecordTypeInfo(
            name = "Distance",
            displayName = "Distance",
            category = RecordCategory.ACTIVITY,
            description = "Distance traveled in meters",
            readPermission = "android.permission.health.READ_DISTANCE"
        ),
        RecordTypeInfo(
            name = "ExerciseSession",
            displayName = "Exercise",
            category = RecordCategory.ACTIVITY,
            description = "Exercise/workout sessions with type and duration",
            readPermission = "android.permission.health.READ_EXERCISE"
        ),
        RecordTypeInfo(
            name = "Hydration",
            displayName = "Hydration",
            category = RecordCategory.NUTRITION,
            description = "Fluid intake in liters",
            readPermission = "android.permission.health.READ_HYDRATION"
        ),
        RecordTypeInfo(
            name = "OxygenSaturation",
            displayName = "Oxygen Saturation",
            category = RecordCategory.VITALS,
            description = "Blood oxygen saturation percentage",
            readPermission = "android.permission.health.READ_OXYGEN_SATURATION"
        ),
        RecordTypeInfo(
            name = "RespiratoryRate",
            displayName = "Respiratory Rate",
            category = RecordCategory.VITALS,
            description = "Breaths per minute",
            readPermission = "android.permission.health.READ_RESPIRATORY_RATE"
        ),
        RecordTypeInfo(
            name = "Nutrition",
            displayName = "Nutrition",
            category = RecordCategory.NUTRITION,
            description = "Nutritional intake including calories, macros, and micronutrients",
            readPermission = "android.permission.health.READ_NUTRITION"
        )
    ).associateBy { it.name }

    /** All registered record types. */
    val allTypes: Collection<RecordTypeInfo> get() = types.values

    /** Look up a record type by name. Returns null if not found. */
    operator fun get(name: String): RecordTypeInfo? = types[name]

    /** All read permissions needed for all supported record types. */
    val allPermissions: Set<String> get() = types.values.map { it.readPermission }.toSet()
}
