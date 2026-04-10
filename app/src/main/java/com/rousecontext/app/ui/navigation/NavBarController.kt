package com.rousecontext.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Controls the persistent app bar from individual screens.
 * Each screen configures the bar declaratively via [ConfigureNavBar].
 */
@Stable
interface NavBarController {
    var title: String
    var showBackButton: Boolean
    var showBottomBar: Boolean
    var showTopBar: Boolean
    var onBackPressed: (() -> Unit)?
    var titleContent: (@Composable () -> Unit)?
}

/**
 * Concrete implementation backed by [mutableStateOf] so the Scaffold
 * recomposes when any property changes.
 */
@Stable
class NavBarControllerImpl : NavBarController {
    override var title: String by mutableStateOf("")
    override var showBackButton: Boolean by mutableStateOf(false)
    override var showBottomBar: Boolean by mutableStateOf(false)
    override var showTopBar: Boolean by mutableStateOf(true)
    override var onBackPressed: (() -> Unit)? by mutableStateOf(null)
    override var titleContent: (@Composable () -> Unit)? by mutableStateOf(null)
}

/**
 * Provides the [NavBarController] to the composition tree.
 * Throws if accessed outside a [CompositionLocalProvider].
 */
val LocalNavBarController = compositionLocalOf<NavBarController> {
    error("No NavBarController provided")
}

/**
 * Declaratively configures the persistent app bar from a screen composable.
 * Call at the top of each screen's composition.
 *
 * All properties — including [titleContent] — are set atomically inside a
 * single [LaunchedEffect] keyed on every parameter, which eliminates the
 * race where `titleContent` from a previous screen is still visible while
 * the new screen's `LaunchedEffect` hasn't fired yet.
 */
@Composable
fun ConfigureNavBar(
    title: String = "",
    showBackButton: Boolean = false,
    showBottomBar: Boolean = false,
    showTopBar: Boolean = true,
    onBackPressed: (() -> Unit)? = null,
    titleContent: (@Composable () -> Unit)? = null
) {
    val controller = LocalNavBarController.current
    LaunchedEffect(
        title,
        showBackButton,
        showBottomBar,
        showTopBar,
        onBackPressed,
        titleContent
    ) {
        controller.title = title
        controller.showBackButton = showBackButton
        controller.showBottomBar = showBottomBar
        controller.showTopBar = showTopBar
        controller.onBackPressed = onBackPressed
        controller.titleContent = titleContent
    }
}
