package com.rousecontext.app.ui.navigation.destinations

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.rousecontext.api.McpIntegration
import com.rousecontext.app.McpUrlProvider
import com.rousecontext.app.R
import com.rousecontext.app.ui.navigation.ConfigureNavBar
import com.rousecontext.app.ui.navigation.Routes
import com.rousecontext.app.ui.screens.IntegrationEnabledContent
import com.rousecontext.app.ui.screens.IntegrationEnabledState
import com.rousecontext.app.ui.screens.IntegrationEnabledUrlState
import com.rousecontext.mcp.core.McpSession
import org.koin.compose.koinInject

fun NavGraphBuilder.integrationEnabledDestination(navController: NavController) {
    composable(
        route = Routes.INTEGRATION_ENABLED,
        arguments = listOf(
            navArgument("integrationId") {
                type = NavType.StringType
            }
        )
    ) { backStackEntry ->
        val integrationId = backStackEntry.arguments
            ?.getString("integrationId")
            ?: return@composable
        val urlProvider: McpUrlProvider = koinInject()
        val integrations: List<McpIntegration> = koinInject()
        val integration =
            integrations.find { it.id == integrationId }
        var retryCount by remember { mutableIntStateOf(0) }
        val urlState by rememberUrlLookupState(urlProvider, integrationId, retryCount)
        val integrationName = integration?.displayName
            ?: integrationId
        // Back from IntegrationEnabled MUST land on IntegrationManage
        // with the intermediate setup screens popped. A naive
        // popBackStack() would reveal IntegrationSetup (whose state
        // has already transitioned to Complete) or an abandoned
        // permission-setup screen, either flashing blank or looping
        // forward (see #62). Using the same destination +
        // popUpTo(HOME) as "Finish Later" guarantees we land on
        // [HOME, IntegrationManage] regardless of whether we
        // arrived here via fresh setup or the Add Client action on
        // an existing integration's manage screen.
        val finishToManage: () -> Unit = {
            navController.navigate(
                Routes.integrationManage(integrationId)
            ) {
                popUpTo(Routes.HOME)
            }
        }
        ConfigureNavBar(
            title = stringResource(
                R.string.destination_title_integration_enabled,
                integrationName
            ),
            showBackButton = true,
            onBackPressed = finishToManage
        )

        // Auto-navigate to approval when a client auth request arrives
        val mcpSession: McpSession = koinInject()
        LaunchedEffect(Unit) {
            mcpSession.authorizationCodeManager.newRequestFlow
                .collect {
                    navController.navigate(Routes.AUTH_APPROVAL)
                }
        }

        IntegrationEnabledContent(
            state = IntegrationEnabledState(
                integrationName = integrationName,
                url = (urlState as? IntegrationEnabledUrlState.Ready)?.url ?: "",
                urlState = urlState
            ),
            onCancel = finishToManage,
            onRetryUrl = { retryCount++ }
        )
    }
}

/**
 * #164: the URL lookup goes through three states -- Loading while the suspend
 * call is in flight, Ready when a secret resolves, and Unavailable when
 * buildUrl returns null (subdomain or per-integration secret not yet
 * persisted). [retryCount] keys [produceState] so the Retry button can
 * re-run the lookup without reconstructing the destination.
 */
@Composable
private fun rememberUrlLookupState(
    urlProvider: McpUrlProvider,
    integrationId: String,
    retryCount: Int
): State<IntegrationEnabledUrlState> = produceState<IntegrationEnabledUrlState>(
    IntegrationEnabledUrlState.Loading,
    integrationId,
    retryCount
) {
    value = IntegrationEnabledUrlState.Loading
    val resolved = urlProvider.buildUrl(integrationId)
    value = if (resolved != null) {
        IntegrationEnabledUrlState.Ready(resolved)
    } else {
        IntegrationEnabledUrlState.Unavailable
    }
}
