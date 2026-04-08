package com.rousecontext.app.ui.screenshots

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.rousecontext.app.ui.screens.AuditEntry
import com.rousecontext.app.ui.screens.AuthorizedClient
import com.rousecontext.app.ui.screens.ConnectionStatus
import com.rousecontext.app.ui.screens.DashboardState
import com.rousecontext.app.ui.screens.IntegrationItem
import com.rousecontext.app.ui.screens.IntegrationManageScreen
import com.rousecontext.app.ui.screens.IntegrationManageState
import com.rousecontext.app.ui.screens.IntegrationStatus
import com.rousecontext.app.ui.screens.MainDashboardScreen
import com.rousecontext.app.ui.theme.RouseContextTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(
    sdk = [33],
    qualifiers = "w400dp-h800dp-hdpi",
    application = com.rousecontext.app.TestApplication::class
)
class ManageScreenQuickRender {

    @get:Rule
    val composeRule = createComposeRule()

    private fun capture(name: String, content: @Composable () -> Unit) {
        composeRule.setContent {
            RouseContextTheme(darkTheme = true) { content() }
        }
        composeRule.onRoot().captureRoboImage("screenshots/quick_$name.png")
    }

    @Test
    fun dashboard() = capture("dashboard") {
        MainDashboardScreen(
            state = DashboardState(
                connectionStatus = ConnectionStatus.CONNECTED,
                activeSessionCount = 1,
                integrations = listOf(
                    IntegrationItem(
                        id = "health",
                        name = "Health Connect",
                        status = IntegrationStatus.ACTIVE,
                        url = ""
                    ),
                    IntegrationItem(
                        id = "notifications",
                        name = "Notifications",
                        status = IntegrationStatus.PENDING,
                        url = ""
                    )
                ),
                recentActivity = listOf(
                    AuditEntry("10:32 AM", "health/get_steps", 142),
                    AuditEntry("10:31 AM", "health/get_sleep", 89),
                    AuditEntry("Apr 7, 3:15 PM", "health/get_heart_rate", 1250)
                )
            )
        )
    }

    @Test
    fun manageActive() = capture("manage_active") {
        IntegrationManageScreen(
            state = IntegrationManageState(
                status = IntegrationStatus.ACTIVE,
                recentActivity = listOf(
                    AuditEntry("10:32 AM", "get_steps", 142),
                    AuditEntry("10:31 AM", "get_sleep", 89),
                    AuditEntry("Apr 7, 3:15 PM", "get_heart_rate", 1250)
                ),
                authorizedClients = listOf(
                    AuthorizedClient("Claude", "Apr 2", "2 hours ago"),
                    AuthorizedClient("Cursor", "Apr 3", "just now")
                )
            )
        )
    }

    @Test
    fun managePending() = capture("manage_pending") {
        IntegrationManageScreen(
            state = IntegrationManageState(
                status = IntegrationStatus.PENDING
            )
        )
    }

    @Test
    fun manageManyClients() = capture("manage_many_clients") {
        IntegrationManageScreen(
            state = IntegrationManageState(
                status = IntegrationStatus.ACTIVE,
                recentActivity = listOf(
                    AuditEntry("10:32 AM", "get_steps", 142),
                    AuditEntry("10:31 AM", "get_sleep", 89),
                    AuditEntry("Apr 7, 3:15 PM", "get_heart_rate", 1250)
                ),
                authorizedClients = listOf(
                    AuthorizedClient("Claude", "Apr 2", "2 hours ago"),
                    AuthorizedClient("Cursor", "Apr 3", "just now"),
                    AuthorizedClient("Windsurf", "Apr 4", "yesterday"),
                    AuthorizedClient("Cline", "Apr 5", "3 days ago"),
                    AuthorizedClient("Continue", "Apr 6", "5 days ago"),
                    AuthorizedClient("Aider", "Apr 7", "1 week ago")
                )
            )
        )
    }
}
