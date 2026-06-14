package com.rousecontext.app.ui.screenshots

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.rousecontext.app.delivery.DistributorOption
import com.rousecontext.app.ui.screens.BackgroundDeliveryRowState
import com.rousecontext.app.ui.screens.BackgroundDeliveryScreen
import com.rousecontext.app.ui.screens.DashboardState
import com.rousecontext.app.ui.screens.DeliveryBanner
import com.rousecontext.app.ui.screens.HomeDashboardContent
import com.rousecontext.app.ui.screens.SettingsContent
import com.rousecontext.app.ui.screens.SettingsState
import com.rousecontext.app.ui.theme.RouseContextTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Golden screenshots of the SHIPPING "Background delivery" surfaces (issue
 * #463): the real picker screen, the degraded-Home banner, and the Settings
 * row — the production composables, not the proposal mocks in
 * [FossDeliveryMockTest]. Drives them with crafted state (no UnifiedPush /
 * Koin), so they render under Robolectric.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(
    sdk = [33],
    qualifiers = "w400dp-h800dp-xxhdpi",
    application = com.rousecontext.app.TestApplication::class
)
class BackgroundDeliveryScreenshotTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun capture(name: String, dark: Boolean, content: @Composable () -> Unit) {
        composeRule.setContent { RouseContextTheme(darkTheme = dark) { content() } }
        composeRule.onRoot().captureRoboImage(
            "screenshots/${name}_${if (dark) "dark" else "light"}.png"
        )
    }

    private fun noneInstalled() = listOf(
        DistributorOption(
            "io.heckel.ntfy",
            "ntfy",
            "Recommended · not installed",
            DistributorOption.Kind.INSTALL_NTFY
        ),
        DistributorOption(
            "",
            "Install another app",
            "Browse delivery apps",
            DistributorOption.Kind.INSTALL_OTHER
        )
    )

    private fun someInstalled() = listOf(
        DistributorOption("io.heckel.ntfy", "ntfy", "Installed", DistributorOption.Kind.INSTALLED),
        DistributorOption(
            "org.nextpush",
            "NextPush",
            "Installed",
            DistributorOption.Kind.INSTALLED
        ),
        DistributorOption(
            "",
            "Install another app",
            "Browse delivery apps",
            DistributorOption.Kind.INSTALL_OTHER
        )
    )

    private fun oneActive() = listOf(
        DistributorOption("io.heckel.ntfy", "ntfy", "Active", DistributorOption.Kind.ACTIVE),
        DistributorOption(
            "org.nextpush",
            "NextPush",
            "Installed",
            DistributorOption.Kind.INSTALLED
        ),
        DistributorOption(
            "",
            "Install another app",
            "Browse delivery apps",
            DistributorOption.Kind.INSTALL_OTHER
        )
    )

    @Test fun pickerNoneLight() = capture("80_real_picker_none", false) {
        BackgroundDeliveryScreen(noneInstalled(), settingsMode = false, {}, {}, {})
    }

    @Test fun pickerNoneDark() = capture("80_real_picker_none", true) {
        BackgroundDeliveryScreen(noneInstalled(), settingsMode = false, {}, {}, {})
    }

    @Test fun pickerInstalledLight() = capture("81_real_picker_installed", false) {
        BackgroundDeliveryScreen(someInstalled(), settingsMode = false, {}, {}, {})
    }

    @Test fun pickerInstalledDark() = capture("81_real_picker_installed", true) {
        BackgroundDeliveryScreen(someInstalled(), settingsMode = false, {}, {}, {})
    }

    @Test fun pickerActiveSettingsLight() = capture("82_real_picker_active_settings", false) {
        BackgroundDeliveryScreen(oneActive(), settingsMode = true, {}, {}, {})
    }

    @Test fun pickerActiveSettingsDark() = capture("82_real_picker_active_settings", true) {
        BackgroundDeliveryScreen(oneActive(), settingsMode = true, {}, {}, {})
    }

    @Test fun degradedBannerLight() = capture("83_real_degraded_banner", false) {
        HomeDashboardContent(state = DashboardState(deliveryBanner = DeliveryBanner))
    }

    @Test fun degradedBannerDark() = capture("83_real_degraded_banner", true) {
        HomeDashboardContent(state = DashboardState(deliveryBanner = DeliveryBanner))
    }

    @Test fun settingsRowLight() = capture("84_real_settings_row", false) {
        SettingsContent(
            state = SettingsState(backgroundDelivery = BackgroundDeliveryRowState("ntfy"))
        )
    }

    @Test fun settingsRowDark() = capture("84_real_settings_row", true) {
        SettingsContent(
            state = SettingsState(backgroundDelivery = BackgroundDeliveryRowState("ntfy"))
        )
    }
}
