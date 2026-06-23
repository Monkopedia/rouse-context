package com.rousecontext.app.work

import android.content.pm.ServiceInfo
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit test for the google [GoogleFgsTypeSelector]: the Play build always runs
 * the tunnel foreground service as dataSync and never declares specialUse.
 */
class GoogleFgsTypeSelectorTest {

    @Test
    fun `always returns DATA_SYNC`() {
        val selector = GoogleFgsTypeSelector()
        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            selector.foregroundServiceType()
        )
    }
}
