package com.rousecontext.app.support

import android.content.Context
import android.os.PowerManager
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class BatteryOptimizationTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `isExempt reflects PowerManager state`() {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val shadow = shadowOf(pm)

        shadow.setIgnoringBatteryOptimizations(context.packageName, true)
        assertTrue(BatteryOptimization.isExempt(context))

        shadow.setIgnoringBatteryOptimizations(context.packageName, false)
        assertFalse(BatteryOptimization.isExempt(context))
    }

    @Test
    fun `requestExemptionIntent targets the one-tap allow dialog for this package`() {
        val intent = BatteryOptimization.requestExemptionIntent(context)
        assertEquals(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, intent.action)
        assertEquals("package:${context.packageName}", intent.data.toString())
    }
}
