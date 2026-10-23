package com.rousecontext.app.ui.navigation.destinations

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Install-path fallbacks for #803. Activity resolution is enforced so an
 * unhandled intent throws [ActivityNotFoundException] as it does on a device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = com.rousecontext.app.TestApplication::class)
class HealthConnectAvailabilityTest {

    private val application: Application = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        shadowOf(application).checkActivities(true)
    }

    @Test
    fun `install falls back to the web listing when no store handles market links`() {
        resolvable(HEALTH_CONNECT_WEB_URI)

        openHealthConnectInstall(application)

        assertEquals(HEALTH_CONNECT_WEB_URI, shadowOf(application).nextStartedActivity?.dataString)
    }

    @Test
    fun `install opens the store listing when a store is present`() {
        resolvable(HEALTH_CONNECT_MARKET_URI)
        resolvable(HEALTH_CONNECT_WEB_URI)

        openHealthConnectInstall(application)

        assertEquals(
            HEALTH_CONNECT_MARKET_URI,
            shadowOf(application).nextStartedActivity?.dataString
        )
    }

    @Test
    fun `install does not crash when nothing can open either listing`() {
        openHealthConnectInstall(application)

        assertNull(shadowOf(application).nextStartedActivity)
    }

    @Test
    fun `launch is skipped while health connect is unavailable`() {
        var launched = false

        launchHealthConnectOrInstall(application) { launched = true }

        assertFalse(launched)
    }

    private fun resolvable(uri: String) {
        shadowOf(application.packageManager).addResolveInfoForIntent(
            Intent(Intent.ACTION_VIEW, Uri.parse(uri)),
            ResolveInfo().apply {
                activityInfo = ActivityInfo().apply {
                    packageName = "store"
                    name = "StoreActivity"
                }
            }
        )
    }
}
