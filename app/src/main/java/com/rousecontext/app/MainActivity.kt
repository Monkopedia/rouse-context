package com.rousecontext.app

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.animation.DecelerateInterpolator
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.animation.doOnEnd
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.rousecontext.app.ui.navigation.RouseContextApp
import com.rousecontext.app.ui.navigation.Routes
import com.rousecontext.notifications.PerToolCallNotifier
import com.rousecontext.notifications.SessionSummaryNotifier
import com.rousecontext.tunnel.CertificateStore
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

class MainActivity : ComponentActivity() {
    private val certStore: CertificateStore by inject()

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()

        // Navy status bar in both themes = always white icons
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(
                Color.parseColor("#0F1A30")
            )
        )
        super.onCreate(savedInstanceState)

        // Compose state for the resolved start destination. Null until determined.
        var startDestination: String? by mutableStateOf(null)

        // Keep splash visible until we know which screen to show.
        splashScreen.setKeepOnScreenCondition { startDestination == null }

        // Determine start destination (getSubdomain is suspend).
        // New users go to the Welcome screen; returning users go straight to Home.
        // Registration itself is non-blocking — it runs in the background after
        // the user taps "Get Started" and lands on Home immediately.
        lifecycleScope.launch {
            val hasSubdomain = certStore.getSubdomain() != null
            val notificationDestination = destinationForNotificationIntent(intent)
            startDestination = when {
                !hasSubdomain -> Routes.ONBOARDING
                notificationDestination != null -> notificationDestination
                else -> Routes.HOME
            }
        }

        // Animated exit: fade + scale the splash screen out, then fix status bar
        splashScreen.setOnExitAnimationListener { splashScreenView ->
            val view = splashScreenView.view
            val fade = ObjectAnimator.ofFloat(view, "alpha", 1f, 0f)
            val scaleX = ObjectAnimator.ofFloat(view, "scaleX", 1f, SPLASH_EXIT_SCALE)
            val scaleY = ObjectAnimator.ofFloat(view, "scaleY", 1f, SPLASH_EXIT_SCALE)
            AnimatorSet().apply {
                playTogether(fade, scaleX, scaleY)
                duration = SPLASH_EXIT_DURATION_MS
                interpolator = DecelerateInterpolator()
                doOnEnd {
                    splashScreenView.remove()
                    // Navy bars = always white status bar icons
                    val controller = WindowCompat
                        .getInsetsController(window, window.decorView)
                    controller.isAppearanceLightStatusBars = false
                }
                start()
            }
        }

        setContent {
            // Only compose the app once the destination is resolved — the splash screen
            // covers the window until then, so the user never sees a blank frame.
            startDestination?.let { dest ->
                RouseContextApp(startDestination = dest)
            }
        }
    }

    companion object {
        private const val SPLASH_EXIT_DURATION_MS = 400L
        private const val SPLASH_EXIT_SCALE = 1.15f

        /**
         * Resolve a notification-tap [Intent] to a nav route, or null if the
         * intent does not carry a notification deep-link. Notifications from
         * the per-tool-call and session-summary notifiers carry actions like
         * [PerToolCallNotifier.ACTION_OPEN_AUDIT_HISTORY] and extras we can
         * convert into one of the [Routes] entries.
         *
         * The mapping:
         *  - Per-call tap / summary tap → [Routes.AUDIT_BASE] (intent extras
         *    like scrollToCallId / start+end millis are carried through for
         *    the destination composable to pick up).
         *  - `Manage` action button with a single integration id → the
         *    integration manage route.
         *  - `Manage` action button for a mixed-integration summary → home.
         *
         * We return a plain route string so the [RouseContextApp] start
         * destination can be chosen without a live NavController. Intent
         * extras are left on the Activity intent so destinations can read
         * them via `LocalActivityIntent` / `LocalContext.findActivity()`
         * helpers already in the codebase.
         */
        internal fun destinationForNotificationIntent(intent: Intent?): String? {
            if (intent == null) return null
            return when (intent.action) {
                PerToolCallNotifier.ACTION_OPEN_AUDIT_HISTORY,
                SessionSummaryNotifier.ACTION_OPEN_AUDIT_HISTORY -> Routes.AUDIT_BASE
                PerToolCallNotifier.ACTION_OPEN_INTEGRATION_MANAGE,
                SessionSummaryNotifier.ACTION_OPEN_INTEGRATION_MANAGE -> {
                    val id = intent.getStringExtra(PerToolCallNotifier.EXTRA_INTEGRATION_ID)
                        ?: intent.getStringExtra(SessionSummaryNotifier.EXTRA_INTEGRATION_ID)
                    if (id != null) Routes.integrationManage(id) else Routes.HOME
                }
                SessionSummaryNotifier.ACTION_OPEN_HOME -> Routes.HOME
                else -> null
            }
        }
    }
}
