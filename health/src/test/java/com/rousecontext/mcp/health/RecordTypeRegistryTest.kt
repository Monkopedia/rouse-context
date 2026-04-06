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
    fun `allPermissions returns one per type`() {
        assertEquals(
            RecordTypeRegistry.allTypes.size,
            RecordTypeRegistry.allPermissions.size
        )
    }

    @Test
    fun `known types are present`() {
        val expected = listOf(
            "Steps", "HeartRate", "SleepSession", "Weight", "BloodPressure",
            "ActiveCaloriesBurned", "Distance", "ExerciseSession",
            "Hydration", "OxygenSaturation", "RespiratoryRate", "Nutrition"
        )
        for (name in expected) {
            assertNotNull("Missing type: $name", RecordTypeRegistry[name])
        }
    }
}
