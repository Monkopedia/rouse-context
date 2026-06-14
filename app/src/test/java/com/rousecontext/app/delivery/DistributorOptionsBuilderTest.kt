package com.rousecontext.app.delivery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the "Background delivery" picker row rules from issue #463:
 * ntfy is suggested ONLY when nothing is installed; otherwise the list shows
 * exactly the installed distributors plus "install another app".
 */
class DistributorOptionsBuilderTest {

    @Test
    fun `nothing installed suggests ntfy plus install-another`() {
        val rows = DistributorOptionsBuilder.build(installed = emptyList(), activeId = null)

        assertEquals(2, rows.size)
        assertEquals(DistributorOption.Kind.INSTALL_NTFY, rows[0].kind)
        assertEquals(DistributorOptionsBuilder.NTFY_PACKAGE, rows[0].id)
        assertEquals(DistributorOption.Kind.INSTALL_OTHER, rows[1].kind)
    }

    @Test
    fun `installed distributors listed without an install-ntfy row`() {
        val rows = DistributorOptionsBuilder.build(
            installed = listOf(
                DistributorOptionsBuilder.Installed("io.heckel.ntfy", "ntfy"),
                DistributorOptionsBuilder.Installed(
                    "org.unifiedpush.distributor.nextpush",
                    "NextPush"
                )
            ),
            activeId = null
        )

        assertEquals(3, rows.size)
        assertEquals(DistributorOption.Kind.INSTALLED, rows[0].kind)
        assertEquals(DistributorOption.Kind.INSTALLED, rows[1].kind)
        assertEquals(DistributorOption.Kind.INSTALL_OTHER, rows[2].kind)
        assertTrue(rows.none { it.kind == DistributorOption.Kind.INSTALL_NTFY })
    }

    @Test
    fun `non-ntfy only still never injects an install-ntfy row`() {
        val rows = DistributorOptionsBuilder.build(
            installed = listOf(
                DistributorOptionsBuilder.Installed(
                    "org.unifiedpush.distributor.nextpush",
                    "NextPush"
                )
            ),
            activeId = null
        )

        assertEquals(2, rows.size)
        assertEquals("NextPush", rows[0].name)
        assertEquals(DistributorOption.Kind.INSTALLED, rows[0].kind)
        assertFalse(rows.any { it.kind == DistributorOption.Kind.INSTALL_NTFY })
    }

    @Test
    fun `active distributor is marked active`() {
        val rows = DistributorOptionsBuilder.build(
            installed = listOf(
                DistributorOptionsBuilder.Installed("io.heckel.ntfy", "ntfy"),
                DistributorOptionsBuilder.Installed(
                    "org.unifiedpush.distributor.nextpush",
                    "NextPush"
                )
            ),
            activeId = "io.heckel.ntfy"
        )

        val ntfy = rows.first { it.id == "io.heckel.ntfy" }
        assertEquals(DistributorOption.Kind.ACTIVE, ntfy.kind)
        assertEquals("Active", ntfy.subtitle)
        val nextpush = rows.first { it.name == "NextPush" }
        assertEquals(DistributorOption.Kind.INSTALLED, nextpush.kind)
    }
}
