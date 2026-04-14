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
    NUTRITION("nutrition"),
    REPRODUCTIVE("reproductive"),
    MINDFULNESS("mindfulness")
}

/**
 * Registry of all supported Health Connect record types.
 *
 * Maps string names to [RecordTypeInfo] metadata. The production
 * [HealthConnectRepository] uses these names to dispatch queries.
 */
object RecordTypeRegistry {

    private val types: Map<String, RecordTypeInfo> = listOf(
        // --- Activity ---
        RecordTypeInfo(
            name = "Steps",
            displayName = "Steps",
            category = RecordCategory.ACTIVITY,
            description = "Step count records",
            readPermission = "android.permission.health.READ_STEPS"
        ),
        RecordTypeInfo(
            name = "ActiveCaloriesBurned",
            displayName = "Active Calories",
            category = RecordCategory.ACTIVITY,
            description = "Calories burned during activity",
            readPermission = "android.permission.health.READ_ACTIVE_CALORIES_BURNED"
        ),
        RecordTypeInfo(
            name = "TotalCaloriesBurned",
            displayName = "Total Calories",
            category = RecordCategory.ACTIVITY,
            description = "Total energy expenditure including basal metabolism",
            readPermission = "android.permission.health.READ_TOTAL_CALORIES_BURNED"
        ),
        RecordTypeInfo(
            name = "BasalMetabolicRate",
            displayName = "Basal Metabolic Rate",
            category = RecordCategory.ACTIVITY,
            description = "Resting energy expenditure in watts",
            readPermission = "android.permission.health.READ_BASAL_METABOLIC_RATE"
        ),
        RecordTypeInfo(
            name = "Distance",
            displayName = "Distance",
            category = RecordCategory.ACTIVITY,
            description = "Distance traveled in meters",
            readPermission = "android.permission.health.READ_DISTANCE"
        ),
        RecordTypeInfo(
            name = "ElevationGained",
            displayName = "Elevation Gained",
            category = RecordCategory.ACTIVITY,
            description = "Vertical distance climbed in meters",
            readPermission = "android.permission.health.READ_ELEVATION_GAINED"
        ),
        RecordTypeInfo(
            name = "FloorsClimbed",
            displayName = "Floors Climbed",
            category = RecordCategory.ACTIVITY,
            description = "Number of floors ascended",
            readPermission = "android.permission.health.READ_FLOORS_CLIMBED"
        ),
        RecordTypeInfo(
            name = "ExerciseSession",
            displayName = "Exercise",
            category = RecordCategory.ACTIVITY,
            description = "Exercise/workout sessions with type and duration",
            readPermission = "android.permission.health.READ_EXERCISE"
        ),
        RecordTypeInfo(
            name = "Speed",
            displayName = "Speed",
            category = RecordCategory.ACTIVITY,
            description = "Movement speed samples in meters per second",
            readPermission = "android.permission.health.READ_SPEED"
        ),
        RecordTypeInfo(
            name = "Power",
            displayName = "Power",
            category = RecordCategory.ACTIVITY,
            description = "Power output samples in watts",
            readPermission = "android.permission.health.READ_POWER"
        ),
        RecordTypeInfo(
            name = "CyclingPedalingCadence",
            displayName = "Cycling Cadence",
            category = RecordCategory.ACTIVITY,
            description = "Cycling pedaling cadence in RPM",
            readPermission = "android.permission.health.READ_EXERCISE"
        ),
        RecordTypeInfo(
            name = "StepsCadence",
            displayName = "Steps Cadence",
            category = RecordCategory.ACTIVITY,
            description = "Step rate samples in steps per minute",
            readPermission = "android.permission.health.READ_STEPS"
        ),
        RecordTypeInfo(
            name = "WheelchairPushes",
            displayName = "Wheelchair Pushes",
            category = RecordCategory.ACTIVITY,
            description = "Wheelchair push counts",
            readPermission = "android.permission.health.READ_WHEELCHAIR_PUSHES"
        ),

        // --- Body ---
        RecordTypeInfo(
            name = "Weight",
            displayName = "Weight",
            category = RecordCategory.BODY,
            description = "Body weight measurements in kilograms",
            readPermission = "android.permission.health.READ_WEIGHT"
        ),
        RecordTypeInfo(
            name = "Height",
            displayName = "Height",
            category = RecordCategory.BODY,
            description = "Body height measurements in meters",
            readPermission = "android.permission.health.READ_HEIGHT"
        ),
        RecordTypeInfo(
            name = "BodyFat",
            displayName = "Body Fat",
            category = RecordCategory.BODY,
            description = "Body fat percentage",
            readPermission = "android.permission.health.READ_BODY_FAT"
        ),
        RecordTypeInfo(
            name = "BoneMass",
            displayName = "Bone Mass",
            category = RecordCategory.BODY,
            description = "Bone mass in kilograms",
            readPermission = "android.permission.health.READ_BONE_MASS"
        ),
        RecordTypeInfo(
            name = "LeanBodyMass",
            displayName = "Lean Body Mass",
            category = RecordCategory.BODY,
            description = "Lean body mass in kilograms",
            readPermission = "android.permission.health.READ_LEAN_BODY_MASS"
        ),
        RecordTypeInfo(
            name = "Vo2Max",
            displayName = "VO2 Max",
            category = RecordCategory.BODY,
            description = "Maximal oxygen uptake in mL/min/kg",
            readPermission = "android.permission.health.READ_VO2_MAX"
        ),

        // --- Sleep ---
        RecordTypeInfo(
            name = "SleepSession",
            displayName = "Sleep",
            category = RecordCategory.SLEEP,
            description = "Sleep sessions with stage breakdowns",
            readPermission = "android.permission.health.READ_SLEEP"
        ),

        // --- Vitals ---
        RecordTypeInfo(
            name = "HeartRate",
            displayName = "Heart Rate",
            category = RecordCategory.VITALS,
            description = "Heart rate samples in beats per minute",
            readPermission = "android.permission.health.READ_HEART_RATE"
        ),
        RecordTypeInfo(
            name = "RestingHeartRate",
            displayName = "Resting Heart Rate",
            category = RecordCategory.VITALS,
            description = "Resting heart rate in beats per minute",
            readPermission = "android.permission.health.READ_RESTING_HEART_RATE"
        ),
        RecordTypeInfo(
            name = "HeartRateVariabilityRmssd",
            displayName = "Heart Rate Variability",
            category = RecordCategory.VITALS,
            description = "Heart rate variability (RMSSD) in milliseconds",
            readPermission = "android.permission.health.READ_HEART_RATE_VARIABILITY"
        ),
        RecordTypeInfo(
            name = "BloodPressure",
            displayName = "Blood Pressure",
            category = RecordCategory.VITALS,
            description = "Systolic and diastolic blood pressure readings",
            readPermission = "android.permission.health.READ_BLOOD_PRESSURE"
        ),
        RecordTypeInfo(
            name = "BloodGlucose",
            displayName = "Blood Glucose",
            category = RecordCategory.VITALS,
            description = "Blood glucose readings in mmol/L",
            readPermission = "android.permission.health.READ_BLOOD_GLUCOSE"
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
            name = "BodyTemperature",
            displayName = "Body Temperature",
            category = RecordCategory.VITALS,
            description = "Body temperature in Celsius",
            readPermission = "android.permission.health.READ_BODY_TEMPERATURE"
        ),
        RecordTypeInfo(
            name = "BasalBodyTemperature",
            displayName = "Basal Body Temperature",
            category = RecordCategory.VITALS,
            description = "Basal body temperature in Celsius",
            readPermission = "android.permission.health.READ_BASAL_BODY_TEMPERATURE"
        ),
        RecordTypeInfo(
            name = "SkinTemperature",
            displayName = "Skin Temperature",
            category = RecordCategory.VITALS,
            description = "Skin temperature baseline and deltas in Celsius",
            readPermission = "android.permission.health.READ_SKIN_TEMPERATURE"
        ),

        // --- Nutrition ---
        RecordTypeInfo(
            name = "Hydration",
            displayName = "Hydration",
            category = RecordCategory.NUTRITION,
            description = "Fluid intake in liters",
            readPermission = "android.permission.health.READ_HYDRATION"
        ),
        RecordTypeInfo(
            name = "Nutrition",
            displayName = "Nutrition",
            category = RecordCategory.NUTRITION,
            description = "Nutritional intake including calories, macros, and micronutrients",
            readPermission = "android.permission.health.READ_NUTRITION"
        ),

        // --- Reproductive / cycle tracking ---
        RecordTypeInfo(
            name = "MenstruationFlow",
            displayName = "Menstruation Flow",
            category = RecordCategory.REPRODUCTIVE,
            description = "Menstrual flow intensity (light/medium/heavy)",
            readPermission = "android.permission.health.READ_MENSTRUATION"
        ),
        RecordTypeInfo(
            name = "MenstruationPeriod",
            displayName = "Menstruation Period",
            category = RecordCategory.REPRODUCTIVE,
            description = "Start and end of a menstruation period",
            readPermission = "android.permission.health.READ_MENSTRUATION"
        ),
        RecordTypeInfo(
            name = "CervicalMucus",
            displayName = "Cervical Mucus",
            category = RecordCategory.REPRODUCTIVE,
            description = "Cervical mucus appearance and sensation",
            readPermission = "android.permission.health.READ_CERVICAL_MUCUS"
        ),
        RecordTypeInfo(
            name = "OvulationTest",
            displayName = "Ovulation Test",
            category = RecordCategory.REPRODUCTIVE,
            description = "Ovulation test results",
            readPermission = "android.permission.health.READ_OVULATION_TEST"
        ),
        RecordTypeInfo(
            name = "IntermenstrualBleeding",
            displayName = "Intermenstrual Bleeding",
            category = RecordCategory.REPRODUCTIVE,
            description = "Bleeding outside of a menstrual period",
            readPermission = "android.permission.health.READ_INTERMENSTRUAL_BLEEDING"
        ),
        RecordTypeInfo(
            name = "SexualActivity",
            displayName = "Sexual Activity",
            category = RecordCategory.REPRODUCTIVE,
            description = "Sexual activity with protection status",
            readPermission = "android.permission.health.READ_SEXUAL_ACTIVITY"
        ),

        // --- Mindfulness ---
        RecordTypeInfo(
            name = "MindfulnessSession",
            displayName = "Mindfulness",
            category = RecordCategory.MINDFULNESS,
            description = "Mindfulness sessions (meditation, breathing, etc.)",
            readPermission = "android.permission.health.READ_MINDFULNESS"
        )
    ).associateBy { it.name }

    /** All registered record types. */
    val allTypes: Collection<RecordTypeInfo> get() = types.values

    /** Look up a record type by name. Returns null if not found. */
    operator fun get(name: String): RecordTypeInfo? = types[name]

    /** All read permissions needed for all supported record types. */
    val allPermissions: Set<String> get() = types.values.map { it.readPermission }.toSet()
}
