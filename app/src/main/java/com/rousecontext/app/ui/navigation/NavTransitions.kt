package com.rousecontext.app.ui.navigation

/**
 * Ordered map of tab routes to their horizontal index. Used to pick the
 * slide direction when transitioning between top-level tabs (HOME → AUDIT
 * slides left-to-right, AUDIT → HOME slides right-to-left).
 */
internal val TAB_INDEX = mapOf(
    Routes.HOME to 0,
    Routes.AUDIT to 1,
    Routes.AUDIT_BASE to 1,
    Routes.SETTINGS to 2
)

/**
 * Determines the slide direction for tab transitions based on
 * the relative index of the source and destination tabs.
 *
 * Returns `1` when the target tab is to the right of the source (or unknown),
 * `-1` when it is to the left.
 */
internal fun tabSlideDirection(initialRoute: String?, targetRoute: String?): Int {
    val from = TAB_INDEX[initialRoute] ?: return 1
    val to = TAB_INDEX[targetRoute] ?: return 1
    return if (to > from) 1 else -1
}
