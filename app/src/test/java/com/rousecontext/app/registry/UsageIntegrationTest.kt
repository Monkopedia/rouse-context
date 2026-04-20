package com.rousecontext.app.registry

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class UsageIntegrationTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `exposes canonical usage metadata`() {
        val integration = UsageIntegration(context)

        assertEquals("usage", integration.id)
        assertEquals("Usage Stats", integration.displayName)
        assertEquals("/usage", integration.path)
        assertEquals("setup", integration.onboardingRoute)
        assertEquals("settings", integration.settingsRoute)
        assertTrue(integration.description.isNotBlank())
    }

    @Test
    fun `provider id matches integration id`() {
        val integration = UsageIntegration(context)
        assertEquals("usage", integration.provider.id)
    }

    @Test
    fun `isAvailable always true`() = runBlocking {
        assertTrue(UsageIntegration(context).isAvailable())
    }
}
