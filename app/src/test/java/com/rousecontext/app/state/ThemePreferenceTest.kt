package com.rousecontext.app.state

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ThemePreferenceTest {

    private lateinit var context: Context
    private lateinit var pref: ThemePreference

    @Before
    fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        context.filesDir.listFiles()?.forEach { it.deleteRecursively() }
        pref = ThemePreference(context)
    }

    @Test
    fun `default theme is AUTO when explicitly set`() = runTest {
        pref.setThemeMode(ThemeMode.AUTO)
        pref.themeMode.test {
            assertEquals(ThemeMode.AUTO, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `setThemeMode persists each enum value`() = runTest {
        pref.setThemeMode(ThemeMode.LIGHT)
        pref.themeMode.test {
            assertEquals(ThemeMode.LIGHT, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }

        pref.setThemeMode(ThemeMode.DARK)
        pref.themeMode.test {
            assertEquals(ThemeMode.DARK, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }

        pref.setThemeMode(ThemeMode.AUTO)
        pref.themeMode.test {
            assertEquals(ThemeMode.AUTO, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `themeMode emits updates as setter runs`() = runTest {
        pref.themeMode.test {
            assertEquals(ThemeMode.AUTO, awaitItem())
            pref.setThemeMode(ThemeMode.DARK)
            assertEquals(ThemeMode.DARK, awaitItem())
            pref.setThemeMode(ThemeMode.LIGHT)
            assertEquals(ThemeMode.LIGHT, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }
}
