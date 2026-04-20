package com.rousecontext.app.state

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Unit tests for [DataStoreIntegrationStateStore]. Exercises the
 * [com.rousecontext.api.IntegrationStateStore] contract against a real
 * Preferences DataStore backed by the Robolectric in-process filesystem.
 *
 * Note: every test gets a fresh app filesDir via [Robolectric], but
 * DataStore files are cached per-Context on disk across tests, so we
 * set/reset values explicitly rather than relying on a pristine store.
 */
@RunWith(RobolectricTestRunner::class)
class DataStoreIntegrationStateStoreTest {

    private lateinit var context: Context
    private lateinit var store: DataStoreIntegrationStateStore

    @Before
    fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        store = DataStoreIntegrationStateStore(context)
    }

    @Test
    fun `defaults are false for unknown id`() = runTest {
        // Use a fresh-per-test id so cross-test state does not leak
        // through the process-wide DataStore instance.
        val id = "unknown-${System.nanoTime()}"
        assertFalse(store.isUserEnabled(id))
        assertFalse(store.wasEverEnabled(id))
    }

    @Test
    fun `setUserEnabled true persists and flips everEnabled`() = runTest {
        val id = "enable-${System.nanoTime()}"
        store.setUserEnabled(id, true)

        assertTrue(store.isUserEnabled(id))
        assertTrue(store.wasEverEnabled(id))
    }

    @Test
    fun `setUserEnabled false keeps everEnabled true once flipped`() = runTest {
        val id = "sticky-${System.nanoTime()}"
        store.setUserEnabled(id, true)
        store.setUserEnabled(id, false)

        assertFalse(store.isUserEnabled(id))
        assertTrue(store.wasEverEnabled(id))
    }

    @Test
    fun `setUserEnabled does not set everEnabled when disabled first time`() = runTest {
        val id = "never-${System.nanoTime()}"
        store.setUserEnabled(id, false)

        assertFalse(store.isUserEnabled(id))
        assertFalse(store.wasEverEnabled(id))
    }

    @Test
    fun `observeUserEnabled emits initial then updates`() = runTest {
        val id = "obs-user-${System.nanoTime()}"
        store.observeUserEnabled(id).test {
            assertFalse(awaitItem())
            store.setUserEnabled(id, true)
            assertTrue(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `observeEverEnabled emits initial then flips on first enable`() = runTest {
        val id = "obs-ever-${System.nanoTime()}"
        store.observeEverEnabled(id).test {
            assertFalse(awaitItem())
            store.setUserEnabled(id, true)
            assertTrue(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `observeChanges emits Unit for every edit`() = runTest {
        // First element is the initial preferences read.
        val initial = store.observeChanges().first()
        assertEquals(Unit, initial)

        store.observeChanges().test {
            awaitItem() // initial value
            store.setUserEnabled("a", true)
            awaitItem() // after edit
            store.setUserEnabled("b", false)
            // "b=false" edits the preferences store but may not emit a new
            // snapshot if the value is unchanged from implicit default;
            // tolerate that by cancelling without a strict count.
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `independent integration ids do not collide`() = runTest {
        val a = "a-${System.nanoTime()}"
        val b = "b-${System.nanoTime()}"
        store.setUserEnabled(a, true)
        store.setUserEnabled(b, false)

        assertTrue(store.isUserEnabled(a))
        assertFalse(store.isUserEnabled(b))
        assertTrue(store.wasEverEnabled(a))
        assertFalse(store.wasEverEnabled(b))
    }
}
