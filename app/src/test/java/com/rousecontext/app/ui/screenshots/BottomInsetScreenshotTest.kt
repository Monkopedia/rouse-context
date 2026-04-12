package com.rousecontext.app.ui.screenshots

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import com.rousecontext.app.ui.components.appBarColors
import com.rousecontext.app.ui.navigation.ConfigureNavBar
import com.rousecontext.app.ui.navigation.LocalNavBarController
import com.rousecontext.app.ui.navigation.NavBarControllerImpl
import com.rousecontext.app.ui.screens.AuditEntry
import com.rousecontext.app.ui.screens.AuthorizedClient
import com.rousecontext.app.ui.screens.HealthConnectSetupContent
import com.rousecontext.app.ui.screens.IntegrationManageContent
import com.rousecontext.app.ui.screens.IntegrationManageState
import com.rousecontext.app.ui.screens.IntegrationStatus
import com.rousecontext.app.ui.screens.SetupMode
import com.rousecontext.app.ui.theme.RouseContextTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Verifies that on screens with bottom-anchored buttons (Disable Integration,
 * Cancel Setup, etc.) the persistent Scaffold applies the system bottom inset
 * even when the bottom NavigationBar is hidden, so the button is not clipped
 * behind a gesture-navigation area.
 *
 * The test replicates the production persistent-Scaffold wiring in
 * [com.rousecontext.app.ui.navigation.AppNavigation] and injects a fixed
 * 48dp bottom inset through `contentWindowInsets`. A red bar at the bottom
 * of the rendered output visualises the simulated gesture-bar area; the
 * action button must render entirely above it.
 *
 * See GitHub issue #57.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(
    sdk = [33],
    qualifiers = "w400dp-h800dp-xxhdpi",
    application = com.rousecontext.app.TestApplication::class
)
class BottomInsetScreenshotTest {

    @get:Rule
    val composeRule = createComposeRule()

    /**
     * Wraps [content] in a minimal replica of the persistent Scaffold from
     * [com.rousecontext.app.ui.navigation.AppNavigation] with:
     *   - `showBottomBar = false` (detail screen style)
     *   - `contentWindowInsets = WindowInsets(bottom = 48dp)` to simulate a
     *     gesture-navigation bar
     *
     * A red box is painted behind the content over the inset area so that
     * any clipped button is immediately visible as overlapping red.
     *
     * @param bottomBarAlwaysPopulated when `true`, the `bottomBar` slot is
     *   populated with an empty (zero-height) composable even when the bar
     *   is hidden. This reproduces the pre-fix behaviour where Material3
     *   Scaffold drops the `contentWindowInsets.bottom` whenever the
     *   bottomBar slot is non-empty, regardless of its measured height.
     */
    @Suppress("LongMethod")
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun PersistentScaffoldWithGestureBar(
        bottomBarAlwaysPopulated: Boolean = false,
        content: @Composable () -> Unit
    ) {
        val controller = remember { NavBarControllerImpl() }
        Box(modifier = Modifier.fillMaxSize()) {
            // Red strip at the bottom visualising the simulated gesture area.
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(48.dp)
                    .background(Color.Red)
            )
            CompositionLocalProvider(LocalNavBarController provides controller) {
                Scaffold(
                    contentWindowInsets = WindowInsets(bottom = 48.dp),
                    topBar = {
                        if (controller.showTopBar) {
                            TopAppBar(
                                title = {
                                    val custom = controller.titleContent
                                    if (custom != null) {
                                        custom()
                                    } else {
                                        AnimatedContent(
                                            targetState = controller.title,
                                            transitionSpec = {
                                                fadeIn() togetherWith fadeOut()
                                            },
                                            label = "titleCrossfade"
                                        ) { title -> Text(title) }
                                    }
                                },
                                colors = appBarColors(),
                                navigationIcon = {
                                    if (controller.showBackButton) {
                                        IconButton(onClick = {
                                            controller.onBackPressed?.invoke()
                                        }) {
                                            Icon(
                                                Icons.AutoMirrored.Filled.ArrowBack,
                                                contentDescription = "Back"
                                            )
                                        }
                                    }
                                }
                            )
                        }
                    },
                    bottomBar = {
                        // The fix: only populate bottomBar slot when the bar
                        // is actually shown. In the "pre-fix" reproduction
                        // we replicate the exact original code: a Column
                        // with `animateContentSize` wrapping an
                        // `AnimatedVisibility` whose visibility tracks
                        // showBottomBar. Even when hidden, the slot is
                        // populated, which in the buggy version caused
                        // Scaffold to drop the content bottom inset.
                        if (bottomBarAlwaysPopulated) {
                            Column(modifier = Modifier.animateContentSize()) {
                                AnimatedVisibility(
                                    visible = controller.showBottomBar,
                                    enter = slideInVertically(
                                        initialOffsetY = { it }
                                    ) + fadeIn(),
                                    exit = slideOutVertically(
                                        targetOffsetY = { it }
                                    ) + fadeOut()
                                ) {
                                    Box(modifier = Modifier.height(56.dp))
                                }
                            }
                        } else if (controller.showBottomBar) {
                            Box(modifier = Modifier.height(56.dp))
                        }
                    }
                ) { padding ->
                    Box(modifier = Modifier.padding(padding)) {
                        content()
                    }
                }
            }
        }
    }

    private fun integrationManageActiveState() = IntegrationManageState(
        status = IntegrationStatus.ACTIVE,
        recentActivity = listOf(
            AuditEntry("10:32 AM", "get_steps", 142)
        ),
        authorizedClients = listOf(
            AuthorizedClient("Claude", "Apr 2", "2 hours ago")
        )
    )

    @Test
    fun integrationManageRespectsBottomInset() {
        composeRule.setContent {
            RouseContextTheme(darkTheme = true) {
                PersistentScaffoldWithGestureBar {
                    // showBottomBar defaults to false for detail screens.
                    ConfigureNavBar(
                        title = "Health Connect",
                        showBackButton = true,
                        showBottomBar = false,
                        showTopBar = true
                    )
                    IntegrationManageContent(state = integrationManageActiveState())
                }
            }
        }
        composeRule.onRoot()
            .captureRoboImage("screenshots/99_bottom_inset_manage_dark.png")

        // The button must render entirely above the simulated 48dp gesture
        // bar. Root height is 800dp (see @Config), so the button's bottom
        // edge must be <= 752dp.
        val rootBounds = composeRule.onRoot().getBoundsInRoot()
        val buttonBounds = composeRule
            .onNodeWithText("Disable Integration")
            .assertIsDisplayed()
            .getBoundsInRoot()
        val gestureBarTop = rootBounds.bottom - 48.dp
        assert(buttonBounds.bottom <= gestureBarTop) {
            "Disable Integration button bottom (${buttonBounds.bottom}) " +
                "overlaps simulated gesture bar (top at $gestureBarTop)"
        }
    }

    /**
     * Screenshot of the "pre-fix" layout shape — bottomBar slot populated
     * with an `AnimatedVisibility`-wrapped hidden NavigationBar. This is
     * documentary only: in Material3 1.3.2 under Robolectric the button
     * ends up at the same position as in the post-fix layout, so an
     * automated assertion on clipping cannot distinguish the two. The
     * on-device bug is described in `BLOCKERS_57.md`.
     */
    @Test
    fun integrationManagePreFixLayoutSnapshot() {
        composeRule.setContent {
            RouseContextTheme(darkTheme = true) {
                PersistentScaffoldWithGestureBar(bottomBarAlwaysPopulated = true) {
                    ConfigureNavBar(
                        title = "Health Connect",
                        showBackButton = true,
                        showBottomBar = false,
                        showTopBar = true
                    )
                    IntegrationManageContent(state = integrationManageActiveState())
                }
            }
        }
        composeRule.onRoot()
            .captureRoboImage("screenshots/99_bottom_inset_manage_prefix_dark.png")
    }

    @Test
    fun healthConnectSetupRespectsBottomInset() {
        composeRule.setContent {
            RouseContextTheme(darkTheme = true) {
                PersistentScaffoldWithGestureBar {
                    ConfigureNavBar(
                        title = "Health Connect",
                        showBackButton = true,
                        showBottomBar = false
                    )
                    HealthConnectSetupContent(mode = SetupMode.SETUP)
                }
            }
        }
        composeRule.onRoot()
            .captureRoboImage("screenshots/99_bottom_inset_health_dark.png")
    }
}
