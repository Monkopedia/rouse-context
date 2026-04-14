package com.rousecontext.mcp.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordTypeRegistryTest {

    @Test
    fun `all types have unique names`() {
        val names = RecordTypeRegistry.allTypes.map { it.name }
        assertEquals(names.size, names.toSet().size)
    }

    @Test
    fun `lookup by name returns correct type`() {
        val steps = RecordTypeRegistry["Steps"]
        assertNotNull(steps)
        assertEquals("Steps", steps!!.name)
        assertEquals(RecordCategory.ACTIVITY, steps.category)
    }

    @Test
    fun `lookup unknown name returns null`() {
        assertNull(RecordTypeRegistry["NonExistent"])
    }

    @Test
    fun `all types have non-blank permissions`() {
        for (info in RecordTypeRegistry.allTypes) {
            assertTrue(
                "Type ${info.name} has blank permission",
                info.readPermission.isNotBlank()
            )
        }
    }

    @Test
    fun `allPermissions is non-empty and a subset of distinct type permissions`() {
        val typePermissions = RecordTypeRegistry.allTypes.map { it.readPermission }.toSet()
        assertEquals(typePermissions, RecordTypeRegistry.allPermissions)
        assertTrue(RecordTypeRegistry.allPermissions.isNotEmpty())
    }

    @Test
    fun `known types are present`() {
        val expected = listOf(
            // Original 13
            "Steps", "HeartRate", "SleepSession", "Weight", "BloodPressure",
            "BloodGlucose", "ActiveCaloriesBurned", "Distance", "ExerciseSession",
            "Hydration", "OxygenSaturation", "RespiratoryRate", "Nutrition",
            // Body additions
            "Height", "BodyFat", "BoneMass", "LeanBodyMass", "Vo2Max",
            // Vitals additions
            "HeartRateVariabilityRmssd", "RestingHeartRate",
            "BodyTemperature", "BasalBodyTemperature", "SkinTemperature",
            // Activity additions
            "TotalCaloriesBurned", "BasalMetabolicRate", "FloorsClimbed",
            "Speed", "Power", "ElevationGained", "CyclingPedalingCadence",
            "StepsCadence", "WheelchairPushes",
            // Reproductive
            "MenstruationFlow", "MenstruationPeriod", "CervicalMucus",
            "OvulationTest", "IntermenstrualBleeding", "SexualActivity",
            // Mindfulness
            "MindfulnessSession"
        )
        for (name in expected) {
            assertNotNull("Missing type: $name", RecordTypeRegistry[name])
        }
    }

    @Test
    fun `expected total type count`() {
        // 13 original + 5 body + 5 vitals + 9 activity + 6 reproductive + 1 mindfulness = 39
        assertEquals(39, RecordTypeRegistry.allTypes.size)
    }

    @Test
    fun `menstruation flow and period share the same permission`() {
        val flow = RecordTypeRegistry["MenstruationFlow"]!!
        val period = RecordTypeRegistry["MenstruationPeriod"]!!
        assertEquals(flow.readPermission, period.readPermission)
        assertEquals("android.permission.health.READ_MENSTRUATION", flow.readPermission)
    }

    @Test
    fun `reproductive category is used`() {
        val reproductive = RecordTypeRegistry.allTypes
            .filter { it.category == RecordCategory.REPRODUCTIVE }
            .map { it.name }
            .toSet()
        assertEquals(
            setOf(
                "MenstruationFlow",
                "MenstruationPeriod",
                "CervicalMucus",
                "OvulationTest",
                "IntermenstrualBleeding",
                "SexualActivity"
            ),
            reproductive
        )
    }

    @Test
    fun `all permissions match AndroidManifest`() {
        // Load the manifest file relative to the module root.
        val manifest = java.io.File("src/main/AndroidManifest.xml").readText()
        for (info in RecordTypeRegistry.allTypes) {
            assertTrue(
                "Manifest missing permission ${info.readPermission} for ${info.name}",
                manifest.contains("android:name=\"${info.readPermission}\"")
            )
        }
    }
}
