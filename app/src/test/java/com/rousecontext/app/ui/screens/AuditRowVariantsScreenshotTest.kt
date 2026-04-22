package com.rousecontext.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.rousecontext.app.ui.theme.RouseContextTheme
import java.io.File
import org.junit.ClassRule
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Captures Roborazzi screenshots of seven candidate client-label placement
 * options for the audit history's `ToolCallRow` (issue #343). Each variant
 * is rendered in both light and dark theme against the three sample client
 * labels (short, long, unknown) declared in [VariantSamples], writing PNGs
 * to `app/screenshots/audit-row-variants/` and emitting a catalog HTML to
 * `docs/internal/audit-row-variants/index.html` once every test has run.
 *
 * Production <code>ToolCallRow</code> at
 * <code>AuditHistoryScreen.kt:413</code> is **not** modified.
 *
 * Run with:
 * ```
 * JAVA_HOME=/usr/lib/jvm/java-21-openjdk \
 *   ./gradlew :app:clearRoborazziDebug :app:recordRoborazziDebug --rerun-tasks \
 *   --tests "com.rousecontext.app.ui.screens.AuditRowVariantsScreenshotTest"
 * ```
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(
    sdk = [33],
    qualifiers = "w400dp-h800dp-xxhdpi",
    application = com.rousecontext.app.TestApplication::class
)
class AuditRowVariantsScreenshotTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun capture(
        base: String,
        label: String,
        description: String,
        darkTheme: Boolean,
        content: @Composable () -> Unit
    ) {
        CapturedVariants.record(base, label, description)
        composeRule.setContent {
            RouseContextTheme(darkTheme = darkTheme) {
                androidx.compose.foundation.layout.Box(
                    modifier = Modifier.background(MaterialTheme.colorScheme.background)
                ) {
                    content()
                }
            }
        }
        val theme = if (darkTheme) "dark" else "light"
        composeRule.onRoot().captureRoboImage(
            "screenshots/audit-row-variants/${base}_$theme.png"
        )
    }

    // =========================================================================
    // Variant 1 — Third row, full-width client label
    // =========================================================================

    @Test
    fun variant1Light() = capture(
        base = VARIANT_1,
        label = LABEL_1,
        description = DESC_1,
        darkTheme = false
    ) {
        VariantRowsCard(VariantSamples.allRows) { Variant1ThirdRow(it) }
    }

    @Test
    fun variant1Dark() = capture(
        base = VARIANT_1,
        label = LABEL_1,
        description = DESC_1,
        darkTheme = true
    ) {
        VariantRowsCard(VariantSamples.allRows) { Variant1ThirdRow(it) }
    }

    // =========================================================================
    // Variant 2 — Second-row prefix
    // =========================================================================

    @Test
    fun variant2Light() = capture(
        base = VARIANT_2,
        label = LABEL_2,
        description = DESC_2,
        darkTheme = false
    ) {
        VariantRowsCard(VariantSamples.allRows) { Variant2SecondRowPrefix(it) }
    }

    @Test
    fun variant2Dark() = capture(
        base = VARIANT_2,
        label = LABEL_2,
        description = DESC_2,
        darkTheme = true
    ) {
        VariantRowsCard(VariantSamples.allRows) { Variant2SecondRowPrefix(it) }
    }

    // =========================================================================
    // Variant 3 — Right-aligned pill
    // =========================================================================

    @Test
    fun variant3Light() = capture(
        base = VARIANT_3,
        label = LABEL_3,
        description = DESC_3,
        darkTheme = false
    ) {
        VariantRowsCard(VariantSamples.allRows) { Variant3RightAlignedPill(it) }
    }

    @Test
    fun variant3Dark() = capture(
        base = VARIANT_3,
        label = LABEL_3,
        description = DESC_3,
        darkTheme = true
    ) {
        VariantRowsCard(VariantSamples.allRows) { Variant3RightAlignedPill(it) }
    }

    // =========================================================================
    // Variant 4 — Leading initial chip
    // =========================================================================

    @Test
    fun variant4Light() = capture(
        base = VARIANT_4,
        label = LABEL_4,
        description = DESC_4,
        darkTheme = false
    ) {
        VariantRowsCard(VariantSamples.allRows) { Variant4LeadingInitial(it) }
    }

    @Test
    fun variant4Dark() = capture(
        base = VARIANT_4,
        label = LABEL_4,
        description = DESC_4,
        darkTheme = true
    ) {
        VariantRowsCard(VariantSamples.allRows) { Variant4LeadingInitial(it) }
    }

    // =========================================================================
    // Variant 5 — Session-grouped header
    // =========================================================================

    @Test
    fun variant5Light() = capture(
        base = VARIANT_5,
        label = LABEL_5,
        description = DESC_5,
        darkTheme = false
    ) {
        VariantSessionsCard(VariantSamples.sessions) { Variant5SessionGrouped(it) }
    }

    @Test
    fun variant5Dark() = capture(
        base = VARIANT_5,
        label = LABEL_5,
        description = DESC_5,
        darkTheme = true
    ) {
        VariantSessionsCard(VariantSamples.sessions) { Variant5SessionGrouped(it) }
    }

    // =========================================================================
    // Variant 6 — Variant 1 + Variant 5 combined
    // =========================================================================

    @Test
    fun variant6Light() = capture(
        base = VARIANT_6,
        label = LABEL_6,
        description = DESC_6,
        darkTheme = false
    ) {
        VariantSessionsCard(VariantSamples.sessions) { Variant6RowLabelAndSessionHeader(it) }
    }

    @Test
    fun variant6Dark() = capture(
        base = VARIANT_6,
        label = LABEL_6,
        description = DESC_6,
        darkTheme = true
    ) {
        VariantSessionsCard(VariantSamples.sessions) { Variant6RowLabelAndSessionHeader(it) }
    }

    // =========================================================================
    // Variant 7 — Right-aligned pill with time stacked below
    // =========================================================================

    @Test
    fun variant7Light() = capture(
        base = VARIANT_7,
        label = LABEL_7,
        description = DESC_7,
        darkTheme = false
    ) {
        VariantRowsCard(VariantSamples.allRows) { Variant7TimeBelowPill(it) }
    }

    @Test
    fun variant7Dark() = capture(
        base = VARIANT_7,
        label = LABEL_7,
        description = DESC_7,
        darkTheme = true
    ) {
        VariantRowsCard(VariantSamples.allRows) { Variant7TimeBelowPill(it) }
    }

    companion object {
        private const val VARIANT_1 = "01_third_row"
        private const val VARIANT_2 = "02_second_row_prefix"
        private const val VARIANT_3 = "03_right_aligned_pill"
        private const val VARIANT_4 = "04_leading_initial"
        private const val VARIANT_5 = "05_session_grouped_header"
        private const val VARIANT_6 = "06_row_label_plus_session_header"
        private const val VARIANT_7 = "07_time_below_pill"

        private const val LABEL_1 = "1. Third row, full-width"
        private const val LABEL_2 = "2. Second-row prefix"
        private const val LABEL_3 = "3. Right-aligned pill"
        private const val LABEL_4 = "4. Leading initial chip"
        private const val LABEL_5 = "5. Session-grouped header"
        private const val LABEL_6 = "6. Row label + session header"
        private const val LABEL_7 = "7. Right-aligned pill with time below"

        private const val DESC_1 =
            "Client shown as a labelSmall line beneath the existing duration+args row. " +
                "Keeps the top row untouched; every row gets its own label."
        private const val DESC_2 =
            "Client label prefixes the duration+args row: " +
                "\"Claude \u00B7 142ms \u00B7 {days: 7}\". Cheapest change; no new rows."
        private const val DESC_3 =
            "Client rendered as a pill/chip between the tool name and the time. " +
                "Top row gets busier; scanability depends on label length."
        private const val DESC_4 =
            "Circular initial chip (first letter, deterministic color) leads the row. " +
                "Strongest visual anchor; needs a color per client."
        private const val DESC_5 =
            "Rows grouped under a session header that shows the client label once. " +
                "Cleanest rows, but loses per-row context at a glance."
        private const val DESC_6 =
            "Variant 1 + Variant 5: session header AND per-row client label. " +
                "Redundant on purpose for scan-by-row scenarios."
        private const val DESC_7 =
            "Client pill sits alone at the top-right of row 1; the timestamp " +
                "right-aligns on row 2 directly beneath it. Keeps the pill " +
                "prominent without crowding the time next to it."

        /**
         * After every test in this class has run, write the catalog HTML to
         * `docs/internal/audit-row-variants/index.html`. Test CWD is the
         * `:app` module, so the output path resolves relative to that.
         */
        @ClassRule
        @JvmField
        val htmlCatalogWriter: ExternalResource = object : ExternalResource() {
            override fun before() {
                CapturedVariants.clear()
            }

            override fun after() {
                val cases = CapturedVariants.snapshot()
                if (cases.isEmpty()) return
                val output = File("../docs/internal/audit-row-variants/index.html")
                AuditRowCatalogHtmlWriter.write(output, cases)
            }
        }
    }
}
