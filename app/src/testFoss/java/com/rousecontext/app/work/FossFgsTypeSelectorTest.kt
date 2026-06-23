package com.rousecontext.app.work

import android.content.pm.ServiceInfo
import android.os.Build
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for the foss [FossFgsTypeSelector].
 *
 * Verifies the opt-in mapping: ON → specialUse (escaping the Android 15 6h cap),
 * OFF → dataSync, and the API-level floor (specialUse only exists on API 34+).
 */
@RunWith(RobolectricTestRunner::class)
class FossFgsTypeSelectorTest {

    @Test
    @Config(sdk = [Build.VERSION_CODES.VANILLA_ICE_CREAM])
    fun `setting ON returns SPECIAL_USE on API 34 plus`() {
        val selector = FossFgsTypeSelector(ignoreDailyTimeLimit = { true })
        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            selector.foregroundServiceType()
        )
    }

    @Test
    @Config(sdk = [Build.VERSION_CODES.VANILLA_ICE_CREAM])
    fun `setting OFF returns DATA_SYNC on API 34 plus`() {
        val selector = FossFgsTypeSelector(ignoreDailyTimeLimit = { false })
        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            selector.foregroundServiceType()
        )
    }

    @Test
    @Config(sdk = [Build.VERSION_CODES.TIRAMISU])
    fun `setting ON falls back to DATA_SYNC below API 34`() {
        // specialUse is not a valid FGS type before API 34, and there is no 6h
        // cap to escape pre-Android-15, so we always run as dataSync there.
        val selector = FossFgsTypeSelector(ignoreDailyTimeLimit = { true })
        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            selector.foregroundServiceType()
        )
    }
}
