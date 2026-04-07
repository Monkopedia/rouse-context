package com.rousecontext.app

import android.animation.ObjectAnimator
import android.os.Bundle
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
import com.rousecontext.tunnel.CertificateStore
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

class MainActivity : ComponentActivity() {
    private val certStore: CertificateStore by inject()

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()

        val isDarkSystem = (resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        enableEdgeToEdge(
            statusBarStyle = if (isDarkSystem) {
                SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
            } else {
                SystemBarStyle.light(
                    android.graphics.Color.TRANSPARENT,
                    android.graphics.Color.TRANSPARENT
                )
            }
        )
        super.onCreate(savedInstanceState)

        // Compose state for the resolved start destination. Null until determined.
        var startDestination: String? by mutableStateOf(null)

        // Keep splash visible until we know which screen to show.
        splashScreen.setKeepOnScreenCondition { startDestination == null }

        // Determine start destination (getSubdomain is suspend).
        lifecycleScope.launch {
            val hasSubdomain = certStore.getSubdomain() != null
            startDestination = if (hasSubdomain) Routes.HOME else Routes.ONBOARDING
        }

        // Animated exit: cross-fade the splash screen out, then fix status bar
        splashScreen.setOnExitAnimationListener { splashScreenView ->
            ObjectAnimator.ofFloat(splashScreenView.view, "alpha", 1f, 0f).apply {
                duration = SPLASH_FADE_DURATION_MS
                doOnEnd {
                    splashScreenView.remove()
                    // Re-apply status bar style after splash removal
                    // The splash theme may have overridden it
                    val controller = WindowCompat
                        .getInsetsController(window, window.decorView)
                    controller.isAppearanceLightStatusBars = !isDarkSystem
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
        private const val SPLASH_FADE_DURATION_MS = 300L
    }
}
