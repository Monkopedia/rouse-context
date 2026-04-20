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
class HealthConnectIntegrationTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `exposes canonical health metadata`() {
        val integration = HealthConnectIntegration(context)

        assertEquals("health", integration.id)
        assertEquals("Health Connect", integration.displayName)
        assertEquals("/health", integration.path)
        assertEquals("setup", integration.onboardingRoute)
        assertEquals("settings", integration.settingsRoute)
        assertTrue(integration.description.isNotBlank())
    }

    @Test
    fun `provider exposes a non-blank id`() {
        val integration = HealthConnectIntegration(context)
        // HealthConnectMcpServer uses its own id ("health-connect") for MCP
        // protocol advertising. The integration keeps the shorter path id.
        assertTrue(integration.provider.id.isNotBlank())
        assertTrue(integration.provider.displayName.isNotBlank())
    }

    @Test
    fun `isAvailable returns true on API P+`() = runBlocking {
        // Robolectric defaults to a modern SDK level (well above API 28).
        val integration = HealthConnectIntegration(context)
        assertTrue(integration.isAvailable())
    }
}
