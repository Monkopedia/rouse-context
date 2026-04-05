package com.rousecontext.mcp.health

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import com.rousecontext.mcp.core.McpIntegration
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows

@RunWith(RobolectricTestRunner::class)
class HealthConnectIntegrationTest {

    private val context: Context = RuntimeEnvironment.getApplication()
    private val integration: McpIntegration = HealthConnectIntegration(context)

    @Test
    fun `id is health`() {
        assertEquals("health", integration.id)
    }

    @Test
    fun `displayName is Health Connect`() {
        assertEquals("Health Connect", integration.displayName)
    }

    @Test
    fun `description describes shared data`() {
        assertEquals(
            "Share step count, heart rate, and sleep data with AI clients",
            integration.description,
        )
    }

    @Test
    fun `path is health`() {
        assertEquals("health", integration.path)
    }

    @Test
    fun `provider is a HealthConnectMcpServer`() {
        assertNotNull(integration.provider)
        assertTrue(integration.provider is HealthConnectMcpServer)
    }

    @Test
    fun `onboardingRoute is health setup`() {
        assertEquals("health/setup", integration.onboardingRoute)
    }

    @Test
    fun `settingsRoute is health settings`() {
        assertEquals("health/settings", integration.settingsRoute)
    }

    @Test
    fun `isAvailable returns false when Health Connect is not installed`() = runBlocking {
        assertFalse(integration.isAvailable())
    }

    @Test
    fun `isAvailable returns true when Health Connect is installed`() = runBlocking {
        val shadowPackageManager = Shadows.shadowOf(context.packageManager)
        shadowPackageManager.installPackage(
            PackageInfo().apply {
                packageName = HealthConnectIntegration.HEALTH_CONNECT_PACKAGE
            },
        )

        assertTrue(integration.isAvailable())
    }
}
