package com.rousecontext.app.state

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class IntegrationSettingsStoreTest {

    private lateinit var context: Context
    private lateinit var store: IntegrationSettingsStore

    @Before
    fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        store = IntegrationSettingsStore(context)
    }

    @Test
    fun `getBoolean returns default when unset`() = runTest {
        val id = "id-${System.nanoTime()}"
        assertFalse(store.getBoolean(id, "allow_actions"))
        assertTrue(store.getBoolean(id, "allow_actions", default = true))
    }

    @Test
    fun `setBoolean persists and reads back`() = runTest {
        val id = "id-${System.nanoTime()}"
        store.setBoolean(id, IntegrationSettingsStore.KEY_ALLOW_ACTIONS, true)
        assertTrue(store.getBoolean(id, IntegrationSettingsStore.KEY_ALLOW_ACTIONS))
    }

    @Test
    fun `getInt returns default when unset`() = runTest {
        val id = "id-${System.nanoTime()}"
        assertEquals(7, store.getInt(id, "retention_days", default = 7))
        assertEquals(30, store.getInt(id, "retention_days", default = 30))
    }

    @Test
    fun `setInt persists and reads back`() = runTest {
        val id = "id-${System.nanoTime()}"
        store.setInt(id, IntegrationSettingsStore.KEY_RETENTION_DAYS, 14)
        assertEquals(
            14,
            store.getInt(id, IntegrationSettingsStore.KEY_RETENTION_DAYS, default = 7)
        )
    }

    @Test
    fun `observeBoolean emits initial default and every write`() = runTest {
        val id = "id-${System.nanoTime()}"
        store.observeBoolean(id, IntegrationSettingsStore.KEY_DND_TOGGLED).test {
            assertFalse(awaitItem())
            store.setBoolean(id, IntegrationSettingsStore.KEY_DND_TOGGLED, true)
            assertTrue(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `observeInt emits initial default and every write`() = runTest {
        val id = "id-${System.nanoTime()}"
        store.observeInt(id, IntegrationSettingsStore.KEY_RETENTION_DAYS, 7).test {
            assertEquals(7, awaitItem())
            store.setInt(id, IntegrationSettingsStore.KEY_RETENTION_DAYS, 30)
            assertEquals(30, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `different integration ids do not collide`() = runTest {
        val a = "a-${System.nanoTime()}"
        val b = "b-${System.nanoTime()}"
        store.setBoolean(a, IntegrationSettingsStore.KEY_DND_TOGGLED, true)
        store.setBoolean(b, IntegrationSettingsStore.KEY_DND_TOGGLED, false)

        assertTrue(store.getBoolean(a, IntegrationSettingsStore.KEY_DND_TOGGLED))
        assertFalse(store.getBoolean(b, IntegrationSettingsStore.KEY_DND_TOGGLED))
    }

    @Test
    fun `booleanKey and intKey namespace by integration id`() {
        val aBool = IntegrationSettingsStore.booleanKey("outreach", "x")
        val bBool = IntegrationSettingsStore.booleanKey("notifications", "x")
        assertNotEquals(aBool, bBool)

        val aInt = IntegrationSettingsStore.intKey("outreach", "x")
        val bInt = IntegrationSettingsStore.intKey("notifications", "x")
        assertNotEquals(aInt, bInt)
    }

    @Test
    fun `observeAll emits preferences snapshot reflecting writes`() = runTest {
        val id = "obs-all-${System.nanoTime()}"
        store.observeAll().test {
            awaitItem() // Initial snapshot (may be non-empty from prior tests).
            store.setBoolean(id, IntegrationSettingsStore.KEY_DND_TOGGLED, true)
            val updated = awaitItem()
            val key = IntegrationSettingsStore.booleanKey(
                id,
                IntegrationSettingsStore.KEY_DND_TOGGLED
            )
            assertEquals(true, updated[key])
            cancelAndIgnoreRemainingEvents()
        }
    }
}
